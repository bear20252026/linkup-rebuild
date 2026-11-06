package gateway

import (
	"net/http"
	"strings"
	"testing"
	"time"

	"linkup.local/server/internal/contract"
	"linkup.local/server/internal/location"
	"linkup.local/server/internal/store"
)

// report posts one fix through the signed path, the way the device does.
func (h *harness) report(t *testing.T, token string, userID int64, lat, lng float64, at time.Time) envelope {
	t.Helper()
	body := map[string]any{
		"accuracy": 12, "lat": lat, "lng": lng, "locType": 1, "speed": 0,
		"timePeriod": at.Unix(),
		"address": map[string]any{"province": "重庆市", "city": "重庆市", "district": "渝北区",
			"street": "龙山路", "streetNumber": "9号", "poiName": "两江幸福广场"},
	}
	return h.request(t, http.MethodPost, "/api/location/report", "", body, token)
}

func TestLocationReportIsStoredAndAnsweredAsTheClientsBean(t *testing.T) {
	h := newTest(t)
	token, me := h.login(t, "13800001001")

	now := time.Now()
	answered := h.report(t, token, me.ID, 29.6154, 106.5301, now)
	if answered.Status != 200 {
		t.Fatalf("report: %+v", answered)
	}
	saved := decode[location.Point](t, answered.Data)
	if saved.ID == 0 {
		t.Error("the server must assign the id the client puts back on the next report")
	}
	if saved.Lat != 29.6154 || saved.Lng != 106.5301 {
		t.Errorf("coordinates drifted: %+v", saved)
	}
	if saved.TimePeriod != now.Unix() {
		t.Errorf("timePeriod must stay the device's epoch seconds, got %d", saved.TimePeriod)
	}
	if saved.Address == nil || saved.Address.District != "渝北区" || saved.Address.POIName != "两江幸福广场" {
		t.Errorf("the reverse-geocoded parts were lost: %+v", saved.Address)
	}
	// bean/Location's key set is exactly this; a drifted key reads as a zero on the phone.
	raw := string(answered.Data)
	for _, want := range []string{`"accuracy"`, `"address"`, `"id"`, `"lat"`, `"lng"`, `"locType"`, `"speed"`, `"timePeriod"`} {
		if !strings.Contains(raw, want) {
			t.Errorf("report answer is missing %s: %s", want, raw)
		}
	}

	if stored, ok := h.store.LatestFix(me.ID); !ok || stored.ID != saved.ID {
		t.Fatalf("the fix never reached the store: %+v %v", stored, ok)
	}
}

func TestLocationDaysAndTracksReadBackOneDay(t *testing.T) {
	h := newTest(t)
	token, me := h.login(t, "13800001002")

	at := time.Date(2026, 11, 5, 2, 0, 0, 0, time.UTC)
	home := h.report(t, token, me.ID, 29.6154, 106.5301, at)
	if home.Status != 200 {
		t.Fatalf("home fix: %+v", home)
	}
	// 0.3 m away, four minutes later: a stay long enough to count as an arrival point.
	if answered := h.request(t, http.MethodPost, "/api/location/report", "", stayOf(at.Add(4*time.Minute)), token); answered.Status != 200 {
		t.Fatalf("stay fix: %+v", answered)
	}
	// Then 1.5 km away and another four-minute stay at that place.
	if answered := h.request(t, http.MethodPost, "/api/location/report", "", stayAt(at.Add(10*time.Minute), awayCoords()), token); answered.Status != 200 {
		t.Fatalf("moved fix: %+v", answered)
	}
	if answered := h.request(t, http.MethodPost, "/api/location/report", "", stayAt(at.Add(14*time.Minute), awayCoords()), token); answered.Status != 200 {
		t.Fatalf("second stay fix: %+v", answered)
	}
	// A fix on the previous day, to prove the day index and the per-day filter.
	if answered := h.request(t, http.MethodPost, "/api/location/report", "", stayOf(at.Add(-26*time.Hour)), token); answered.Status != 200 {
		t.Fatalf("previous-day fix: %+v", answered)
	}

	days := h.request(t, http.MethodGet, "/api/location/days", "queryId="+idOf(me.ID), nil, token)
	list := decode[[]string](t, days.Data)
	if len(list) != 2 {
		t.Fatalf("expected two days, got %v", list)
	}
	if list[0] != "2026-11-05" || list[1] != "2026-11-04" {
		t.Errorf("days must be newest first: %v", list)
	}

	tracks := h.request(t, http.MethodGet, "/api/location/v2/tracks",
		"queryId="+idOf(me.ID)+"&date=2026-11-05", nil, token)
	if tracks.Status != 200 {
		t.Fatalf("tracks: %+v", tracks)
	}
	day := decode[struct {
		UserID int64 `json:"userId"`
		Points []struct {
			Lat        float64 `json:"lat"`
			Lng        float64 `json:"lng"`
			TimePeriod int64   `json:"timePeriod"`
		} `json:"points"`
		Tracks []struct {
			Type       int    `json:"type"`
			Address    string `json:"address"`
			TimePeriod int64  `json:"timePeriod"`
			Line       *struct {
				Distance float64 `json:"distance"`
				Duration int64   `json:"duration"`
				Type     int     `json:"type"`
			} `json:"line"`
		} `json:"tracks"`
		TotalDistance     float64 `json:"totalDistance"`
		TotalStayTime     int64   `json:"totalStayTime"`
		ArrivalPointCount int     `json:"arrivalPointCount"`
		FriendLocation    *struct {
			Lat         float64 `json:"lat"`
			ArrivalTime int64   `json:"arrivalTime"`
		} `json:"friendLocation"`
		Permission *struct {
			SensLocation bool `json:"sensLocation"`
		} `json:"permission"`
	}](t, tracks.Data)

	if day.UserID != me.ID {
		t.Errorf("userId wrong: %d", day.UserID)
	}
	if len(day.Points) != 4 {
		t.Fatalf("the day's polyline must carry all four fixes, got %d", len(day.Points))
	}
	// metres and seconds are the client's own units (it divides distance by 1000, multiplies stay by 1000).
	if day.TotalDistance < 1400 || day.TotalDistance > 1600 {
		t.Errorf("totalDistance should be ~1500 metres, got %.1f", day.TotalDistance)
	}
	if day.TotalStayTime != 480 {
		t.Errorf("totalStayTime should be 480 seconds, got %d", day.TotalStayTime)
	}
	if day.ArrivalPointCount != 2 {
		t.Errorf("two stays over the threshold should count, got %d", day.ArrivalPointCount)
	}
	if day.FriendLocation == nil || day.FriendLocation.ArrivalTime == 0 {
		t.Errorf("friendLocation must carry the newest fix: %+v", day.FriendLocation)
	}
	if day.Permission == nil {
		t.Error("permission must be present even for self, so the client's null branch is not taken")
	}

	var arrives, leaves int
	movementLine := false
	for _, tr := range day.Tracks {
		switch tr.Type {
		case location.TrackArrive:
			arrives++
			if tr.Line != nil && tr.Line.Type == location.LineMove && tr.Line.Distance > 1400 {
				movementLine = true
			}
		case location.TrackLeave:
			leaves++
		}
	}
	// The day's rows are the two stays: an arrival and a leave each.
	if arrives != 2 || leaves != 2 {
		t.Errorf("expected two arrivals and two leaves, got %d/%d: %+v", arrives, leaves, day.Tracks)
	}
	if !movementLine {
		t.Errorf("the row opening the second stay must carry the 1.5 km movement line: %+v", day.Tracks)
	}

	// A day with nothing in it must still answer 200 with empty lists, not nulls the client would
	// have to special-case.
	quiet := h.request(t, http.MethodGet, "/api/location/v2/tracks", "queryId="+idOf(me.ID)+"&date=2020-01-01", nil, token)
	empty := decode[map[string]any](t, quiet.Data)
	if empty["points"] == nil || empty["tracks"] == nil {
		t.Errorf("an empty day must answer with arrays: %s", quiet.Data)
	}
	if raw := string(quiet.Data); strings.Contains(raw, `"createTime"`) || strings.Contains(raw, `"totalStayMinutes"`) {
		t.Errorf("keys must be the bean's own: %s", raw)
	}
}

func TestLocationReadsRespectTheRelation(t *testing.T) {
	h := newTest(t)
	tokenMe, me := h.login(t, "13800001003")
	tokenOther, other := h.login(t, "13800001004")

	now := time.Now()
	if answered := h.report(t, tokenOther, other.ID, 29.6, 106.5, now); answered.Status != 200 {
		t.Fatalf("other's report: %+v", answered)
	}

	// Not connected yet: reading another device's movement is refused, and the refusal is a body the
	// client can toast (HTTP 200, status != 200).
	refused := h.request(t, http.MethodGet, "/api/location/days", "queryId="+idOf(other.ID), nil, tokenMe)
	if refused.Status == contract.StatusOK {
		t.Fatalf("a stranger read another person's track days: %s", refused.Data)
	}
	if !strings.Contains(refused.Msg, "权限") {
		t.Errorf("the client toasts msg verbatim, got %q", refused.Msg)
	}

	// Self is always allowed.
	if mine := h.request(t, http.MethodGet, "/api/location/days", "queryId="+idOf(me.ID), nil, tokenMe); mine.Status != 200 {
		t.Errorf("own days should always answer: %+v", mine)
	}

	// An established relation in either direction opens it.
	if err := h.store.PutExtra(store.Extra{OwnerID: other.ID, FriendID: me.ID, Relation: 3}); err != nil {
		t.Fatal(err)
	}
	opened := h.request(t, http.MethodGet, "/api/location/v2/tracks",
		"queryId="+idOf(other.ID)+"&date="+location.Day(now.Unix(), h.zone), nil, tokenMe)
	if opened.Status != 200 {
		t.Fatalf("a parent relation should open the child's tracks: %+v %s", opened, opened.Data)
	}

	// A missing or non-numeric queryId is the client's 参数异常, not a crash.
	if bad := h.request(t, http.MethodGet, "/api/location/days", "queryId=abc", nil, tokenMe); bad.Status != contract.StatusParamErr {
		t.Errorf("expected 参数异常, got %+v", bad)
	}
}

func TestLocationFlagsAreHonouredNotHardcoded(t *testing.T) {
	h := newTest(t, func(o *Options) {
		o.LocationSettings = location.Settings{StaySeconds: 600, GapSeconds: 60}
	})
	token, me := h.login(t, "13800001005")
	now := time.Now()

	// A four-minute pause is a stay under the default 120 s but not under the 600 s this run asked for.
	h.report(t, token, me.ID, 29.6154, 106.5301, now)
	h.request(t, http.MethodPost, "/api/location/report", "", stayOf(now.Add(4*time.Minute)), token)

	answered := h.request(t, http.MethodGet, "/api/location/v2/tracks",
		"queryId="+idOf(me.ID)+"&date="+location.Day(now.Unix(), h.zone), nil, token)
	day := decode[struct {
		ArrivalPointCount int   `json:"arrivalPointCount"`
		TotalStayTime     int64 `json:"totalStayTime"`
	}](t, answered.Data)
	if day.ArrivalPointCount != 0 {
		t.Errorf("a 600 s threshold must not count a 240 s pause, got %d", day.ArrivalPointCount)
	}
	if day.TotalStayTime != 240 {
		t.Errorf("stay time is still recorded: %d", day.TotalStayTime)
	}
}

func stayOf(at time.Time) map[string]any {
	return stayAt(at, homeCoords())
}

func homeCoords() [2]float64 { return [2]float64{29.6154, 106.5301} }

// awayCoords is about 1.5 km of longitude away at 29.6°N.
func awayCoords() [2]float64 { return [2]float64{29.6154, 106.5301 + 0.01616} }

// stayAt reports one fix at an exact place; a stay is two of these seconds apart.
func stayAt(at time.Time, latlng [2]float64) map[string]any {
	name := "两江幸福广场"
	if latlng == awayCoords() {
		name = "重庆北站"
	}
	return map[string]any{
		"lat": latlng[0], "lng": latlng[1], "timePeriod": at.Unix(), "accuracy": 12, "locType": 1,
		"address": map[string]any{"city": "重庆市", "district": "渝北区", "poiName": name},
	}
}
