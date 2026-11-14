package gateway

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"linkup.local/server/internal/contract"
	"linkup.local/server/internal/dto"
	"linkup.local/server/internal/store"
)

// 设置域与聚合域：`api/setting/im`（自动报备通知设置，GET 读 / POST 写，键集照客户端
// AutoReportSettings bean）、`api/setting/user_list` + `api/setting/update`（好友级隐私开关，
// 存 Extra 的敏感旗标）、`api/index`（守护 Tab 功能卡聚合，原版 HomeManager 的读数）、
// `api/version/check`（本 rebuild 恒答"已是最新"，升级通道是独立卡）。

// settingIMGet answers the account's AutoReport JSON: stored value, or the recovered defaults
// (all notice types 0 / notices false — the client bean's own zero values).
func (g *Gateway) settingIMGet(c *gin.Context) {
	me := g.claims(c).UserID
	raw := g.opts.Store.SettingFor(me, "im")
	if raw == "" {
		raw = "{}"
	}
	var pretty any
	_ = json.Unmarshal([]byte(raw), &pretty)
	c.JSON(http.StatusOK, contract.OK(pretty))
}

// settingIMUpdate stores the client's AutoReport bean verbatim. The update path on the original
// server is not recoverable from the APK (only the GET binding is visible); storing the whole bean
// keyed by user is this rebuild's choice, written down in DESIGN.
func (g *Gateway) settingIMUpdate(c *gin.Context) {
	form := body(c)
	raw, err := json.Marshal(form)
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	g.opts.Store.SetSetting(g.claims(c).UserID, "im", string(raw))
	c.JSON(http.StatusOK, contract.OK(nil))
}

// privacyUserList: one row per confirmed friend, carrying that direction's sens flags from Extra.
func (g *Gateway) privacyUserList(c *gin.Context) {
	me := g.claims(c).UserID
	friends, _, err := g.opts.IM.Friends(c.Request.Context(), store.IMID(me), 1, 100)
	if !g.handleStatus(c, err, "好友列表") {
		return
	}
	rows := make([]map[string]any, 0, len(friends))
	for _, f := range friends {
		id := store.ParseID(f.FriendUser.UserID)
		extra := g.opts.Store.ExtraFor(me, id)
		rows = append(rows, map[string]any{
			"userId":        id,
			"nickname":      f.FriendUser.Nickname,
			"remark":        extra.Remark,
			"avatar":        f.FriendUser.FaceURL,
			"sensLocation":  extra.SensLoc,
			"sensCall":      extra.SensCall,
			"sensAppUse":    extra.SensAll,
			"sensSensitive": extra.SensAll,
		})
	}
	c.JSON(http.StatusOK, contract.OK(map[string]any{"list": rows}))
}

// privacyUpdate flips one direction's sens flags (the recovered `api/setting/update` contract;
// the exact original body keys are not recoverable, so this rebuild pins friendId + four flags).
// The flags live on the relation row and the row is what location reads authorize on, so this write
// goes through the same confirmed-pair gate as `api/relation/update` — the old "no row yet, treat
// as friend" fallback could mint a row for any user id and open their tracks (audit P0-2), and a
// privacy flip has no business mutating the relation value anyway.
func (g *Gateway) privacyUpdate(c *gin.Context) {
	form := body(c)
	me, id := g.claims(c).UserID, num(form, "friendId")
	if id <= 0 {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	if !g.confirmedPair(c, me, id, "仅确认过的好友可修改隐私") {
		return
	}
	extra := g.opts.Store.ExtraFor(me, id)
	extra.OwnerID, extra.FriendID = me, id
	if v, ok := form["sensLocation"].(bool); ok {
		extra.SensLoc = v
	}
	if v, ok := form["sensCall"].(bool); ok {
		extra.SensCall = v
	}
	if v, ok := form["sensAppUse"].(bool); ok {
		extra.SensAll = v
	}
	if err := g.opts.Store.PutExtra(extra); err != nil {
		c.JSON(http.StatusOK, contract.Fail(http.StatusInternalServerError, "修改失败"))
		return
	}
	c.JSON(http.StatusOK, contract.OK(nil))
}

// index aggregates the guard tab's feature cards (`manager/x.java` HomeManager 的 `api/index`):
// the guarded peer's device facts, live location, call-record card, lock ledger row and this
// account's own permission row. The app-use cards stay empty until their report route carries data.
func (g *Gateway) index(c *gin.Context) {
	me := g.claims(c).UserID
	peers := g.guardPeers(c, me)
	payload := map[string]any{
		"phoneUseCard": nil,
		"appUseCard":   nil,
		"serverTime":   time.Now().Unix(),
	}
	for _, peer := range peers {
		if state := g.friendStateRow(peer); state != nil {
			payload["friendState"] = state
		}
		if g.sensAllows(peer, me, sensLoc) {
			if fix, ok := g.opts.Store.LatestFix(peer); ok {
				payload["friendLocation"] = &dto.FriendLocation{
					Address:     fix.Address,
					ArrivalTime: fix.TimePeriod,
					Lat:         fix.Lat,
					Lng:         fix.Lng,
					Speed:       float32(fix.Speed),
				}
			}
		}
		// 通话记录卡：被守护端的台账（call_record/report 供数）。只在有记录且对方未关通话共享
		// （SensCall）时下发——没有的时候缺键，客户端的卡照原版语义收起。
		if calls := g.opts.Store.CallRecordsFor(peer, indexCallRecordLimit); len(calls) > 0 && g.sensAllows(peer, me, sensCall) {
			rows := make([]any, 0, len(calls))
			for _, call := range calls {
				rows = append(rows, &dto.CallRecord{
					Contact:    textPtr(call.Contact),
					Duration:   call.Duration,
					IsIncoming: call.IsIncoming,
					Phone:      textPtr(call.Phone),
					Time:       call.Time,
				})
			}
			payload["callRecordList"] = rows
		}
		// 应用使用卡（app_use/report 供数）："今天"是被守护端自己上报的本地日（台账里最新一天），
		// 服务端不做时区换算；昨日按 86400 秒回退——本地日的近似口径，DESIGN 记录在案。
		if card := g.appUseCardFor(peer); card != nil && g.sensAllows(peer, me, sensAll) {
			payload["appUseCard"] = card
		}
		// 敏感行为卡（sensitive/report 供数）：最新几条，只在有台账时下发（无则缺键，卡收起）。
		if sensRows := g.opts.Store.SensRecordsFor(peer, "", indexSensLimit, g.opts.DayZone); len(sensRows) > 0 && g.sensAllows(peer, me, sensAll) {
			rows := make([]any, 0, len(sensRows))
			for _, e := range sensRows {
				rows = append(rows, &dto.SensRecord{
					Type:      e.Type,
					EventTime: e.EventTime,
					AppName:   textPtr(e.AppName),
					Phone:     textPtr(e.Phone),
					CreatedAt: e.CreatedAt,
				})
			}
			payload["sensitiveCard"] = rows
		}
		lock := g.opts.Store.LockFor(me, peer)
		payload["lockInfo"] = map[string]any{
			"userId":       peer,
			"lockTime":     lock.LockTime,
			"lockDuration": lock.LockDuration,
			"lockTitle":    lock.LockTitle,
		}
		break // the original's cards show the current guard target only
	}
	self, selfErr := g.opts.Store.ByID(me)
	payload["selfPermission"] = map[string]any{"userId": me}
	payload["phoneInfoCard"] = g.friendStateRow(me)
	if selfErr == nil {
		payload["selfInfo"] = map[string]any{"nickname": self.Nickname, "avatar": self.Avatar}
	}
	c.JSON(http.StatusOK, contract.OK(payload))
}

// versionCheck: this rebuild is always the newest — the updater channel is its own card.

/** The 通话记录 card reads at most this many ledger rows per guard target. */
const indexCallRecordLimit = 10

/** The 敏感行为 card reads at most this many ledger rows per guard target. */
const indexSensLimit = 5

func (g *Gateway) versionCheck(c *gin.Context) {
	c.JSON(http.StatusOK, contract.OK(map[string]any{
		"version":    "1.6.6",
		"needUpdate": false,
		"url":        "",
	}))
}
