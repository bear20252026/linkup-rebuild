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

// 敏感行为域（DESIGN 五点四十一，监控数据面第三域）：被守护端把敏感事件（本重建口径：应用
// 安装/卸载/更新）报上来，守护端按日读台账、`api/index` 的 sensitiveCard 读最新几条。
// 原版的事件类型表与 server_timer 语义都不可恢复——前者本重建自定口径（1=安装 2=卸载 3=更新），
// 后者客户端无调用方，路由不建，都写进 DESIGN 待原版源码或需求拍板。

// sensReport is `api/phone/sensitive/report`: the guarded phone posts one event per send (the
// recovered contract is a single SensBean body, not a batch). The body's unused fields are ignored —
// the ledger keeps what the collector actually produces (type/eventTime/appName/phone). The answer
// carries the recovered SensReportResult shape with zero stats: the original's post-report
// statistics are not recoverable, and zeros are honest where numbers would be invented.
func (g *Gateway) sensReport(c *gin.Context) {
	var payload struct {
		Type      int    `json:"type"`
		EventTime int64  `json:"eventTime"`
		AppName   string `json:"appName"`
		Phone     string `json:"phone"`
	}
	if err := json.Unmarshal(rawBody(c), &payload); err != nil {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	if payload.Type <= 0 || payload.EventTime <= 0 {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	g.opts.Store.AddSensRecords(g.claims(c).UserID, []store.SensEntry{
		{
			Type:      payload.Type,
			EventTime: payload.EventTime,
			AppName:   payload.AppName,
			Phone:     payload.Phone,
			CreatedAt: time.Now().Unix(),
		},
	})
	c.JSON(http.StatusOK, contract.OK(dto.SensReportResult{}))
}

// sensUser is `api/sensitive/user?date=&mainUser=`: the guardian reads the guarded peer's ledger
// for one yyyyMMdd day (empty date = the newest page). The peer must be connected to the caller
// (relation in either direction — the same rule the location reads use); the answer rows are the
// SensBean-compatible subset.
func (g *Gateway) sensUser(c *gin.Context) {
	me := g.claims(c).UserID
	peer, err := parseInt(c.Query("mainUser"))
	if err != nil || peer <= 0 || peer == me {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	if !hasRelation(g.opts.Store, me, peer) {
		g.log.Warn("sensitive read refused", "viewer", me, "target", peer)
		c.JSON(http.StatusOK, contract.Fail(contract.StatusForbidden, "没有查看该设备敏感行为的权限"))
		return
	}
	rows := g.opts.Store.SensRecordsFor(peer, c.Query("date"), sensUserLimit, g.opts.DayZone)
	list := make([]any, 0, len(rows))
	for _, e := range rows {
		list = append(list, &dto.SensRecord{
			Type:      e.Type,
			EventTime: e.EventTime,
			AppName:   textPtr(e.AppName),
			Phone:     textPtr(e.Phone),
			CreatedAt: e.CreatedAt,
		})
	}
	c.JSON(http.StatusOK, contract.OK(list))
}

// sensUserDays is `api/sensitive/user/days`: the yyyyMMdd days the CALLER has events on — the
// guarded phone reads its own ledger (the recovered signature carries no user id, so the guardian
// path goes through sensUser's date paging instead; written down in DESIGN).
func (g *Gateway) sensUserDays(c *gin.Context) {
	c.JSON(http.StatusOK, contract.OK(g.opts.Store.SensDaysFor(g.claims(c).UserID, g.opts.DayZone)))
}

// The sensitive card and the user page read at most this many ledger rows per answer.
const sensUserLimit = 20
