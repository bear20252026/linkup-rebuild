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
func (g *Gateway) privacyUpdate(c *gin.Context) {
	form := body(c)
	me, id := g.claims(c).UserID, num(form, "friendId")
	if id <= 0 {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	extra := g.opts.Store.ExtraFor(me, id)
	extra.OwnerID, extra.FriendID = me, id
	if extra.Relation == 0 {
		extra.Relation = 2 // 只对好友改隐私；无关系行时按好友兜底，与 location 域读权限一致
	}
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
// the guarded peer's device facts, live location, lock ledger row and this account's own
// permission row. Call records / app-use cards stay empty until their report routes carry data.
func (g *Gateway) index(c *gin.Context) {
	me := g.claims(c).UserID
	peers := g.guardPeers(c, me)
	payload := map[string]any{
		"callRecordList": []any{},
		"phoneUseCard":   nil,
		"appUseCard":     nil,
		"sensitiveCard":  []any{},
		"serverTime":     time.Now().Unix(),
	}
	for _, peer := range peers {
		if state := g.friendStateRow(peer); state != nil {
			payload["friendState"] = state
		}
		if fix, ok := g.opts.Store.LatestFix(peer); ok {
			payload["friendLocation"] = &dto.FriendLocation{
				Address:     fix.Address,
				ArrivalTime: fix.TimePeriod,
				Lat:         fix.Lat,
				Lng:         fix.Lng,
				Speed:       float32(fix.Speed),
			}
		}
		lock := g.opts.Store.LockFor(me, peer)
		payload["lockInfo"] = map[string]any{
			"userId":      peer,
			"lockTime":    lock.LockTime,
			"lockDuration": lock.LockDuration,
			"lockTitle":   lock.LockTitle,
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
func (g *Gateway) versionCheck(c *gin.Context) {
	c.JSON(http.StatusOK, contract.OK(map[string]any{
		"version":  "1.6.6",
		"needUpdate": false,
		"url":      "",
	}))
}
