package location

import (
	"math"
	"strings"
	"testing"
	"time"
)

func fix(lat, lng float64, at int64) Point {
	return Point{Lat: lat, Lng: lng, TimePeriod: at, Accuracy: 10}
}

// aboutMeters moves a longitude by roughly the given metres at Beijing's latitude.
func shiftLng(p Point, meters float64) Point {
	deg := meters / (111320 * math.Cos(p.Lat*math.Pi/180))
	q := p
	q.Lng += deg
	return q
}

func TestDistanceIsHaversineNotDegrees(t *testing.T) {
	a := fix(39.9042, 116.4074, 0)
	b := fix(39.9142, 116.4074, 0)
	got := DistanceMeters(a, b)
	// 0.01° of latitude is about 1112 m. A degree-based implementation would answer 0.01.
	if got < 1100 || got > 1125 {
		t.Fatalf("0.01° latitude should be ~1112 m, got %.1f", got)
	}
	if d := DistanceMeters(a, a); d != 0 {
		t.Fatalf("a point is 0 m from itself, got %f", d)
	}
}

func TestSummariseSplitsStayFromMovement(t *testing.T) {
	home := fix(39.9042, 116.4074, 1_700_000_000)
	atHome2 := shiftLng(home, 0.4)
	atHome2.TimePeriod = home.TimePeriod + 300
	away := shiftLng(home, 2000)
	away.TimePeriod = atHome2.TimePeriod + 600
	// `away` then sits there for four minutes, which is over the default stay threshold.
	awayLater := away
	awayLater.TimePeriod = away.TimePeriod + 240

	s := Summarise([]Point{awayLater, atHome2, away, home}, DefaultSettings())

	if len(s.Points) != 4 {
		t.Fatalf("every usable fix belongs on the polyline, got %d", len(s.Points))
	}
	if len(s.Stays) != 2 {
		t.Fatalf("two places were sat at, got %d stays: %+v", len(s.Stays), s.Stays)
	}
	if s.TotalStaySeconds != 540 {
		t.Fatalf("stay seconds are summed in seconds (the client multiplies by 1000), got %d", s.TotalStaySeconds)
	}
	if s.TotalDistance < 1900 || s.TotalDistance > 2100 {
		t.Fatalf("total distance must be metres (the client divides by 1000), got %.1f", s.TotalDistance)
	}
	// Both stays lasted over the 120 s default, so both count as arrival points.
	if s.ArrivalPointCount != 2 {
		t.Errorf("arrival point count wrong: %d", s.ArrivalPointCount)
	}

	var arrives, leaves int
	for _, tr := range s.Tracks {
		switch tr.Type {
		case TrackArrive:
			arrives++
		case TrackLeave:
			leaves++
		}
	}
	if arrives != 2 || leaves != 2 {
		t.Errorf("each stay should be an arrive row plus a leave row, got %d/%d: %+v", arrives, leaves, s.Tracks)
	}
}

func TestLineTypesUseTheClientsOwnThresholds(t *testing.T) {
	settings := DefaultSettings()

	t.Run("超过 gap 的沉默是「没有新定位」", func(t *testing.T) {
		a := fix(39.9042, 116.4074, 1_700_000_000)
		b := shiftLng(a, 500)
		b.TimePeriod = a.TimePeriod + settings.GapSeconds + 600
		bLater := shiftLng(b, 0.5)
		bLater.TimePeriod = b.TimePeriod + 300
		s := Summarise([]Point{bLater, b, a}, settings)
		var found bool
		for _, tr := range s.Tracks {
			if tr.Line != nil && tr.Line.Type == LineNoNewLocation && tr.Line.Duration > settings.GapSeconds {
				found = true
			}
		}
		if !found {
			t.Fatalf("no LineNoNewLocation row was emitted: %+v", s.Tracks)
		}
	})

	t.Run("普通移动是 type 2", func(t *testing.T) {
		a := fix(39.9042, 116.4074, 1_700_000_000)
		aLater := shiftLng(a, 0.3)
		aLater.TimePeriod = a.TimePeriod + 60
		b := shiftLng(a, 800)
		b.TimePeriod = aLater.TimePeriod + 120
		bLater := shiftLng(b, 0.3)
		bLater.TimePeriod = b.TimePeriod + 300
		s := Summarise([]Point{aLater, a, b, bLater}, settings)
		var typed bool
		for _, tr := range s.Tracks {
			if tr.Line != nil && tr.Line.Type == LineMove && tr.Line.Distance > 700 && tr.Line.Duration < settings.GapSeconds {
				typed = true
			}
		}
		if !typed {
			t.Fatalf("the row after movement should carry a LineMove of ~800 m: %+v", s.Tracks)
		}
	})
}

func TestUnusableFixStillShowsAndStaysOffThePolyline(t *testing.T) {
	good := fix(39.9042, 116.4074, 1_700_000_000)
	goodLater := shiftLng(good, 0.5)
	goodLater.TimePeriod = good.TimePeriod + 300
	bad := fix(0, 0, goodLater.TimePeriod+120)

	s := Summarise([]Point{good, goodLater, bad}, DefaultSettings())
	if len(s.Points) != 2 {
		t.Fatalf("a 0/0 report is not a position: %d points", len(s.Points))
	}
	var noLoc int
	for _, tr := range s.Tracks {
		if tr.Line != nil && tr.Line.Type == LineNoLocation {
			if tr.LostType != 0 {
				t.Errorf("the client only renders a row when lostType == 0, got %d", tr.LostType)
			}
			noLoc++
		}
	}
	if noLoc != 1 {
		t.Fatalf("the unusable fix should produce exactly one 无法获取定位 row, got %d: %+v", noLoc, s.Tracks)
	}
}

func TestShortStayIsNotAnArrivalPoint(t *testing.T) {
	a := fix(39.9042, 116.4074, 1_700_000_000)
	b := shiftLng(a, 0.3)
	b.TimePeriod = a.TimePeriod + 60 // 60 s < the 120 s default
	s := Summarise([]Point{a, b}, DefaultSettings())
	if s.ArrivalPointCount != 0 {
		t.Errorf("a one-minute pause should not count as an arrival point, got %d", s.ArrivalPointCount)
	}
	if s.TotalStaySeconds != 60 {
		t.Errorf("it is still stay time: %d", s.TotalStaySeconds)
	}
	if len(s.Stays) != 1 {
		t.Errorf("and still one stay: %d", len(s.Stays))
	}
}

func TestDayOfOnlyMovementStillHasARow(t *testing.T) {
	var day []Point
	for i := 0; i < 6; i++ {
		p := shiftLng(fix(39.9042, 116.4074, 0), float64(i)*900)
		p.TimePeriod = 1_700_000_000 + int64(i)*120
		day = append(day, p)
	}
	s := Summarise(day, DefaultSettings())
	if len(s.Tracks) != 1 {
		t.Fatalf("the client's summary would sit over an empty list, got %d rows", len(s.Tracks))
	}
	if s.Tracks[0].Type != TrackArrive {
		t.Errorf("the first row should be an arrival, got %d", s.Tracks[0].Type)
	}
	if s.TotalDistance < 4000 {
		t.Errorf("5 gaps of 900 m should total ~4.5 km, got %.0f", s.TotalDistance)
	}
}

func TestAddressFallsBackThroughTheParts(t *testing.T) {
	p := fix(39.9042, 116.4074, 0)
	if placeOf(p) != "" {
		t.Error("no address should render as empty, not a placeholder")
	}
	p.Address = &Address{Province: "北京市", City: "北京市", District: "朝阳区", Street: "望京街", StreetNumber: "10号"}
	if got := placeOf(p); !strings.Contains(got, "朝阳区望京街10号") {
		t.Errorf("the parts should compose in order without repeating the city, got %q", got)
	}
	p.Address = &Address{POIName: "望京SOHO"}
	if got := placeOf(p); got != "望京SOHO" {
		t.Errorf("with no street parts the POI name is the place, got %q", got)
	}
}

func TestDayIsRenderedInTheServersTimezone(t *testing.T) {
	shanghai, err := time.LoadLocation("Asia/Shanghai")
	if err != nil {
		t.Skipf("no tzdata: %v", err)
	}
	utc := time.UTC
	// 2026-11-05 00:30 in Shanghai is still 2026-11-04 in UTC: which day a fix belongs to is a
	// server-side decision, and the client just echoes the string back as `date`.
	at := time.Date(2026, 11, 05, 0, 30, 0, 0, shanghai).Unix()
	if got := Day(at, shanghai); got != "2026-11-05" {
		t.Errorf("Shanghai day: %s", got)
	}
	if got := Day(at, utc); got != "2026-11-04" {
		t.Errorf("UTC day: %s", got)
	}
}

func TestSortIsStableByTime(t *testing.T) {
	ps := []Point{fix(1, 1, 30), fix(1, 1, 10), fix(1, 1, 20)}
	Sort(ps)
	for i := 1; i < len(ps); i++ {
		if ps[i-1].TimePeriod > ps[i].TimePeriod {
			t.Fatalf("not sorted: %+v", ps)
		}
	}
}
