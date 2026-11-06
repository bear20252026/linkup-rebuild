// Package location turns the fixes a guarded phone reports into the shapes the track screen reads.
//
// Everything here is derived from what the shipped client does with the answer, because the original
// server is gone:
//
//   - units: `totalDistance` is metres (TrackRecordFragment.java:181 divides it by 1000 and appends
//     "km"), `totalStayTime` is seconds (line 164 multiplies by 1000 before formatting), and
//     `timePeriod` is epoch seconds (line 280 does `Date(timePeriod * 1000)` then formats "HH:mm").
//   - `line.type` is chosen by the client's own branch labels (TrackRecordFragment.java:236-300):
//     1 renders R.string.less_than_1m, 2 renders R.string.less_than_200m, 3 the "无法获取定位" icon,
//     4 the "没有新定位" warning. So a segment's type is a distance/availability judgement the
//     server makes, and the first two thresholds are exactly those two distances.
//   - `track.type`: 2 shows the stay icon plus R.string.arrive, 3 the blue point plus R.string.leave —
//     an arrival, and the departure that ends the stay.
//
// Two numbers are not observable, because the original server computed them and never told the
// client: how long a stay must last to count as an arrival point, and how long a silence must last
// before a gap becomes "no new location". Both are Settings, not constants.
package location

import (
	"math"
	"sort"
	"strings"
	"time"
)

// Address is the reverse-geocoded place a fix carries. bean/Address declares exactly these six keys —
// there is no pre-composed full-address field, which is why placeOf builds one.
type Address struct {
	Province     string `json:"province,omitempty"`
	City         string `json:"city,omitempty"`
	District     string `json:"district,omitempty"`
	Street       string `json:"street,omitempty"`
	StreetNumber string `json:"streetNumber,omitempty"`
	POIName      string `json:"poiName,omitempty"`
}

// Point is one reported fix. It doubles as `bean/Location`, which is what `api/location/report`
// posts as its body and answers with, so one struct serves both directions.
type Point struct {
	Accuracy   float32  `json:"accuracy"`
	Address    *Address `json:"address,omitempty"`
	ID         int64    `json:"id"`
	Lat        float64  `json:"lat"`
	Lng        float64  `json:"lng"`
	LocType    int      `json:"locType"`
	Speed      float32  `json:"speed"`
	TimePeriod int64    `json:"timePeriod"`
	Type       int      `json:"type"`
}

// Stay is one place the phone sat, with the flat keys bean/StayInfo declares.
type Stay struct {
	ArriveTime   int64  `json:"arriveTime"`
	City         string `json:"city,omitempty"`
	District     string `json:"district,omitempty"`
	Duration     int64  `json:"duration"`
	POIName      string `json:"poiName,omitempty"`
	Province     string `json:"province,omitempty"`
	Street       string `json:"street,omitempty"`
	StreetNumber string `json:"streetNumber,omitempty"`
}

// Line is the movement between two consecutive rows of the day.
type Line struct {
	Distance float64 `json:"distance"`
	Duration int64   `json:"duration"`
	Type     int     `json:"type"`
}

// Track is one event row in the day's list: where the phone arrived or left.
type Track struct {
	Address    string  `json:"address,omitempty"`
	Lat        float64 `json:"lat"`
	Line       *Line   `json:"line,omitempty"`
	Lng        float64 `json:"lng"`
	LostType   int     `json:"lostType"`
	TimePeriod int64   `json:"timePeriod"`
	Type       int     `json:"type"`
}

// Line types, in the client's numbering.
const (
	LineStay          = 1
	LineMove          = 2
	LineNoLocation    = 3
	LineNoNewLocation = 4
)

// Track event types, in the client's numbering.
const (
	TrackArrive = 2
	TrackLeave  = 3
)

// StayRadiusMeters is the client's own "less_than_1m" label: a segment no longer than this is not
// movement.
const StayRadiusMeters = 1.0

// DayLayout is how `api/location/days` answers and how the `date` parameter arrives.
const DayLayout = "2006-01-02"

// Settings carries the two numbers the APK cannot tell us about.
type Settings struct {
	// StaySeconds: how long a stay must last before it counts as an arrival point.
	StaySeconds int64
	// GapSeconds: how long a silence must be before the next row's line is "no new location".
	GapSeconds int64
}

// DefaultSettings is a placeholder, not a recovery: both numbers were server-side decisions.
func DefaultSettings() Settings { return Settings{StaySeconds: 120, GapSeconds: 1800} }

// Summary is one day's answer for `api/location/v2/tracks`.
type Summary struct {
	Points            []Point
	Tracks            []Track
	Stays             []Stay
	TotalDistance     float64
	TotalStaySeconds  int64
	ArrivalPointCount int
}

// Valid rejects the coordinates a phone reports when it has no fix: 0/0, and anything outside the
// range a map SDK can encode.
func (p Point) Valid() bool {
	if p.Lat == 0 && p.Lng == 0 {
		return false
	}
	return math.Abs(p.Lat) <= 90 && math.Abs(p.Lng) <= 180
}

// DistanceMeters is the haversine distance between two fixes.
func DistanceMeters(a, b Point) float64 {
	const earthRadiusMeters = 6371000.0
	lat1, lat2 := a.Lat*math.Pi/180, b.Lat*math.Pi/180
	dLat := (b.Lat - a.Lat) * math.Pi / 180
	dLng := (b.Lng - a.Lng) * math.Pi / 180
	h := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(lat1)*math.Cos(lat2)*math.Sin(dLng/2)*math.Sin(dLng/2)
	return 2 * earthRadiusMeters * math.Atan2(math.Sqrt(h), math.Sqrt(1-h))
}

// Sort orders fixes by time; the store appends rather than sorts.
func Sort(ps []Point) {
	sort.SliceStable(ps, func(i, j int) bool { return ps[i].TimePeriod < ps[j].TimePeriod })
}

// Summarise walks one day's fixes and produces the client's answer.
//
// Rows are events, not fixes: a stay contributes an arrival at its start and a leave at its end, and
// a fix the device could not locate contributes one row whose line says so. Fixes between events are
// movement and only reach the polyline (`points`), which is what the client draws on the map.
func Summarise(points []Point, s Settings) Summary {
	fixes := append([]Point(nil), points...)
	Sort(fixes)

	out := Summary{Points: []Point{}, Tracks: []Track{}, Stays: []Stay{}}
	var usable []Point
	for _, p := range fixes {
		if p.Valid() {
			usable = append(usable, p)
			out.Points = append(out.Points, p)
			continue
		}
		// bean/TrackRecordBean.Track only renders a row when lostType == 0, and what a non-zero
		// value means is not visible in the client; the unusable fix is therefore recorded with a
		// line of type 3 and lostType left at 0 so the row still shows.
		out.Tracks = append(out.Tracks, Track{
			Lat: 0, Lng: 0,
			Line:       &Line{Type: LineNoLocation},
			TimePeriod: p.TimePeriod,
			Type:       TrackLeave,
		})
	}

	var (
		prev        *Point
		pending     *Line // the line the next emitted row should carry
		stayMinimum = s.StaySeconds
	)
	i := 0
	for i < len(usable) {
		cur := usable[i]
		if prev != nil {
			seconds := cur.TimePeriod - prev.TimePeriod
			if seconds < 0 {
				seconds = 0
			}
			kind := LineMove
			if seconds > s.GapSeconds {
				kind = LineNoNewLocation
			}
			pending = &Line{Distance: DistanceMeters(*prev, cur), Duration: seconds, Type: kind}
			out.TotalDistance += DistanceMeters(*prev, cur)
		}

		// Grow a stay from this fix: a maximal run within StayRadiusMeters of it.
		end := i
		for end+1 < len(usable) && DistanceMeters(cur, usable[end+1]) <= StayRadiusMeters {
			end++
		}
		if end == i {
			// Plain movement: it belongs to the polyline, not to the row list.
			used := cur
			prev, i = &used, i+1
			continue
		}

		last := usable[end]
		stayed := last.TimePeriod - cur.TimePeriod
		if stayed < 0 {
			stayed = 0
		}
		out.TotalStaySeconds += stayed
		if stayed >= stayMinimum {
			out.ArrivalPointCount++
		}
		out.Tracks = append(out.Tracks,
			Track{Address: placeOf(cur), Lat: cur.Lat, Line: pending, Lng: cur.Lng,
				TimePeriod: cur.TimePeriod, Type: TrackArrive},
			Track{Address: placeOf(last), Lat: last.Lat, Lng: last.Lng,
				TimePeriod: last.TimePeriod, Type: TrackLeave},
		)
		pending = nil
		out.Stays = append(out.Stays, stayOf(cur, stayed))

		used := last
		prev, i = &used, end+1
	}

	// A day that never stays still has no rows at all; the client's summary cards would then show
	// zeros over an empty list, so the first fix is reported as an arrival.
	if len(out.Tracks) == 0 && len(usable) > 0 {
		out.Tracks = append(out.Tracks, Track{
			Address: placeOf(usable[0]), Lat: usable[0].Lat, Lng: usable[0].Lng,
			Line: &Line{Type: LineMove}, TimePeriod: usable[0].TimePeriod, Type: TrackArrive,
		})
	}
	return out
}

func stayOf(p Point, seconds int64) Stay {
	stay := Stay{ArriveTime: p.TimePeriod, Duration: seconds}
	if p.Address != nil {
		stay.City = p.Address.City
		stay.District = p.Address.District
		stay.POIName = p.Address.POIName
		stay.Province = p.Address.Province
		stay.Street = p.Address.Street
		stay.StreetNumber = p.Address.StreetNumber
	}
	return stay
}

// placeOf composes the text the day's rows show. bean/Address has no full-address key, so the
// original server composed one too — the exact order it used is not observable from the client,
// which only ever receives the string.
func placeOf(p Point) string {
	if p.Address == nil {
		return ""
	}
	var b strings.Builder
	for _, part := range []string{p.Address.Province, p.Address.City, p.Address.District, p.Address.Street, p.Address.StreetNumber} {
		if part != "" && !strings.Contains(b.String(), part) {
			b.WriteString(part)
		}
	}
	if b.Len() > 0 {
		return b.String()
	}
	return p.Address.POIName
}

// Day renders epoch seconds as the `date` string the client sends back, in the timezone the server
// counts days in.
func Day(epochSeconds int64, in *time.Location) string {
	return time.Unix(epochSeconds, 0).In(in).Format(DayLayout)
}

// PolylinePoint is `TrackRecordBean.Point` — the map's polyline vertex. It is NOT bean/Location:
// the client's own nested bean carries only these four keys, and nothing in the shipped code reads
// `type`, so it stays 0 until someone can say what it meant.
type PolylinePoint struct {
	Lat        float64 `json:"lat"`
	Lng        float64 `json:"lng"`
	TimePeriod int64   `json:"timePeriod"`
	Type       int     `json:"type"`
}

// Polyline projects stored fixes onto the polyline bean.
func Polyline(points []Point) []PolylinePoint {
	out := make([]PolylinePoint, 0, len(points))
	for _, p := range points {
		out = append(out, PolylinePoint{Lat: p.Lat, Lng: p.Lng, TimePeriod: p.TimePeriod})
	}
	return out
}
