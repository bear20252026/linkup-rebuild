package gateway

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"linkup.local/server/internal/contract"
	"linkup.local/server/internal/dto"
	"linkup.local/server/internal/location"
	"linkup.local/server/internal/store"
)

// locationReport is `api/location/report`. The guarded phone posts its own bean/Location and the
// server answers with the same bean, carrying the id it assigned — that id is what the client puts
// back into the field on the next report.
func (g *Gateway) locationReport(c *gin.Context) {
	var fix location.Point
	if err := json.Unmarshal(rawBody(c), &fix); err != nil {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return
	}
	if fix.TimePeriod == 0 {
		// A fix without a timestamp would be filed under "today" forever and break the day index,
		// so the server stamps it. The client always sends one; this only guards a broken caller.
		fix.TimePeriod = time.Now().Unix()
	}
	me := g.claims(c).UserID
	saved, err := g.opts.Store.AddFix(me, fix)
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(http.StatusInternalServerError, "上报失败"))
		return
	}
	c.JSON(http.StatusOK, contract.OK(saved))
}

// locationDays is `api/location/days?queryId=` — the date picker's contents, newest first.
func (g *Gateway) locationDays(c *gin.Context) {
	target, ok := g.locationTarget(c, "queryId")
	if !ok {
		return
	}
	days := g.opts.Store.DaysFor(target, g.opts.DayZone)
	if days == nil {
		days = []string{}
	}
	c.JSON(http.StatusOK, contract.OK(days))
}

// locationTracks is `api/location/v2/tracks?queryId=&date=`: the day's polyline, the event rows, and
// the three summary numbers. Units are the client's own — metres, seconds, epoch seconds.
func (g *Gateway) locationTracks(c *gin.Context) {
	target, ok := g.locationTarget(c, "queryId")
	if !ok {
		return
	}
	day := c.Query("date")
	if day == "" {
		day = location.Day(time.Now().Unix(), g.opts.DayZone)
	}
	fixes := g.opts.Store.FixesOnDay(target, day, g.opts.DayZone)
	summary := location.Summarise(fixes, g.opts.LocationSettings)

	answer := dto.TrackRecord{
		ArrivalPointCount: summary.ArrivalPointCount,
		Permission:        permission(g.opts.Store.ExtraFor(g.claims(c).UserID, target)),
		Points:            location.Polyline(summary.Points),
		TotalDistance:     summary.TotalDistance,
		TotalStayTime:     summary.TotalStaySeconds,
		Tracks:            summary.Tracks,
		UserID:            target,
	}
	if latest, has := g.opts.Store.LatestFix(target); has {
		answer.FriendLocation = &dto.FriendLocation{
			Address:     latest.Address,
			ArrivalTime: latest.TimePeriod,
			Lat:         latest.Lat,
			Lng:         latest.Lng,
			Speed:       latest.Speed,
		}
	}
	if answer.Tracks == nil {
		answer.Tracks = []location.Track{}
	}
	c.JSON(http.StatusOK, contract.OK(answer))
}

// locationTarget resolves whose tracks are being asked for. Reading another person's movement is
// allowed when the two accounts are connected in either direction — the same relation the friend list
// uses — and always for oneself.
//
// The refusal status is ours, not a recovery: the client only checks `status != 200` and toasts `msg`,
// so the original server's code for this case is not observable.
func (g *Gateway) locationTarget(c *gin.Context, param string) (int64, bool) {
	target, err := parseInt(c.Query(param))
	if err != nil || target <= 0 {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
		return 0, false
	}
	me := g.claims(c).UserID
	if target == me {
		return target, true
	}
	if hasRelation(g.opts.Store, me, target) {
		// 隐私旗标的服务端强制(审计 P1-2):被守护端关掉位置共享(SensLoc=false)后,守护端的
		// 轨迹/天读数即刻拒绝——不再依赖客户端自觉。
		if !g.sensAllows(target, me, sensLoc) {
			c.JSON(http.StatusOK, contract.Fail(contract.StatusForbidden, "对方已关闭位置共享"))
			return 0, false
		}
		return target, true
	}
	g.log.Warn("track read refused", "viewer", me, "target", target)
	c.JSON(http.StatusOK, contract.Fail(contract.StatusForbidden, "没有查看该设备位置的权限"))
	return 0, false
}

func hasRelation(s *store.Store, a, b int64) bool {
	return s.ExtraFor(a, b).Since != 0 || s.ExtraFor(b, a).Since != 0
}
