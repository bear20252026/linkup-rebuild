package gateway

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"linkup.local/server/internal/contract"
	"linkup.local/server/internal/dto"
	"linkup.local/server/internal/openim"
	"linkup.local/server/internal/store"
)

// StatusNotFoundUser is the answer for "this match code belongs to nobody". The number is a choice,
// not a recovery: the client only tests `status != 200` and toasts `msg`, so the original server's
// code for this case is not observable from the APK.
const StatusNotFoundUser = 1002

// handleStatus maps a failure onto the client's envelope. An unreachable IM service is reported, not
// swallowed: an empty friend list would look exactly like data loss.
func (g *Gateway) handleStatus(c *gin.Context, err error, what string) bool {
	if err == nil {
		return true
	}
	var imErr *openim.Error
	switch {
	case errors.As(err, &imErr):
		g.log.Error("openim refused", "op", what, "err", err)
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, what+"失败"))
	case errors.Is(err, store.ErrNotFound):
		c.JSON(http.StatusOK, contract.Fail(StatusNotFoundUser, "该用户不存在"))
	default:
		g.log.Error("openim unreachable", "op", what, "err", err)
		c.JSON(http.StatusOK, contract.Fail(http.StatusInternalServerError, "服务暂时不可用，请稍后重试"))
	}
	return false
}

// overQuota answers the question the client asks before showing the membership upsell. The limit is a
// boot flag because how many friends a free account may hold was never visible in the client.
func (g *Gateway) overQuota(u store.User) bool {
	if g.opts.FriendLimit < 0 || u.IsVip() {
		return false
	}
	return g.opts.Store.FriendCount(u.ID) >= g.opts.FriendLimit
}

func (g *Gateway) toUser(u store.User, token string) dto.User {
	return dto.User{
		Avatar:        dto.Text(u.Avatar),
		GetuiCid:      dto.Text(u.GetuiCid),
		HadFriend:     g.opts.Store.FriendCount(u.ID) > 0,
		IMPassword:    dto.Text(""),
		IsRegister:    u.IsRegister,
		MainUser:      u.MainUserID,
		Nickname:      dto.Text(u.Nickname),
		Phone:         dto.Text(u.Phone),
		RegisterAv:    dto.Text(u.Avatar),
		Sex:           u.Sex,
		Stage:         u.Stage,
		Token:         dto.Text(token),
		UserID:        u.ID,
		VipExpireTime: u.VIPExpire,
		VipSubType:    u.VIPSubType,
		VipType:       boolToInt(u.VIPSubType > 0),
	}
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

func (g *Gateway) clientConfig(c *gin.Context) {
	c.JSON(http.StatusOK, contract.OK(g.opts.ClientConfig))
}

func (g *Gateway) loginCut(c *gin.Context) {
	// "1" asks the GY SDK for a cached number, "3" asks FlyVerify; any other value makes the client skip
	// the one-tap path. Which one production answers is not recoverable from the APK.
	c.JSON(http.StatusOK, contract.OK(dto.Cut{LoginType: dto.Text(g.opts.CutLoginType)}))
}

func (g *Gateway) sendCode(c *gin.Context) {
	phone := strings.TrimSpace(c.Query("phone"))
	if !validPhone(phone) {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "手机号格式不正确"))
		return
	}
	code, err := g.opts.Store.IssueCode(phone, 5*time.Minute)
	if err != nil {
		if errors.Is(err, store.ErrCodeCooldown) {
			c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, err.Error()))
			return
		}
		c.JSON(http.StatusOK, contract.Fail(http.StatusInternalServerError, "发送失败"))
		return
	}
	// Nothing here can reach an SMS provider: a backend under reconstruction must not be able to send
	// real messages to real numbers.
	g.log.Info("verification code issued", "phone", maskPhone(phone), "code", code.Value)
	c.JSON(http.StatusOK, contract.OK(nil))
}

func (g *Gateway) loginByPhone(c *gin.Context) {
	form := body(c)
	phone := strings.TrimSpace(str(form, "phone"))
	// typeId 1 and 3 are the one-tap paths, which need vendor SDKs we cannot obtain; they are refused
	// out loud rather than answered with a login that never happened.
	if id := int(num(form, "typeId")); id != contract.TypeIDSMS {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "一键登录需要号码认证 SDK，尚未接回"))
		return
	}
	if !validPhone(phone) {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "手机号格式不正确"))
		return
	}
	if !g.codeAccepted(phone, str(form, "smsCode")) {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "验证码错误或已过期"))
		return
	}
	g.loginAnswer(c, phone)
}

// codeAccepted honours the boot-time dev code when one is configured, and otherwise consumes a real
// issued code.
func (g *Gateway) codeAccepted(phone, code string) bool {
	if g.opts.DevSMSCode != "" && code == g.opts.DevSMSCode {
		return true
	}
	return g.opts.Store.CheckCode(phone, code)
}

func (g *Gateway) loginAnswer(c *gin.Context, phone string) {
	u, err := g.opts.Store.CreateAccount(phone)
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(http.StatusInternalServerError, "登录失败"))
		return
	}
	g.ensureIMAccount(c.Request.Context(), *u)
	token, err := contract.IssueToken(g.opts.JWTSecret, u.ID, u.MainUserID, g.opts.TokenTTL)
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(http.StatusInternalServerError, "登录失败"))
		return
	}
	c.JSON(http.StatusOK, contract.OK(g.toUser(*u, token)))
}

// ensureIMAccount registers the account with OpenIM. A failure is logged and not fatal: the relation
// calls will surface their own error, and losing login because the IM side was briefly unreachable
// would be worse than a retryable add-friend failure.
func (g *Gateway) ensureIMAccount(ctx context.Context, u store.User) {
	if g.opts.IM == nil {
		return
	}
	err := g.opts.IM.RegisterUsers(ctx, openim.Account{
		UserID:   store.IMID(u.ID),
		Nickname: u.Nickname,
		FaceURL:  u.Avatar,
	})
	if err != nil && !openim.IsAlreadyKnown(err) {
		g.log.Warn("openim register failed", "userId", u.ID, "err", err)
	}
}

// loginByThird answers `api/user/login`, the WeChat (platform 4) and QQ (platform 5) path. The body is
// exactly `unionId, platform, avatar, nickname, phone`, with platform as a *string* — the client sends
// the phone only when the user typed it to bind, so a login can arrive with `phone: null`.
//
// The whole path sits behind -enable-third-login (default off). The pause is not the audit's P0-1
// fix, it is the pause *of* it: the unionId and phone are client-asserted, so an enabled deployment
// without a vendor OAuth code exchange still hands a JWT — sometimes a stranger's whole account — to
// whoever types the answer first. Re-enable only together with that server-side verification; the
// rebuilt client hides its vendor buttons behind the same switch meanwhile.
func (g *Gateway) loginByThird(c *gin.Context) {
	if !g.opts.ThirdPartyLogin {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusForbidden, "第三方登录暂未开放"))
		return
	}
	form := body(c)
	unionID := str(form, "unionId")
	if unionID == "" {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	u, err := g.opts.Store.ThirdAccount(int(num(form, "platform")), unionID, str(form, "phone"))
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(http.StatusInternalServerError, "登录失败"))
		return
	}
	profile := store.Profile{Nickname: optStr(form, "nickname"), Avatar: optStr(form, "avatar")}
	if profile.Nickname != nil || profile.Avatar != nil {
		if updated, err := g.opts.Store.UpdateProfile(u.ID, profile); err == nil {
			*u = updated
		}
	}
	g.ensureIMAccount(c.Request.Context(), *u)
	token, err := contract.IssueToken(g.opts.JWTSecret, u.ID, u.MainUserID, g.opts.TokenTTL)
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(http.StatusInternalServerError, "登录失败"))
		return
	}
	c.JSON(http.StatusOK, contract.OK(g.toUser(*u, token)))
}

func (g *Gateway) userInfo(c *gin.Context) {
	u, err := g.opts.Store.ByID(g.claims(c).UserID)
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(StatusNotFoundUser, "账号不存在"))
		return
	}
	c.JSON(http.StatusOK, contract.OK(g.toUser(u, "")))
}

func (g *Gateway) updateUserInfo(c *gin.Context) {
	form := body(c)
	claims := g.claims(c)
	// The recovered payload is exactly these four keys — the datasource builds
	// `mapOf("avatar" to …, "nickname" to …, "sex" to …, "stage" to …)`, where `sex` arrives through
	// the Baidu-ads constant `ArticleInfo.USER_SEX` and `avatar` through ImproveUserInfoActivity's own.
	updated, err := g.opts.Store.UpdateProfile(claims.UserID, store.Profile{
		Nickname: optStr(form, "nickname"),
		Avatar:   optStr(form, "avatar"),
		Sex:      optNum(form, "sex"),
		Stage:    optNum(form, "stage"),
	})
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(StatusNotFoundUser, "账号不存在"))
		return
	}
	if g.opts.IM != nil {
		if err := g.opts.IM.UpdateProfile(c.Request.Context(), store.IMID(updated.ID), updated.Nickname, updated.Avatar); err != nil {
			g.log.Warn("openim profile sync failed", "err", err)
		}
	}
	c.JSON(http.StatusOK, contract.OK(g.toUser(updated, "")))
}

// imToken issues the OpenIM user token the client SDK logs in with — the first caller of
// `openim.Client.UserToken`, which existed unused until this route existed. The client supplies neither
// the IM id nor the ws address from its own constants: both come from here, keyed to the signed-in
// account, so a token can never outlive the session it was issued from.
func (g *Gateway) imToken(c *gin.Context) {
	u, err := g.opts.Store.ByID(g.claims(c).UserID)
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(StatusNotFoundUser, "账号不存在"))
		return
	}
	token, expire, err := g.opts.IM.UserToken(c.Request.Context(), store.IMID(u.ID), openim.PlatformAndroid)
	if err != nil {
		g.log.Warn("openim user token failed", "err", err)
		c.JSON(http.StatusOK, contract.Fail(http.StatusInternalServerError, "服务暂时不可用，请稍后重试"))
		return
	}
	c.JSON(http.StatusOK, contract.OK(dto.ImToken{
		ImUserId:          store.IMID(u.ID),
		ImToken:           token,
		ExpireTimeSeconds: expire,
		WsUrl:             g.opts.OpenIMWs,
	}))
}

func (g *Gateway) publicKey(c *gin.Context) {
	c.JSON(http.StatusOK, contract.OK(dto.PublicKey{PublicKey: dto.Text(g.pem)}))
}

func (g *Gateway) preConfirm(c *gin.Context) {
	code, err := parseInt(c.Query("code"))
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "匹配码无效"))
		return
	}
	me, err := g.opts.Store.ByID(g.claims(c).UserID)
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(StatusNotFoundUser, "账号不存在"))
		return
	}
	// `code` is the user id: the client shows `userId` as the match code and types it back in.
	found, err := g.opts.Store.ByID(code)
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(StatusNotFoundUser, "该用户不存在"))
		return
	}
	if found.ID == me.ID {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "不能添加自己"))
		return
	}
	c.JSON(http.StatusOK, contract.OK(dto.Friend{
		Avatar:           dto.Text(found.Avatar),
		IsFriendLimit:    g.overQuota(me),
		Nickname:         dto.Text(found.Nickname),
		OtherFriendLimit: g.overQuota(found),
		Permission:       permission(g.opts.Store.ExtraFor(me.ID, found.ID)),
		Sex:              dto.Text(strconv.Itoa(found.Sex)),
		UserID:           found.ID,
	}))
}

// permission projects the two switches LinkUp stores per direction onto the ten fields the client
// parses. The extras only carry location/call/all, so `all` fans out — the original's own grouping is
// not visible in the client, and the mapping is marked for the day the real server is compared.
func permission(e store.Extra) *dto.Permission {
	return &dto.Permission{
		SensLocation:      e.SensLoc,
		SensCall:          e.SensCall,
		SensAppUse:        e.SensAll,
		SensPhoneState:    e.SensAll,
		SensPhoneUse:      e.SensAll,
		SensRealtimeVoice: e.SensAll,
		SensScreenShot:    e.SensAll,
		SensSensitive:     e.SensAll,
		SensTrack:         e.SensAll,
		SensLockPhone:     e.SensAll,
	}
}

func (g *Gateway) apply(c *gin.Context) {
	form := body(c)
	me := g.claims(c).UserID
	target, err := g.opts.Store.ByID(num(form, "code"))
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(StatusNotFoundUser, "该用户不存在"))
		return
	}
	if target.ID == me {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "不能添加自己"))
		return
	}
	// What the sender chose has to survive until their answer, because `api/relation/apply` carries it
	// and nothing else remembers it: OpenIM only knows that a request exists.
	sens := form["sensSetting"]
	if err := g.opts.Store.RememberApply(store.Apply{
		FromID:   me,
		ToID:     target.ID,
		Relation: int(num(form, "relation")),
		SensAll:  boolAt(sens, "sensAppUse"),
		SensLoc:  boolAt(sens, "sensLocation"),
		SensCall: boolAt(sens, "sensCall"),
	}); err != nil {
		c.JSON(http.StatusOK, contract.Fail(http.StatusInternalServerError, "申请失败"))
		return
	}
	if !g.handleStatus(c, g.opts.IM.AddFriend(c.Request.Context(), store.IMID(me), store.IMID(target.ID), "请求添加你为好友"), "添加好友") {
		return
	}
	c.JSON(http.StatusOK, contract.OK(dto.FriendApply{
		ApplyStatus: applyStatusSent,
		Avatar:      dto.Text(target.Avatar),
		FriendID:    target.ID,
		Nickname:    dto.Text(target.Nickname),
		Sex:         dto.Text(strconv.Itoa(target.Sex)),
		UserID:      me,
	}))
}

// applyStatusSent is 1 because that is what the client's own detail screen renders as "已发送".
const applyStatusSent = 1

// confirm answers `api/relation/confirm`. The requester's id arrives as a query parameter — the
// recovered declaration is `@Query("code") code: Long` next to a body that repeats it — so the query
// is what this handler reads.
//
// Two directions are recorded because the client stores `relation` and the permission switches per
// direction: the confirmer's own choices come from this call's body, and the requester's keep what
// they submitted with `api/relation/apply`. With no recorded request — a confirm from a device that
// never saw the apply — the confirmer's values stand for both sides.
func (g *Gateway) confirm(c *gin.Context) {
	code, err := parseInt(c.Query("code"))
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	form := body(c)
	me := g.claims(c).UserID
	requester, err := g.opts.Store.ByID(code)
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(StatusNotFoundUser, "该用户不存在"))
		return
	}
	if g.overQuota(mustUser(g.opts.Store, me)) {
		c.JSON(http.StatusOK, contract.OK(dto.ConfirmAdd{FriendLimit: 1, FriendID: requester.ID}))
		return
	}
	pending, hasPending := g.opts.Store.ApplyFor(requester.ID, me)
	relation := int(num(form, "relation"))
	if relation == 0 && hasPending {
		relation = pending.Relation
	}
	if !g.handleStatus(c, g.opts.IM.RespondApply(c.Request.Context(), store.IMID(requester.ID), store.IMID(me), openim.HandleResultAccepted, ""), "同意添加") {
		return
	}
	// 隐私旗标在确认时刻的默认值:**缺省即全开**(显式传参才关)——确认后的位置/通话/敏感读
	// 由服务端按旗标强制(审计 P1-2),默认关会把重建客户端自己的确认流读死。
	sens := form["sensSetting"]
	sensLoc, sensCall, sensAll := true, true, true
	if sens != nil {
		sensLoc, sensCall, sensAll = boolAt(sens, "sensLocation"), boolAt(sens, "sensCall"), boolAt(sens, "sensAppUse")
	}
	own := store.Extra{
		OwnerID:  me,
		FriendID: requester.ID,
		Relation: relation,
		SensAll:  sensAll,
		SensLoc:  sensLoc,
		SensCall: sensCall,
	}
	theirs := own
	theirs.OwnerID, theirs.FriendID = requester.ID, me
	if hasPending {
		theirs.Relation = pending.Relation
		theirs.SensAll, theirs.SensLoc, theirs.SensCall = pending.SensAll, pending.SensLoc, pending.SensCall
	}
	for _, e := range []store.Extra{own, theirs} {
		if err := g.opts.Store.PutExtra(e); err != nil {
			g.log.Warn("record relation failed", "err", err)
		}
	}
	if err := g.opts.Store.ForgetApply(requester.ID, me); err != nil {
		g.log.Warn("clear apply failed", "err", err)
	}
	c.JSON(http.StatusOK, contract.OK(dto.ConfirmAdd{FriendLimit: 0, FriendID: requester.ID}))
}

func (g *Gateway) applyList(c *gin.Context) {
	me := g.claims(c).UserID
	page, size := queryInt(c, "page", 1), queryInt(c, "pageSize", 20)
	applies, _, err := g.opts.IM.ReceivedApplies(c.Request.Context(), store.IMID(me), page, size)
	if !g.handleStatus(c, err, "申请列表") {
		return
	}
	meUser := mustUser(g.opts.Store, me)
	limit := g.overQuota(meUser)
	unread := 0
	list := make([]*dto.FriendRequest, 0, len(applies))
	for _, a := range applies {
		from := store.ParseID(a.FromUserID)
		recorded, _ := g.opts.Store.ApplyFor(from, me)
		if a.HandleResult == openim.HandleResultIgnored {
			unread++
		}
		name := a.FromNickname
		if name == "" {
			name = a.ToNickname
		}
		list = append(list, &dto.FriendRequest{
			Avatar:        dto.Text(a.FromFaceURL),
			CreateAt:      a.CreateTime,
			IsFriendLimit: limit,
			Nickname:      dto.Text(name),
			Relation:      recorded.Relation,
			Sex:           sexOf(g.opts.Store, from),
			Status:        int(a.HandleResult),
			UserID:        from,
		})
	}
	c.JSON(http.StatusOK, contract.OK(dto.FriendRequestList{
		IsFriendLimit: &limit,
		List:          list,
		UnreadCount:   &unread,
	}))
}

func (g *Gateway) friendList(c *gin.Context) {
	me := g.claims(c).UserID
	page, size := queryInt(c, "page", 1), queryInt(c, "pageSize", 100)
	key := strings.TrimSpace(c.Query("key"))
	friends, _, err := g.opts.IM.Friends(c.Request.Context(), store.IMID(me), page, size)
	if !g.handleStatus(c, err, "好友列表") {
		return
	}
	list := make([]*dto.FriendRow, 0, len(friends))
	for _, f := range friends {
		id := store.ParseID(f.FriendUser.UserID)
		if key != "" && !strings.Contains(f.FriendUser.Nickname, key) && !strings.Contains(f.Remark, key) {
			continue
		}
		extra := g.opts.Store.ExtraFor(me, id)
		remark := f.Remark
		if remark == "" {
			remark = extra.Remark
		}
		list = append(list, &dto.FriendRow{
			Avatar: dto.Text(f.FriendUser.FaceURL),
			// OpenIM's createTime is epoch seconds and this bean's createAt is consumed as
			// `Date(createAt * 1000L)`, so it passes straight through.
			CreateAt: f.CreateTime,
			Nickname: dto.Text(f.FriendUser.Nickname),
			Relation: extra.Relation,
			Remark:   dto.Text(remark),
			Sex:      sexOf(g.opts.Store, id),
			UserID:   id,
		})
	}
	c.JSON(http.StatusOK, contract.OK(dto.FriendList{List: list}))
}

func (g *Gateway) friendInfo(c *gin.Context) {
	id, err := parseInt(c.Query("queryId"))
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	target, err := g.opts.Store.ByID(id)
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(StatusNotFoundUser, "该用户不存在"))
		return
	}
	extra := g.opts.Store.ExtraFor(g.claims(c).UserID, id)
	c.JSON(http.StatusOK, contract.OK(dto.FriendInfo{
		Avatar:     dto.Text(target.Avatar),
		Client:     dto.Text(""),
		CreateAt:   extra.Since,
		Nickname:   dto.Text(target.Nickname),
		Permission: permission(extra),
		Relation:   dto.Text(strconv.Itoa(extra.Relation)),
		Remark:     dto.Text(extra.Remark),
		Sex:        dto.Text(strconv.Itoa(target.Sex)),
		UserID:     target.ID,
	}))
}

// deleteFriend removes the pair in **both** directions. Measured on the live server: `delete_friend`
// is per-owner, so after deleting B→A the other side still lists A (`inUser2Friends` stays true),
// while LinkUp's own model forgets the relation both ways. Half a deletion would show up as a friend
// that reappears on one phone, so both calls have to succeed.
func (g *Gateway) deleteFriend(c *gin.Context) {
	form := body(c)
	id := num(form, "friendId")
	me := g.claims(c).UserID
	ctx := c.Request.Context()
	for _, pair := range [][2]int64{{me, id}, {id, me}} {
		if err := g.opts.IM.DeleteFriend(ctx, store.IMID(pair[0]), store.IMID(pair[1])); err != nil {
			g.handleStatus(c, err, "删除好友")
			return
		}
	}
	if err := g.opts.Store.ForgetPair(me, id); err != nil {
		g.log.Warn("forget relation failed", "err", err)
	}
	c.JSON(http.StatusOK, contract.OK(nil))
}

// updateRelation answers `api/relation/update`. A relation row is the *only* grant path to someone's
// tracks (location.go's `hasRelation` reads it), so the write that mints or rewrites one must be
// reachable only across a confirmed pair: the target has to be an IM friend **and** already carry a
// confirm-stamped row. Without both gates, one request forges a row against any user id and opens
// their whole location history — the audit's P0-2.
func (g *Gateway) updateRelation(c *gin.Context) {
	form := body(c)
	me, id := g.claims(c).UserID, num(form, "friendId")
	if id <= 0 {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	if !g.confirmedPair(c, me, id, "仅确认过的好友可修改关系") {
		return
	}
	extra := g.opts.Store.ExtraFor(me, id)
	extra.OwnerID, extra.FriendID, extra.Relation = me, id, int(num(form, "relation"))
	if err := g.opts.Store.PutExtra(extra); err != nil {
		c.JSON(http.StatusOK, contract.Fail(http.StatusInternalServerError, "修改失败"))
		return
	}
	c.JSON(http.StatusOK, contract.OK(nil))
}

// confirmedPair is the shared gate of the relation-row writes: IM friendship, then a row that
// relation/confirm stamped. Refusing is the caller's answer shape, so the helper writes it and says
// whether the request may proceed.
func (g *Gateway) confirmedPair(c *gin.Context, me, id int64, msg string) bool {
	if friend, err := g.opts.IM.IsFriend(c.Request.Context(), store.IMID(me), store.IMID(id)); err != nil || !friend {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusForbidden, msg))
		return false
	}
	if g.opts.Store.ExtraFor(me, id).Since == 0 {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusForbidden, msg))
		return false
	}
	return true
}

func (g *Gateway) updateRemark(c *gin.Context) {
	form := body(c)
	me, id := g.claims(c).UserID, num(form, "friendId")
	remark := str(form, "remark")
	if !g.handleStatus(c, g.opts.IM.SetRemark(c.Request.Context(), store.IMID(me), store.IMID(id), remark), "修改备注") {
		return
	}
	extra := g.opts.Store.ExtraFor(me, id)
	extra.OwnerID, extra.FriendID, extra.Remark = me, id, remark
	if err := g.opts.Store.PutExtra(extra); err != nil {
		g.log.Warn("record remark failed", "err", err)
	}
	c.JSON(http.StatusOK, contract.OK(nil))
}

func mustUser(s *store.Store, id int64) store.User {
	u, err := s.ByID(id)
	if err != nil {
		return store.User{ID: id}
	}
	return u
}

func sexOf(s *store.Store, id int64) int {
	u, err := s.ByID(id)
	if err != nil {
		return 0
	}
	return u.Sex
}

func boolAt(v any, key string) bool {
	m, ok := v.(map[string]any)
	if !ok {
		return false
	}
	b, _ := m[key].(bool)
	return b
}

func parseInt(text string) (int64, error) { return strconv.ParseInt(strings.TrimSpace(text), 10, 64) }

// validPhone accepts a mainland mobile number, which is the only shape the client's own input filter
// can produce; anything shorter than that cannot be a real account either.
func validPhone(phone string) bool {
	if len(phone) < 7 || len(phone) > 15 {
		return false
	}
	for _, r := range phone {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

func maskPhone(phone string) string {
	if len(phone) < 7 {
		return "***"
	}
	return phone[:3] + "****" + phone[len(phone)-4:]
}
