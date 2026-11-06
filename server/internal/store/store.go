// Package store keeps the LinkUp-only state that OpenIM has no concept of: accounts keyed by phone
// and match code, the per-relation extras (relation type, the privacy/lock settings), verification
// codes and the friend quota.
//
// It is deliberately a small JSON file, not a database. The real schema is unrecoverable (the
// production database is not reachable from this project), so inventing tables now would be
// guesswork with a persistence layer on top. When the real backend or its dump shows up, this package
// is the seam that gets replaced — nothing else reads the file.
package store

import (
	"encoding/json"
	"errors"
	"fmt"
	"linkup.local/server/internal/location"
	"math/rand"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// User is a LinkUp account. The OpenIM userID is the decimal id, so no mapping table is needed.
//
// There is no separate "match code": AddFriendActivity shows `String.valueOf(user.userId)` chunked in
// fours as 匹配码 (1.6.6 `AddFriendActivity.java:862-864`) and accepts a typed or scanned input only at
// exactly 8 characters (`AddFriendViewModel.MATCH_CODE_LENGTH`) before calling `pre_confirm?code=`.
// So the id *is* the code, and ids must therefore be 8 digits — see firstUserID.
type User struct {
	ID         int64  `json:"id"`
	Phone      string `json:"phone"`
	UnionID    string `json:"unionId"`
	Platform   int    `json:"platform"`
	Nickname   string `json:"nickname"`
	Avatar     string `json:"avatar"`
	Sex        int    `json:"sex"`
	Stage      int    `json:"stage"`
	Token      string `json:"-"`
	IsRegister bool   `json:"isRegister"`
	VIPSubType int    `json:"vipSubType"`
	VIPExpire  int64  `json:"vipExpireTime"`
	SharedVip  int    `json:"sharedVipSubType"`
	MainUserID int64  `json:"mainUser"`
	GetuiCid   string `json:"getuiCid"`
	PushToken  string `json:"pushToken"`
}

// IsVip mirrors the client's own rule: own subscription first, then any shared grant.
func (u User) IsVip() bool {
	return u.VIPSubType > 0 || u.SharedVip > 0
}

// Extra is the per-direction relation state the client renders but OpenIM cannot store: which
// relationship was recorded and what the two users agreed to share.
type Extra struct {
	OwnerID  int64  `json:"ownerID"`
	FriendID int64  `json:"friendID"`
	Relation int    `json:"relation"`
	SensAll  bool   `json:"sensAll"`
	SensLoc  bool   `json:"sensLocation"`
	SensCall bool   `json:"sensCall"`
	Remark   string `json:"remark"`
	Since    int64  `json:"since"`
}

// fix is one stored position: the wire bean (bean/Location, which has no owner field) plus the
// account it was reported by.
type fix struct {
	UserID int64 `json:"userId"`
	location.Point
}

// Apply is one pending request, as the sender recorded it. The authoritative list stays in OpenIM;
// this is only the LinkUp-side payload that travels with the request: which relationship the sender
// picked and what they were willing to share. The client's request row has no message field, so none
// is stored here either — `api/relation/apply` only ever carries code, relation, sensSetting.
type Apply struct {
	FromID   int64 `json:"fromID"`
	ToID     int64 `json:"toID"`
	Relation int   `json:"relation"`
	SensAll  bool  `json:"sensAll"`
	SensLoc  bool  `json:"sensLocation"`
	SensCall bool  `json:"sensCall"`
	At       int64 `json:"at"`
}

// Code is a verification code. Nothing here ever talks to an SMS provider: see DevCode in the gateway.
type Code struct {
	Phone     string    `json:"phone"`
	Value     string    `json:"value"`
	IssuedAt  time.Time `json:"issuedAt"`
	ExpiresAt time.Time `json:"expiresAt"`
	Attempts  int       `json:"attempts"`
}

// maxCodeAttempts bounds how often one code may be guessed before it is burned. The number is an
// operational choice — the client only reports "验证码错误" and re-enables the button, so how many tries
// the original allowed is not observable from the APK.
const maxCodeAttempts = 5

// Data is the on-disk shape.
type Data struct {
	NextID    int64            `json:"nextID"`
	NextFix   int64            `json:"nextFix"`
	Users     []User           `json:"users"`
	Extras    map[string]Extra `json:"extras"`
	Applies   map[string]Apply `json:"applies"`
	Codes     map[string]Code  `json:"codes"`
	Locations []fix            `json:"locations"`
}

// Store guards Data and persists it on every write.
type Store struct {
	path string
	mu   sync.Mutex
	data Data
}

// firstUserID is 8 digits because that is the length the add-friend screen insists a match code has
// (`MATCH_CODE_LENGTH`), and the match code is the user id: an id of 10001 would be refused with
// "请输入正确的匹配码" before it ever reached the server.
const firstUserID = 10_000_001

// ErrNotFound is what lookups return for a missing account.
var ErrNotFound = errors.New("linkup/store: not found")

// Open loads (or creates) the file. An empty path keeps everything in memory, which is what the
// tests do.
func Open(path string) (*Store, error) {
	s := &Store{path: path, data: Data{NextID: firstUserID, NextFix: 1, Extras: map[string]Extra{}, Applies: map[string]Apply{}, Codes: map[string]Code{}}}
	if path == "" {
		return s, nil
	}
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, fmt.Errorf("linkup/store: read %s: %w", path, err)
	}
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &s.data); err != nil {
			return nil, fmt.Errorf("linkup/store: parse %s: %w", path, err)
		}
	}
	if s.data.Extras == nil {
		s.data.Extras = map[string]Extra{}
	}
	if s.data.Applies == nil {
		s.data.Applies = map[string]Apply{}
	}
	if s.data.Codes == nil {
		s.data.Codes = map[string]Code{}
	}
	if s.data.NextID < firstUserID {
		s.data.NextID = firstUserID
	}
	if s.data.NextFix == 0 {
		s.data.NextFix = 1
	}
	return s, nil
}

func (s *Store) save() error {
	if s.path == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o755); err != nil {
		return err
	}
	raw, err := json.MarshalIndent(&s.data, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

// CreateAccount assigns the next 8-digit id. That id doubles as the account's match code, which is
// why the sequence starts at 10000001 rather than at 1.
func (s *Store) CreateAccount(phone string) (*User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	phone = strings.TrimSpace(phone)
	for i := range s.data.Users {
		if s.data.Users[i].Phone == phone && phone != "" {
			copied := s.data.Users[i]
			return &copied, nil
		}
	}
	u := s.createLocked(phone, "", 0)
	if err := s.save(); err != nil {
		return nil, err
	}
	copied := *u
	return &copied, nil
}

// ThirdAccount is `api/user/login`: a WeChat or QQ identity, plus the phone the user may have typed to
// bind it. The union id is the lookup key, so the same identity always returns to the same account and
// a late phone just attaches to it. When only the phone is known to us, that account adopts the union
// id if it has none — one person cannot end up with two accounts because they used two entry points.
func (s *Store) ThirdAccount(platform int, unionID, phone string) (*User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	unionID, phone = strings.TrimSpace(unionID), strings.TrimSpace(phone)

	var byUnion, byPhone *User
	for i := range s.data.Users {
		u := &s.data.Users[i]
		if unionID != "" && u.UnionID == unionID {
			byUnion = u
		}
		if phone != "" && u.Phone == phone {
			byPhone = u
		}
	}
	switch {
	case byUnion != nil:
		if phone != "" && byUnion.Phone == "" {
			byUnion.Phone = phone
		}
	case byPhone != nil:
		if unionID != "" && byPhone.UnionID == "" {
			byPhone.UnionID, byPhone.Platform = unionID, platform
		}
		byUnion = byPhone
	default:
		byUnion = s.createLocked(phone, unionID, platform)
	}
	if err := s.save(); err != nil {
		return nil, err
	}
	copied := *byUnion
	return &copied, nil
}

// createLocked appends a new account. The caller holds the lock.
func (s *Store) createLocked(phone, unionID string, platform int) *User {
	u := User{
		ID:         s.data.NextID,
		Phone:      phone,
		UnionID:    unionID,
		Platform:   platform,
		Nickname:   fmt.Sprintf("守护用户%04d", s.data.NextID%10000),
		MainUserID: s.data.NextID,
		IsRegister: true,
	}
	s.data.NextID++
	s.data.Users = append(s.data.Users, u)
	return &s.data.Users[len(s.data.Users)-1]
}

// ByID is both the session lookup and the match-code lookup: `pre_confirm?code=` and the apply
// body's `code` carry the user id, because that is what the client displays and types.
func (s *Store) ByID(id int64) (User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, u := range s.data.Users {
		if u.ID == id {
			return u, nil
		}
	}
	return User{}, ErrNotFound
}

// ByPhone answers with a copy so callers cannot mutate the store by accident.
func (s *Store) ByPhone(phone string) (User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	phone = strings.TrimSpace(phone)
	for _, u := range s.data.Users {
		if u.Phone == phone {
			return u, nil
		}
	}
	return User{}, ErrNotFound
}

// Profile is what `api/user/update/info` can change. The client sends exactly these four keys —
// avatar, nickname, sex, stage — and each one is a nullable Kotlin Integer/String, so absence has to
// be distinguishable from a deliberate zero: `stage` 0 means "start the profile wizard again".
type Profile struct {
	Nickname *string
	Avatar   *string
	Sex      *int
	Stage    *int
}

// UpdateProfile applies only the fields the call actually carried.
func (s *Store) UpdateProfile(id int64, p Profile) (User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.data.Users {
		if s.data.Users[i].ID != id {
			continue
		}
		if v := p.Nickname; v != nil && *v != "" {
			s.data.Users[i].Nickname = *v
		}
		if v := p.Avatar; v != nil && *v != "" {
			s.data.Users[i].Avatar = *v
		}
		if v := p.Sex; v != nil {
			s.data.Users[i].Sex = *v
		}
		if v := p.Stage; v != nil {
			s.data.Users[i].Stage = *v
		}
		updated := s.data.Users[i]
		return updated, s.save()
	}
	return User{}, ErrNotFound
}

// RecordDevice stores the push identifiers, which is all `report/getui_cid` and
// `update/push/token` ever did.
func (s *Store) RecordDevice(id int64, getuiCid, pushToken string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.data.Users {
		if s.data.Users[i].ID != id {
			continue
		}
		if getuiCid != "" {
			s.data.Users[i].GetuiCid = getuiCid
		}
		if pushToken != "" {
			s.data.Users[i].PushToken = pushToken
		}
		return s.save()
	}
	return ErrNotFound
}

// PutExtra writes the relation extras for one direction.
func (s *Store) PutExtra(e Extra) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	// Seconds, not milliseconds: the client renders this as `Date(createAt * 1000L)`
	// (FriendInfoActivity.kt:103, FriendListVAdapter.kt:80) and OpenIM's own createTime is seconds too.
	e.Since = time.Now().Unix()
	s.data.Extras[extraKey(e.OwnerID, e.FriendID)] = e
	return s.save()
}

// ExtraFor reads one direction, or a zero-value Extra when nothing was recorded (the client's own
// default then applies).
func (s *Store) ExtraFor(owner, friend int64) Extra {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.data.Extras[extraKey(owner, friend)]
}

// ForgetPair drops both directions, which is what a deletion must do.
func (s *Store) ForgetPair(a, b int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.data.Extras, extraKey(a, b))
	delete(s.data.Extras, extraKey(b, a))
	delete(s.data.Applies, extraKey(a, b))
	delete(s.data.Applies, extraKey(b, a))
	return s.save()
}

// RememberApply keeps the sender's relation choice until the answer arrives.
func (s *Store) RememberApply(a Apply) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	a.At = time.Now().UnixMilli()
	s.data.Applies[extraKey(a.FromID, a.ToID)] = a
	return s.save()
}

// ApplyFor returns what the sender recorded for a pending request.
func (s *Store) ApplyFor(from, to int64) (Apply, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	a, ok := s.data.Applies[extraKey(from, to)]
	return a, ok
}

func (s *Store) ForgetApply(from, to int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.data.Applies, extraKey(from, to))
	return s.save()
}

// IssueCode replaces any earlier code for the phone and honours the ttl. The value is four digits
// because that is the length the client's SMS dialog auto-submits at.
func (s *Store) IssueCode(phone string, ttl time.Duration) (Code, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if ttl <= 0 {
		ttl = 5 * time.Minute
	}
	now := time.Now()
	c := Code{
		Phone:     phone,
		Value:     fmt.Sprintf("%04d", rand.Intn(10000)),
		IssuedAt:  now,
		ExpiresAt: now.Add(ttl),
	}
	s.data.Codes[phone] = c
	return c, s.save()
}

// CheckCode consumes a code on success, mirroring the "one code, one login" rule. An expired code is
// refused and dropped, so a stale row cannot be revived by guessing.
func (s *Store) CheckCode(phone, value string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	c, ok := s.data.Codes[phone]
	if !ok {
		return false
	}
	if time.Now().After(c.ExpiresAt) {
		delete(s.data.Codes, phone)
		return false
	}
	if c.Attempts >= maxCodeAttempts {
		return false
	}
	if !strings.EqualFold(strings.TrimSpace(c.Value), strings.TrimSpace(value)) {
		c.Attempts++
		s.data.Codes[phone] = c
		return false
	}
	delete(s.data.Codes, phone)
	return true
}

// FriendCount counts the recorded relations, which is what the quota is compared against.
func (s *Store) FriendCount(owner int64) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	n := 0
	for k := range s.data.Extras {
		if strings.HasPrefix(k, fmt.Sprintf("%d|", owner)) {
			n++
		}
	}
	return n
}

// Accounts lists everything, ordered by id; used by the boot log and the smoke script.
func (s *Store) Accounts() []User {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := append([]User(nil), s.data.Users...)
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out
}

func extraKey(a, b int64) string { return fmt.Sprintf("%d|%d", a, b) }

// IMID is the string form OpenIM identities use.
func IMID(id int64) string { return fmt.Sprintf("%d", id) }

// ParseID is the inverse, tolerant of the empty string a missing field produces.
func ParseID(text string) int64 {
	var id int64
	for _, r := range text {
		if r < '0' || r > '9' {
			return 0
		}
		id = id*10 + int64(r-'0')
	}
	return id
}

// AddFix stores one reported position and returns it with the server-assigned id, which is what
// `api/location/report` answers with.
func (s *Store) AddFix(userID int64, p location.Point) (location.Point, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	p.ID = s.data.NextFix
	s.data.NextFix++
	s.data.Locations = append(s.data.Locations, fix{UserID: userID, Point: p})
	if err := s.save(); err != nil {
		return location.Point{}, err
	}
	return p, nil
}

// Fixes returns one account's whole history in time order.
func (s *Store) Fixes(userID int64) []location.Point {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]location.Point, 0, 32)
	for _, row := range s.data.Locations {
		if row.UserID == userID {
			out = append(out, row.Point)
		}
	}
	location.Sort(out)
	return out
}

// LatestFix is the position the track screen and the cards show as "现在".
func (s *Store) LatestFix(userID int64) (location.Point, bool) {
	fixes := s.Fixes(userID)
	for i := len(fixes) - 1; i >= 0; i-- {
		if fixes[i].Valid() {
			return fixes[i], true
		}
	}
	return location.Point{}, false
}

// DaysFor lists the days that have any data, newest first, counted in the server's timezone:
// `api/location/days` answers strings and `api/location/v2/tracks` takes one back, so the two must
// agree on which day a fix belongs to.
func (s *Store) DaysFor(userID int64, in *time.Location) []string {
	seen := map[string]bool{}
	var out []string
	for _, p := range s.Fixes(userID) {
		day := location.Day(p.TimePeriod, in)
		if seen[day] {
			continue
		}
		seen[day] = true
		out = append(out, day)
	}
	sort.Sort(sort.Reverse(sort.StringSlice(out)))
	return out
}

// FixesOnDay is the slice one day's summary is computed from.
func (s *Store) FixesOnDay(userID int64, day string, in *time.Location) []location.Point {
	var out []location.Point
	for _, p := range s.Fixes(userID) {
		if location.Day(p.TimePeriod, in) == day {
			out = append(out, p)
		}
	}
	return out
}
