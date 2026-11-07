package gateway

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"linkup.local/server/internal/contract"
	"linkup.local/server/internal/dto"
	"linkup.local/server/internal/store"
)

// 锁机域：`api/relation/lock*` 六条路由。锁状态的存取在本 store（Extra 旁的 Locks map），锁命令
// 的下发不走网关——原版由发起端经 IM CMD（action `lockMachine`）直接发到对方，服务端只记账。
// 题库内容是产品内容而非协议：原版题库内嵌在 WindowHelper 的 JSON 里，这里给等规模的重建题库
// （DESIGN 五点三十三）。

// questionBank is this rebuild's riddle bank for the question lock. Content is a product choice;
// the wire shape (content/options/correctAnswer) is the recovered bean.
var questionBank = []dto.QuestionRow{
	{
		Content: "小明的妈妈有三个孩子，老大叫大毛，老二叫二毛，老三叫什么？",
		Options: []*dto.QuestionOptionRow{
			{Label: "A", Content: "大毛"},
			{Label: "B", Content: "二毛"},
			{Label: "C", Content: "三毛"},
			{Label: "D", Content: "小明"},
		},
		CorrectAnswer: "D",
	},
	{
		Content: "一年中哪个月有 28 天？",
		Options: []*dto.QuestionOptionRow{
			{Label: "A", Content: "2月"},
			{Label: "B", Content: "每个月"},
			{Label: "C", Content: "闰年的2月"},
			{Label: "D", Content: "1月"},
		},
		CorrectAnswer: "B",
	},
	{
		Content: "什么东西越洗越脏？",
		Options: []*dto.QuestionOptionRow{
			{Label: "A", Content: "衣服"},
			{Label: "B", Content: "水"},
			{Label: "C", Content: "毛巾"},
			{Label: "D", Content: "碗"},
		},
		CorrectAnswer: "B",
	},
}

func (g *Gateway) lockList(c *gin.Context) {
	me := g.claims(c).UserID
	friends, _, err := g.opts.IM.Friends(c.Request.Context(), store.IMID(me), 1, 100)
	if !g.handleStatus(c, err, "好友列表") {
		return
	}
	list := make([]*dto.LockInfoRow, 0, len(friends))
	now := time.Now().Unix()
	for _, f := range friends {
		id := store.ParseID(f.FriendUser.UserID)
		extra := g.opts.Store.ExtraFor(me, id)
		lock := g.opts.Store.LockFor(me, id)
		// The guarded device's version row is its own last phone-state report (待验 77): av/client
		// ride the report form, so an unreported device honestly shows empty strings.
		device := g.opts.Store.DeviceStateFor(id)
		row := &dto.LockInfoRow{
			UserID:           id,
			Remark:           dto.Text(extra.Remark),
			Nickname:         dto.Text(f.FriendUser.Nickname),
			Avatar:           dto.Text(f.FriendUser.FaceURL),
			Av:               dto.Text(device.Av),
			Client:           dto.Text(device.Client),
			OsVersion:        dto.Text(device.OsVersion),
			Relation:         extra.Relation,
			CreateAt:         extra.Since,
			ServerTime:       now,
			LockTime:         lock.LockTime,
			LockDuration:     lock.LockDuration,
			LockTitle:        dto.Text(lock.LockTitle),
			LockBackPic:      dto.Text(lock.LockBackPic),
		}
		if lock.Question != "" {
			row.Question = &lock.Question
		}
		list = append(list, row)
	}
	c.JSON(http.StatusOK, contract.OK(dto.LockInfoList{List: list}))
}

func (g *Gateway) lockQuestionList(c *gin.Context) {
	c.JSON(http.StatusOK, contract.OK(dto.QuestionList{List: questionBank}))
}

func (g *Gateway) lockCoverList(c *gin.Context) {
	// 封面图库原版由 TOS 资产下发；TOS 账号随源码丢失，本 rebuild 的封面库为空，客户端降级到
	// 本地默认封面（待验 77）。custom=-1 的删除路由因此是幂等 no-op。
	c.JSON(http.StatusOK, contract.OK(dto.LockImageList{List: []*dto.LockImageRow{}}))
}

func (g *Gateway) lockCoverDel(c *gin.Context) {
	c.JSON(http.StatusOK, contract.OK(nil))
}

func (g *Gateway) lock(c *gin.Context) {
	me := g.claims(c).UserID
	form := body(c)
	friendID := num(form, "friendId")
	if friendID <= 0 {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	if friend, err := g.opts.IM.IsFriend(c.Request.Context(), store.IMID(me), store.IMID(friendID)); err != nil || !friend {
		// 锁一个不是好友的人，answer 与"锁成功"不可区分会给用户假象：403 语义是本工程定义的
		// （待验里对 relation 域统一 200+status=403）。
		c.JSON(http.StatusOK, contract.Fail(contract.StatusForbidden, "仅好友可锁机"))
		return
	}
	state := store.LockState{
		LockTime:    time.Now().Unix(),
		LockDuration: int64(num(form, "lockDuration")),
		LockTitle:   str(form, "lockTitle"),
		LockBackPic: str(form, "lockBackPic"),
		Question:    str(form, "question"),
	}
	g.opts.Store.SetLock(me, friendID, state)
	c.JSON(http.StatusOK, contract.OK(nil))
}

func (g *Gateway) unlock(c *gin.Context) {
	me := g.claims(c).UserID
	form := body(c)
	friendID := num(form, "friendId")
	if friendID <= 0 {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	g.opts.Store.ClearLock(me, friendID)
	c.JSON(http.StatusOK, contract.OK(nil))
}
