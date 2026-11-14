package gateway

import (
	"encoding/json"
	"net/http"

	"github.com/gin-gonic/gin"

	"linkup.local/server/internal/contract"
	"linkup.local/server/internal/store"
)

// 小域四件（DESIGN 五点四十五）：意见反馈 / 首页弹窗 / 纪念日 / 收藏地址标签——客户端
// LinkUpApi 已声明的四个外围域，服务端补齐路由让契约闭环。口径如实记录：
// - 反馈：append-only 台账；回复通道未重建，check/checkPop 答诚实零值。
// - 首页弹窗：运营配置不可恢复，恒答空列表（无弹窗）。
// - 纪念日/地址标签：per-account 台账，PutAnniversary/AddAddressTag 的校验在 store。

// feedbackCreate is `api/feedback`: one 意见反馈, append-only.
func (g *Gateway) feedbackCreate(c *gin.Context) {
	form := body(c)
	g.opts.Store.AddFeedback(g.claims(c).UserID, str(form, "content"))
	c.JSON(http.StatusOK, contract.OK(nil))
}

// feedbackCheck is `api/feedback/check`: the reply channel is not rebuilt, so the honest answer
// is "nothing new" — the shape stays for the client's contract.
func (g *Gateway) feedbackCheck(c *gin.Context) {
	c.JSON(http.StatusOK, contract.OK(map[string]any{"hasNew": false}))
}

// feedbackCheckPop is `api/feedback/checkPop`: no reply → no pop.
func (g *Gateway) feedbackCheckPop(c *gin.Context) {
	c.JSON(http.StatusOK, contract.OK(map[string]any{"feedbackPop": false}))
}

// popHomepage is `api/pop/homePage`: the operation-driven pop list is not recoverable, so the
// honest answer is an empty list (no pop) rather than an invented campaign.
func (g *Gateway) popHomepage(c *gin.Context) {
	c.JSON(http.StatusOK, contract.OK(map[string]any{
		"popList":      []any{},
		"delayRefresh": 0,
	}))
}

// anniversaryCreateoredit is `api/anniversary/createOrEdit`: {id?, title, anniversaryTime} —
// insert or update one 纪念日 owned by the caller; the answer carries the stored row.
func (g *Gateway) anniversaryCreateoredit(c *gin.Context) {
	var payload struct {
		ID              int64  `json:"id"`
		Title           string `json:"title"`
		AnniversaryTime int64  `json:"anniversaryTime"`
	}
	if err := json.Unmarshal(rawBody(c), &payload); err != nil {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	entry, err := g.opts.Store.PutAnniversary(g.claims(c).UserID, storeAnniversaryEntry(payload))
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, err.Error()))
		return
	}
	c.JSON(http.StatusOK, contract.OK(map[string]any{
		"id":              entry.ID,
		"title":           entry.Title,
		"anniversaryTime": entry.Time,
		"userId":          g.claims(c).UserID,
	}))
}

// anniversaryChoose is `api/anniversary/choose`: the newest 纪念日 owned by `userId` — zero
// values when that account never created one (the honest absence, not an invented row).
func (g *Gateway) anniversaryChoose(c *gin.Context) {
	owner, err := parseInt(c.Query("userId"))
	if err != nil || owner <= 0 {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	entry := g.opts.Store.LatestAnniversary(owner)
	if entry.ID == 0 {
		c.JSON(http.StatusOK, contract.OK(map[string]any{}))
		return
	}
	c.JSON(http.StatusOK, contract.OK(map[string]any{
		"id":              entry.ID,
		"title":           entry.Title,
		"anniversaryTime": entry.Time,
		"userId":          owner,
	}))
}

// addressTagCreate is `api/address/tag`: one 收藏地址标签; the answer carries the stored row.
func (g *Gateway) addressTagCreate(c *gin.Context) {
	var payload struct {
		Name    string  `json:"name"`
		Lat     float64 `json:"lat"`
		Lng     float64 `json:"lng"`
		Address string  `json:"address"`
	}
	if err := json.Unmarshal(rawBody(c), &payload); err != nil {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	entry, err := g.opts.Store.AddAddressTag(g.claims(c).UserID, storeAddressTagEntry(payload))
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, err.Error()))
		return
	}
	c.JSON(http.StatusOK, contract.OK(map[string]any{
		"id":      entry.ID,
		"name":    entry.Name,
		"lat":     entry.Lat,
		"lng":     entry.Lng,
		"address": entry.Address,
	}))
}

// addressTagList is `api/address/tag_list`: the caller's tags, oldest first.
func (g *Gateway) addressTagList(c *gin.Context) {
	rows := g.opts.Store.AddressTagsFor(g.claims(c).UserID)
	list := make([]any, 0, len(rows))
	for _, e := range rows {
		list = append(list, map[string]any{
			"id":      e.ID,
			"name":    e.Name,
			"lat":     e.Lat,
			"lng":     e.Lng,
			"address": e.Address,
		})
	}
	c.JSON(http.StatusOK, contract.OK(list))
}

func storeAnniversaryEntry(payload struct {
	ID              int64  `json:"id"`
	Title           string `json:"title"`
	AnniversaryTime int64  `json:"anniversaryTime"`
}) store.AnniversaryEntry {
	return store.AnniversaryEntry{ID: payload.ID, Title: payload.Title, Time: payload.AnniversaryTime}
}

func storeAddressTagEntry(payload struct {
	Name    string  `json:"name"`
	Lat     float64 `json:"lat"`
	Lng     float64 `json:"lng"`
	Address string  `json:"address"`
}) store.AddressTagEntry {
	return store.AddressTagEntry{ID: 0, Name: payload.Name, Lat: payload.Lat, Lng: payload.Lng, Address: payload.Address}
}
