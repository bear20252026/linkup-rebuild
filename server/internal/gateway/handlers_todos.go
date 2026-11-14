package gateway

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"linkup.local/server/internal/contract"
	"linkup.local/server/internal/dto"
	"linkup.local/server/internal/store"
)

// 四象限待办域（DESIGN 五点四十二）：`api/four_quadrant_list` 的三路由 + 守护端/本人共用的
// 台账。listType 1..4 是艾森豪威尔四象限（TodoRsp 字段名的顺序即 1..4）；原版 get_list 的
// listType 参数语义不可恢复，本重建忽略它、一次回答全部四组（DESIGN 记录）。

// todoGetList is `api/four_quadrant_list/get_list`: the caller's todos grouped into the four
// quadrants, each row enriched with the owner's profile (the recovered TodoModel carried the
// creator's avatar/nickname/sex — in this rebuild every list is the owner's own).
func (g *Gateway) todoGetList(c *gin.Context) {
	me := g.claims(c).UserID
	rows := g.opts.Store.TodosFor(me)
	self, selfErr := g.opts.Store.ByID(me)
	byType := map[int][]*dto.TodoModel{}
	for _, e := range rows {
		model := &dto.TodoModel{
			ID:       e.ID,
			ListType: e.ListType,
			Content:  textPtr(e.Content),
			IsFinish: e.IsFinish,
			UserID:   me,
		}
		if selfErr == nil {
			model.Nickname = textPtr(self.Nickname)
			model.Avatar = textPtr(self.Avatar)
			model.Sex = self.Sex
		}
		byType[e.ListType] = append(byType[e.ListType], model)
	}
	c.JSON(http.StatusOK, contract.OK(dto.TodoRsp{
		ImportantAndUrgent:       byType[1],
		ImportantButNutAndUrgent: byType[2],
		NotImportantButUrgent:    byType[3],
		NotImportantNotUrgent:    byType[4],
	}))
}

// todoEdit is `api/four_quadrant_list/edit`: insert (id 0/absent) or update (known id) one todo.
// The validation errors surface verbatim — "content is empty"/"outside the four quadrants" are
// actionable, unlike a bare 参数异常.
func (g *Gateway) todoEdit(c *gin.Context) {
	form := body(c)
	_, err := g.opts.Store.PutTodo(g.claims(c).UserID, store.TodoEntry{
		ID:       num(form, "id"),
		ListType: int(num(form, "listType")),
		Content:  str(form, "content"),
		IsFinish: truthy(form, "isFinish"),
	})
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, err.Error()))
		return
	}
	c.JSON(http.StatusOK, contract.OK(nil))
}

// todoDel is `api/four_quadrant_list/del`; an unknown id answers the param error, not a silent OK —
// the screen needs to know its row was already gone.
func (g *Gateway) todoDel(c *gin.Context) {
	form := body(c)
	if !g.opts.Store.DeleteTodo(g.claims(c).UserID, num(form, "id")) {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "待办不存在"))
		return
	}
	c.JSON(http.StatusOK, contract.OK(nil))
}
