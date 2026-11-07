package gateway

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"linkup.local/server/internal/contract"
	"linkup.local/server/internal/dto"
	"linkup.local/server/internal/location"
	"linkup.local/server/internal/store"
)

// 设备状态与桌面小组件域：`api/phone/state/report`（每台设备自报电量/屏幕/网络）与
// `api/widget/location`、`api/widget/track`（桌面卡读数）。原版敏感事件域里 widget 只读
// "配对的另一人"——本工程用确认关系（任一方向 confirm）当配对，与 location 域读权限同一规则。

func (g *Gateway) phoneStateReport(c *gin.Context) {
	form := body(c)
	g.opts.Store.SetDeviceState(g.claims(c).UserID, store.DeviceState{
		Battery: int(num(form, "currentBattery")),
		// The client bean types isCharging as Boolean, so the wire form is a JSON bool; the
		// numeric read stays for older callers that sent 0/1.
		IsCharging:      truthy(form, "isCharging"),
		NetworkType:     int(num(form, "networkType")),
		ScreenTime:      num(form, "screenTime"),
		ScreenType:      int(num(form, "screenType")),
		LastOfflineTime: num(form, "lastOfflineTime"),
		LastRecoverTime: num(form, "lastRecoverTime"),
		OfflineTipConf:  num(form, "offlineTipTimeConf"),
		Wifi:            str(form, "wifi"),
		RecordedAt:      time.Now().Unix(),
		Av:              str(form, "av"),
		Client:          str(form, "client"),
		OsVersion:       str(form, "osVersion"),
		Model:           str(form, "model"),
		Brand:           str(form, "brand"),
	})
	c.JSON(http.StatusOK, contract.OK(nil))
}

// truthy reads one form key as a flag across the bool and 0/1 spellings.
func truthy(m map[string]any, key string) bool {
	switch v := m[key].(type) {
	case bool:
		return v
	case float64:
		return v == 1
	}
	return false
}

// friendStateRow assembles the client's FriendState for one user from the device-state ledger.
func (g *Gateway) friendStateRow(userID int64) *dto.FriendState {
	state := g.opts.Store.DeviceStateFor(userID)
	if state.RecordedAt == 0 {
		return nil
	}
	return &dto.FriendState{
		CurrentBattery:     state.Battery,
		IsCharging:         state.IsCharging,
		LastOfflineTime:    state.LastOfflineTime,
		LastRecoverTime:    state.LastRecoverTime,
		NetworkType:        state.NetworkType,
		OfflineTipTimeConf: state.OfflineTipConf,
		ScreenTime:         state.ScreenTime,
		ScreenType:         state.ScreenType,
		ServerTime:         time.Now().Unix(),
		Wifi:               &state.Wifi,
	}
}

// guardPeers answers every confirmed friend of the account, either direction — the pairing rule.
func (g *Gateway) guardPeers(c *gin.Context, me int64) []int64 {
	friends, _, err := g.opts.IM.Friends(c.Request.Context(), store.IMID(me), 1, 100)
	if err != nil {
		return nil
	}
	ids := make([]int64, 0, len(friends))
	for _, f := range friends {
		id := store.ParseID(f.FriendUser.UserID)
		if extra := g.opts.Store.ExtraFor(me, id); extra.Relation > 0 {
			ids = append(ids, id)
		}
	}
	return ids
}

// firstPeerFix is the widget's "对方现在在哪"：第一个有定位的守护对象。
func (g *Gateway) firstPeerFix(c *gin.Context, me int64) *dto.FriendLocation {
	for _, peer := range g.guardPeers(c, me) {
		if fix, ok := g.opts.Store.LatestFix(peer); ok {
			return &dto.FriendLocation{
				Address:     fix.Address,
				ArrivalTime: fix.TimePeriod,
				Lat:         fix.Lat,
				Lng:         fix.Lng,
				Speed:       float32(fix.Speed),
			}
		}
	}
	return nil
}

func (g *Gateway) widgetLocation(c *gin.Context) {
	me := g.claims(c).UserID
	var friendState *dto.FriendState
	for _, peer := range g.guardPeers(c, me) {
		if s := g.friendStateRow(peer); s != nil {
			friendState = s
			break
		}
	}
	c.JSON(http.StatusOK, contract.OK(dto.LocationWidget{
		FriendLocation: g.firstPeerFix(c, me),
		FriendState:    friendState,
		SensLocation:   false,
		ServerTime:     time.Now().Unix(),
		SysLocation:    g.opts.Store.DeviceStateFor(me).RecordedAt > 0,
	}))
}

func (g *Gateway) widgetTrack(c *gin.Context) {
	me := g.claims(c).UserID
	// 今日停留读数走 location 域同一套 Summarise（-stay-seconds/-gap-seconds 旗标），一个真源。
	today := time.Now().In(g.opts.DayZone).Format("2006-01-02")
	summary := location.Summarise(g.opts.Store.FixesOnDay(me, today, g.opts.DayZone), g.opts.LocationSettings)
	widget := dto.TrackWidget{
		FriendLocation: g.firstPeerFix(c, me),
		SensTrack:      false,
		ServerTime:     time.Now().Unix(),
		StayNumToday:   len(summary.Stays),
		SysLocation:    g.opts.Store.DeviceStateFor(me).RecordedAt > 0,
		TotalDistance:  summary.TotalDistance,
		TotalStayTime:  summary.TotalStaySeconds,
		Stays:          make([]*dto.StayRow, 0, len(summary.Stays)),
	}
	for _, stay := range summary.Stays {
		widget.Stays = append(widget.Stays, &dto.StayRow{
			ArriveTime: stay.ArriveTime,
			Duration:   stay.Duration,
			PoiName:    dto.Text(stay.POIName),
		})
	}
	c.JSON(http.StatusOK, contract.OK(widget))
}

// screenshotList is the 实时截屏 screen's friend rows: one per confirmed relation, fed by the same
// device-state ledger as the lock rows (av/client/osVersion 待验 77). 166's own screen reads this.
func (g *Gateway) screenshotList(c *gin.Context) {
	me := g.claims(c).UserID
	friends, _, err := g.opts.IM.Friends(c.Request.Context(), store.IMID(me), 1, 100)
	if !g.handleStatus(c, err, "好友列表") {
		return
	}
	list := make([]*dto.ScreenshotRow, 0, len(friends))
	for _, f := range friends {
		id := store.ParseID(f.FriendUser.UserID)
		device := g.opts.Store.DeviceStateFor(id)
		lock := g.opts.Store.LockFor(me, id)
		list = append(list, &dto.ScreenshotRow{
			Av:                 dto.Text(device.Av),
			Avatar:             dto.Text(f.FriendUser.FaceURL),
			Client:             dto.Text(device.Client),
			Nickname:           dto.Text(f.FriendUser.Nickname),
			OsVersion:          dto.Text(device.OsVersion),
			CreateAt:           device.RecordedAt,
			LastOfflineTime:    device.LastOfflineTime,
			LastRecoverTime:    device.LastRecoverTime,
			LockTime:           lock.LockTime,
			OfflineTipTimeConf: device.OfflineTipConf,
			UserID:             id,
		})
	}
	c.JSON(http.StatusOK, contract.OK(dto.ScreenshotList{List: list}))
}

// screenshotDone is the puller's receipt after a capture round: the served picture URL is ledgered
// so the screen can show what was last taken. The capture itself rides the IM `screenShot` command
// (step 0→3); the gateway never touches pixels.
func (g *Gateway) screenshotDone(c *gin.Context) {
	me := g.claims(c).UserID
	friendID := num(map[string]any{"friendId": c.Query("friendId")}, "friendId")
	if friendID <= 0 {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	pic := c.Query("pic")
	if pic == "" {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	g.opts.Store.RecordScreenshot(me, friendID, pic)
	c.JSON(http.StatusOK, contract.OK(true))
}

