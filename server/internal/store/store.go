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
	"log"
	"linkup.local/server/internal/location"
	"math/rand"
	"os"
	"path/filepath"
	"sort"
	"strconv"
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
	ID           int64  `json:"id"`
	Phone        string `json:"phone"`
	Username     string `json:"username,omitempty"`
	PasswordHash string `json:"-"`
	UnionID      string `json:"unionId"`
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
	NextID    int64              `json:"nextID"`
	NextFix   int64              `json:"nextFix"`
	Users     []User             `json:"users"`
	Extras    map[string]Extra   `json:"extras"`
	Applies   map[string]Apply   `json:"applies"`
	Codes     map[string]Code    `json:"codes"`
	Locations []fix                    `json:"locations"`
	Locks     map[string]LockState     `json:"locks,omitempty"`
	Devices   map[int64]DeviceState    `json:"devices,omitempty"`
	Settings  map[int64]map[string]string `json:"settings,omitempty"`
	Screens   map[string]ScreenshotState `json:"screenshots,omitempty"`
	UsernameIdx map[string]int64         `json:"usernameIdx,omitempty"`
	InviteCodes map[string]InviteCode    `json:"inviteCodes,omitempty"`
	CallRecords map[int64][]CallRecordEntry `json:"callRecords,omitempty"`
	AppUses     map[int64][]AppUseEntry    `json:"appUses,omitempty"`
	SensRecords map[int64][]SensEntry      `json:"sensRecords,omitempty"`
	Todos       map[int64][]TodoEntry      `json:"todos,omitempty"`
	NextTodoID  int64                      `json:"nextTodoId,omitempty"`
}

// TodoEntry is one 四象限待办 (the recovered four-quadrant todo list, bean/todo/TodoModel):
// listType 1..4 are the Eisenhower quadrants (重要紧急 / 重要不紧急 / 紧急不重要 / 不紧急不重要 —
// the TodoRsp field names carry the same order). Ids are process-global (NextTodoID) so the
// guardian-facing payloads can reference them unambiguously; the ledger is per account and capped.
type TodoEntry struct {
	ID        int64  `json:"id"`
	ListType  int    `json:"listType"`
	Content   string `json:"content"`
	IsFinish  bool   `json:"isFinish"`
	CreatedAt int64  `json:"createdAt"`
}

const (
	TodoCap        = 200
	TodoMaxContent = 200
)

// SensEntry is one sensitive-behavior event on the guarded phone (监控数据面第三域, DESIGN 五点四十一).
// The original's event-type table is not recoverable from the APK, so this rebuild pins its own:
// 1=应用安装 2=应用卸载 3=应用更新 (see gateway's sensEventLabel for the card wording). Seconds again.
// Deduped by (type, eventTime, detail) and capped per account like the call ledger.
type SensEntry struct {
	Type      int    `json:"type"`
	EventTime int64  `json:"eventTime"`
	AppName   string `json:"appName,omitempty"`
	Phone     string `json:"phone,omitempty"`
	CreatedAt int64  `json:"createdAt"`
}

const SensCap = 200

// AppUseEntry is one app's usage totals for one day on the guarded phone (day = epoch seconds of the
// local day start). Reports are **totals so far**, not increments: AddAppUses replaces the duration
// and count for a matching (package, day) row, which makes a re-report idempotent — the same trick
// the day watermark uses on the call ledger, inverted because usage accumulates instead of happening
// once. Cap per account (AppUseCap) bounds the file.
type AppUseEntry struct {
	PackageName string `json:"packageName"`
	AppName     string `json:"appName,omitempty"`
	Duration    int64  `json:"duration"`
	Count       int    `json:"count"`
	LastUseTime int64  `json:"lastUseTime,omitempty"`
	Day         int64  `json:"day"`
}

const (
	AppUseCap      = 400
	AppUseWindow   = 5 // the ledger keeps at most this many days per account
	AppUseTopCards = 8 // the 应用使用 card lists at most this many rows
)

// CallRecordEntry is one reported phone call (guarded phone → guardian's 通话记录 card). Seconds
// everywhere: time is the call's own stamp, duration is what the dialer recorded. Cap is per account
// (CallRecordCap) so a失控 device cannot grow the file without bound — the audit's P2-1 lesson.
type CallRecordEntry struct {
	Contact    string `json:"contact,omitempty"`
	Phone      string `json:"phone,omitempty"`
	Duration   int64  `json:"duration"`
	IsIncoming bool   `json:"isIncoming"`
	Time       int64  `json:"time"`
}

const CallRecordCap = 200

// InviteCode is one registration invitation: what it costs to join while it lasts. ExpiresAt and
// MaxUses are unix seconds / 0-means-unlimited; UsedCount survives restarts because the codes live
// in the store file, seeded at boot by SeedInviteCodes.
type InviteCode struct {
	Code      string `json:"code"`
	ExpiresAt int64  `json:"expiresAt,omitempty"`
	MaxUses   int    `json:"maxUses,omitempty"`
	UsedCount int    `json:"usedCount"`
}

// The registration rejections RegisterWithUsername can produce; the handler maps each to its own
// user-facing message instead of one opaque 500.
var (
	ErrUsernameTaken    = fmt.Errorf("username already exists")
	ErrInviteUnknown    = fmt.Errorf("invite code unknown")
	ErrInviteExpired    = fmt.Errorf("invite code expired")
	ErrInviteExhausted  = fmt.Errorf("invite code exhausted")
	ErrInviteNotSeeded  = fmt.Errorf("no invite codes configured")
)

// ScreenshotState is the last capture one account pulled off one guarded friend (api/screenshot/done).
// The picture bytes live in the object store; only the served URL is ledgered.
type ScreenshotState struct {
	Pic string `json:"pic"`
	At  int64  `json:"at"`
}

// DeviceState is what `api/phone/state/report` last recorded for one device — the fields the
// widget cards and the lock screens read. This is the seed of the sensitive-event domain
// (recovered 待验 59): pairing lives in Extras, the per-device facts live here.
type DeviceState struct {
	Battery         int    `json:"battery"`
	IsCharging      bool   `json:"isCharging"`
	NetworkType     int    `json:"networkType"`
	ScreenTime      int64  `json:"screenTime"`
	ScreenType      int    `json:"screenType"`
	LastOfflineTime int64  `json:"lastOfflineTime"`
	LastRecoverTime int64  `json:"lastRecoverTime"`
	OfflineTipConf  int64  `json:"offlineTipTimeConf"`
	Wifi            string `json:"wifi"`
	RecordedAt      int64  `json:"recordedAt"`
	// Av/Client/OsVersion/Model/Brand are what lock_list and screenshot/list render as the
	// guarded device's version row. The original bean (bean/PhoneStateInfo) carries model/brand/
	// osVersion; av/client are this rebuild's carrier choice — where the original server learned
	// them is not in the APK (DESIGN 待验 77).
	Av        string `json:"av,omitempty"`
	Client    string `json:"client,omitempty"`
	OsVersion string `json:"osVersion,omitempty"`
	Model     string `json:"model,omitempty"`
	Brand     string `json:"brand,omitempty"`
}

// LockState is what `api/relation/lock` records for one guarded direction. The command itself
// travels over the IM channel (the original's `lockMachine` CMD); the store is the ledger the
// lock_list reads back.
type LockState struct {
	LockTime    int64  `json:"lockTime"`
	LockDuration int64 `json:"lockDuration"`
	LockTitle   string `json:"lockTitle"`
	LockBackPic string `json:"lockBackPic"`
	Question    string `json:"question"`
}

// Store guards Data and persists it in batches. Mutations mark the file dirty and schedule one
// deferred write instead of rewriting the whole JSON on every call — a settings screen that flips
// five switches is one disk write, not five. The trade-off is stated in DESIGN: a crash can lose
// at most one flush window of writes; the real fix is a database, and this package is the seam
// that gets replaced when one arrives. Flush/Close are the two synchronous exits.
type Store struct {
	path string
	mu   sync.Mutex
	data Data

	// flushDelay is how long a dirty file may wait. The timer's first deadline is kept: a steady
	// stream of writes must not postpone the flush forever (bounded staleness, not a trailing edge).
	flushDelay time.Duration
	timer      *time.Timer
	dirty      bool

	// OnFlushError sees every background write failure; nil logs to the standard logger.
	OnFlushError func(error)
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
	s := &Store{path: path, flushDelay: defaultFlushDelay, data: Data{NextID: firstUserID, NextFix: 1, Extras: map[string]Extra{}, Applies: map[string]Apply{}, Codes: map[string]Code{}}}
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

// defaultFlushDelay is the batching window. 200ms keeps a UI burst to one write while the
// reload-without-Close window (a crash) stays short enough to debug from logs.
const defaultFlushDelay = 200 * time.Millisecond

// save writes the file; the caller holds mu.
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

// markDirtyLocked schedules the deferred write. The caller holds mu. An already-pending timer keeps
// its deadline, so bursts coalesce but never starve the flush.
func (s *Store) markDirtyLocked() {
	if s.path == "" {
		return
	}
	s.dirty = true
	if s.timer == nil {
		s.timer = time.AfterFunc(s.flushDelay, s.flush)
	}
}

// flush is the timer's entry point; it re-enters the lock without holding it across the write.
func (s *Store) flush() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.dirty {
		return
	}
	if err := s.save(); err != nil {
		if s.OnFlushError != nil {
			s.OnFlushError(err)
		} else {
			log.Printf("linkup/store: deferred write failed: %v", err)
		}
		return // dirty stays set; the next mark or Flush retries
	}
	s.dirty = false
}

// Flush writes the file now if anything is pending. Callers that must survive a restart call it
// before handing the path to another process.
func (s *Store) Flush() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.timer != nil {
		s.timer.Stop()
		s.timer = nil
	}
	if !s.dirty || s.path == "" {
		return nil
	}
	if err := s.save(); err != nil {
		return err
	}
	s.dirty = false
	return nil
}

// Close flushes and stops the background timer. Safe to call twice.
func (s *Store) Close() error {
	return s.Flush()
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
	s.markDirtyLocked()
	copied := *u
	return &copied, nil
}

// ThirdAccount is `api/user/login`: a WeChat or QQ identity, plus the phone the user may have typed to
// bind it. The union id is the lookup key, so the same identity always returns to the same account and
// a late phone just attaches to it. When only the phone is known to us, that account adopts the union
// id if it has none — one person cannot end up with two accounts because they used two entry points.
//
// Kept but parked behind the gateway's -enable-third-login switch (default off): the identity is
// client-asserted, so an enabled deployment without vendor-side OAuth verification is an account
// takeover channel (audit P0-1). The code stays for the day that verification exists.
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
	s.markDirtyLocked()
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
		s.markDirtyLocked()
		return updated, nil
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
		s.markDirtyLocked()
		return nil
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
	s.markDirtyLocked()
	return nil
}

// ExtraFor reads one direction, or a zero-value Extra when nothing was recorded (the client's own
// default then applies).
// ByUsername finds a user by their username; error if not found.
func (s *Store) ByUsername(username string) (User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.data.UsernameIdx != nil {
		if id, ok := s.data.UsernameIdx[username]; ok {
			for _, u := range s.data.Users {
				if u.ID == id {
					return u, nil
				}
			}
		}
	}
	return User{}, fmt.Errorf("user not found: %s", username)
}

// SeedInviteCodes upserts boot-time codes into the store. Metadata (expiry, quota) follows the
// newest seed so an operator can tighten or extend a code by restarting; the burn count is kept —
// a restart must never refill a code that has already been spent.
func (s *Store) SeedInviteCodes(codes []InviteCode) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.data.InviteCodes == nil {
		s.data.InviteCodes = map[string]InviteCode{}
	}
	for _, c := range codes {
		if c.Code == "" {
			continue
		}
		if old, exists := s.data.InviteCodes[c.Code]; exists {
			c.UsedCount = old.UsedCount
		}
		s.data.InviteCodes[c.Code] = c
	}
	s.markDirtyLocked()
}

// RegisterWithUsername is the whole username/password registration under one lock: the username is
// new, the invite code exists and is neither expired nor exhausted, the account is created, and the
// code is burned — all or nothing. "注册成功才扣减" is this atomicity: an invalid code never
// creates a user, and a failed creation never consumes a slot.
func (s *Store) RegisterWithUsername(username, passwordHash, invite string) (*User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	// The invite code is judged before the username so probing usernames cannot be done without a
	// live code — "用户名已存在" is only ever an answer a valid code can earn.
	if len(s.data.InviteCodes) == 0 {
		// No codes configured at all: registration is closed until an operator seeds one.
		return nil, ErrInviteNotSeeded
	}
	code, ok := s.data.InviteCodes[invite]
	if !ok {
		return nil, ErrInviteUnknown
	}
	if code.ExpiresAt != 0 && time.Now().Unix() > code.ExpiresAt {
		return nil, ErrInviteExpired
	}
	if code.MaxUses != 0 && code.UsedCount >= code.MaxUses {
		return nil, ErrInviteExhausted
	}
	if s.data.UsernameIdx == nil {
		s.data.UsernameIdx = map[string]int64{}
	}
	if _, exists := s.data.UsernameIdx[username]; exists {
		return nil, ErrUsernameTaken
	}
	s.data.NextID++
	u := User{
		ID:           s.data.NextID,
		Username:     username,
		PasswordHash: passwordHash,
		Nickname:     username,
		IsRegister:   true,
	}
	s.data.Users = append(s.data.Users, u)
	s.data.UsernameIdx[username] = u.ID
	code.UsedCount++
	s.data.InviteCodes[invite] = code
	s.save()
	return &u, nil
}

// SaveToken stores the current JWT for a user.
func (s *Store) SaveToken(userID int64, token string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.data.Users {
		if s.data.Users[i].ID == userID {
			s.data.Users[i].Token = token
			s.save()
			return
		}
	}
}

// LockFor answers the recorded lock for one direction; the zero value means "not locked".
func (s *Store) LockFor(owner, friend int64) LockState {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.data.Locks[lockKey(owner, friend)]
}

// SetLock records (or refreshes) a lock; ClearLock removes it. Both persist like every other write.
func (s *Store) SetLock(owner, friend int64, state LockState) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.data.Locks == nil {
		s.data.Locks = map[string]LockState{}
	}
	s.data.Locks[lockKey(owner, friend)] = state
	s.markDirtyLocked()
}

func (s *Store) ClearLock(owner, friend int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.data.Locks, lockKey(owner, friend))
	s.markDirtyLocked()
}

func lockKey(owner, friend int64) string { return extraKey(owner, friend) }

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
	s.markDirtyLocked()
	return nil
}

// RememberApply keeps the sender's relation choice until the answer arrives.
func (s *Store) RememberApply(a Apply) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	a.At = time.Now().UnixMilli()
	s.data.Applies[extraKey(a.FromID, a.ToID)] = a
	s.markDirtyLocked()
	return nil
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
	s.markDirtyLocked()
	return nil
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
	s.markDirtyLocked()
	return c, nil
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
		s.markDirtyLocked()
		return false
	}
	if c.Attempts >= maxCodeAttempts {
		return false
	}
	if !strings.EqualFold(strings.TrimSpace(c.Value), strings.TrimSpace(value)) {
		c.Attempts++
		s.data.Codes[phone] = c
		s.markDirtyLocked()
		return false
	}
	delete(s.data.Codes, phone)
	s.markDirtyLocked()
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
	s.markDirtyLocked()
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

// AddCallRecords appends the batch the guarded phone reported. Duplicates (same time+phone+direction)
// are absorbed — a report retry or a ContentObserver double-fire must not double-count a call — and the
// per-account list stays capped at the newest CallRecordCap rows.
func (s *Store) AddCallRecords(userID int64, records []CallRecordEntry) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(records) == 0 {
		return
	}
	if s.data.CallRecords == nil {
		s.data.CallRecords = map[int64][]CallRecordEntry{}
	}
	existing := s.data.CallRecords[userID]
	seen := map[string]bool{}
	for _, e := range existing {
		seen[callRecordKey(e)] = true
	}
	for _, e := range records {
		if seen[callRecordKey(e)] {
			continue
		}
		seen[callRecordKey(e)] = true
		existing = append(existing, e)
	}
	if len(existing) > CallRecordCap {
		existing = existing[len(existing)-CallRecordCap:]
	}
	s.data.CallRecords[userID] = existing
	s.markDirtyLocked()
}

// CallRecordsFor answers the newest `limit` calls of one account, newest first; nil when none.
func (s *Store) CallRecordsFor(userID int64, limit int) []CallRecordEntry {
	s.mu.Lock()
	defer s.mu.Unlock()
	all := s.data.CallRecords[userID]
	if len(all) == 0 || limit <= 0 {
		return nil
	}
	if len(all) > limit {
		all = all[len(all)-limit:]
	}
	out := make([]CallRecordEntry, 0, len(all))
	for i := len(all) - 1; i >= 0; i-- {
		out = append(out, all[i])
	}
	return out
}

func callRecordKey(e CallRecordEntry) string {
	return strconv.FormatInt(e.Time, 10) + "|" + e.Phone + "|" + strconv.FormatBool(e.IsIncoming)
}

// AddAppUses writes one day's usage totals for an account. Rows key on (package, day); a matching
// row has its duration/count **replaced** (each report carries the day's totals so far, so the write
// is idempotent) and lastUseTime takes the max. Days roll: only the newest AppUseWindow days survive
// per account, and the whole ledger caps at AppUseCap rows.
func (s *Store) AddAppUses(userID int64, day int64, records []AppUseEntry) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(records) == 0 {
		return
	}
	if s.data.AppUses == nil {
		s.data.AppUses = map[int64][]AppUseEntry{}
	}
	ledger := s.data.AppUses[userID]
	byKey := map[string]int{}
	for i, e := range ledger {
		byKey[e.PackageName+"|"+strconv.FormatInt(e.Day, 10)] = i
	}
	for _, r := range records {
		if r.PackageName == "" {
			continue
		}
		row := r
		row.Day = day
		if at, ok := byKey[row.PackageName+"|"+strconv.FormatInt(day, 10)]; ok {
			// Totals so far replace the stored row (idempotent re-report); only lastUseTime and the
			// display name take the defensive max/fallback.
			if ledger[at].LastUseTime > row.LastUseTime {
				row.LastUseTime = ledger[at].LastUseTime
			}
			if row.AppName == "" {
				row.AppName = ledger[at].AppName
			}
			ledger[at] = row
		} else {
			byKey[row.PackageName+"|"+strconv.FormatInt(day, 10)] = len(ledger)
			ledger = append(ledger, row)
		}
	}
	// Days roll off oldest first, then a hard row cap keeps a pathological reporter bounded.
	ledger = dropOldAppUseDays(ledger, AppUseWindow)
	if len(ledger) > AppUseCap {
		ledger = ledger[len(ledger)-AppUseCap:]
	}
	s.data.AppUses[userID] = ledger
	s.markDirtyLocked()
}

// dropOldAppUseDays keeps only the newest `window` distinct days of an ordered ledger.
func dropOldAppUseDays(ledger []AppUseEntry, window int) []AppUseEntry {
	days := map[int64]bool{}
	for _, e := range ledger {
		days[e.Day] = true
	}
	if len(days) <= window {
		return ledger
	}
	kept := make([]int64, 0, window)
	for day := range days {
		kept = append(kept, day)
	}
	sort.Slice(kept, func(i, j int) bool { return kept[i] < kept[j] })
	keep := map[int64]bool{}
	for _, day := range kept[len(kept)-window:] {
		keep[day] = true
	}
	out := ledger[:0]
	for _, e := range ledger {
		if keep[e.Day] {
			out = append(out, e)
		}
	}
	return out
}

// AppUsesFor answers one account's ledger rows for the given days, newest day then biggest use
// first; empty when nothing was reported.
func (s *Store) AppUsesFor(userID int64, days []int64) []AppUseEntry {
	s.mu.Lock()
	defer s.mu.Unlock()
	want := map[int64]bool{}
	for _, day := range days {
		want[day] = true
	}
	var out []AppUseEntry
	for _, e := range s.data.AppUses[userID] {
		if want[e.Day] {
			out = append(out, e)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Duration > out[j].Duration })
	return out
}

// AddSensRecords appends sensitive-behavior events. Duplicates (type+time+detail) are absorbed and
// the per-account list stays capped at the newest SensCap rows, newest appended last.
func (s *Store) AddSensRecords(userID int64, records []SensEntry) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(records) == 0 {
		return
	}
	if s.data.SensRecords == nil {
		s.data.SensRecords = map[int64][]SensEntry{}
	}
	existing := s.data.SensRecords[userID]
	seen := map[string]bool{}
	for _, e := range existing {
		seen[sensKey(e)] = true
	}
	for _, e := range records {
		if seen[sensKey(e)] {
			continue
		}
		seen[sensKey(e)] = true
		existing = append(existing, e)
	}
	if len(existing) > SensCap {
		existing = existing[len(existing)-SensCap:]
	}
	s.data.SensRecords[userID] = existing
	s.markDirtyLocked()
}

func sensKey(e SensEntry) string {
	return strconv.Itoa(e.Type) + "|" + strconv.FormatInt(e.EventTime, 10) + "|" + e.AppName + "|" + e.Phone
}

// SensRecordsFor answers the newest  events of one account whose event time falls on the
// given yyyyMMdd day ("" = all days), newest first. The day bucketing uses the store's own zone
// because the events carry the guarded phone's epoch seconds — the approximate cross-device day
//口径 is written down in DESIGN 五点四十一.
func (s *Store) SensRecordsFor(userID int64, day string, limit int, zone *time.Location) []SensEntry {
	s.mu.Lock()
	defer s.mu.Unlock()
	all := s.data.SensRecords[userID]
	if len(all) == 0 {
		return nil
	}
	out := make([]SensEntry, 0, limit)
	if day == "" {
		for i := len(all) - 1; i >= 0 && len(out) < limit; i-- {
			out = append(out, all[i])
		}
		return out
	}
	for i := len(all) - 1; i >= 0 && len(out) < limit; i-- {
		if zoneDay(all[i].EventTime, zone) == day {
			out = append(out, all[i])
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

// SensDaysFor answers the yyyyMMdd days the account has events on, newest first.
func (s *Store) SensDaysFor(userID int64, zone *time.Location) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	seen := map[string]bool{}
	var out []string
	for i := len(s.data.SensRecords[userID]) - 1; i >= 0; i-- {
		day := zoneDay(s.data.SensRecords[userID][i].EventTime, zone)
		if !seen[day] {
			seen[day] = true
			out = append(out, day)
		}
	}
	return out
}

// LatestSensDay answers the newest day with events; empty when none.
func (s *Store) LatestSensDay(userID int64, zone *time.Location) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	var latest int64
	for _, e := range s.data.SensRecords[userID] {
		if e.EventTime > latest {
			latest = e.EventTime
		}
	}
	if latest == 0 {
		return ""
	}
	return zoneDay(latest, zone)
}

// zoneDay buckets an epoch-second stamp into yyyyMMdd using the store's configured day zone.
func zoneDay(seconds int64, zone *time.Location) string {
	return time.Unix(seconds, 0).In(zone).Format("20060102")
}

// AppUseMinutes answers the whole ledger's duration summed into minutes for the given days — the
// card's useToday/usedYesterday numbers.
func (s *Store) AppUseMinutes(userID int64, days []int64) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	var seconds int64
	for _, e := range s.data.AppUses[userID] {
		for _, day := range days {
			if e.Day == day {
				seconds += e.Duration
			}
		}
	}
	return int(seconds / 60)
}

// TodosFor answers one account's todos, oldest first (the screen groups them, order inside a
// quadrant is the creation order). Empty when none.
func (s *Store) TodosFor(userID int64) []TodoEntry {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]TodoEntry, 0, len(s.data.Todos[userID]))
	out = append(out, s.data.Todos[userID]...)
	return out
}

// PutTodo inserts or updates one todo for an account. An entry with id 0 (or an unknown id) is a
// new todo: the store assigns the next process-global id, stamps createdAt and appends it. A known
// id owned by the caller updates content/listType/isFinish in place. Content is trimmed; empty
// content and a listType outside 1..4 are the caller's validation errors, refused here.
func (s *Store) PutTodo(userID int64, entry TodoEntry) (TodoEntry, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	content := strings.TrimSpace(entry.Content)
	if content == "" {
		return TodoEntry{}, fmt.Errorf("todo content is empty")
	}
	if entry.ListType < 1 || entry.ListType > 4 {
		return TodoEntry{}, fmt.Errorf("todo listType %d is outside the four quadrants", entry.ListType)
	}
	if len(content) > TodoMaxContent {
		content = content[:TodoMaxContent]
	}
	if s.data.Todos == nil {
		s.data.Todos = map[int64][]TodoEntry{}
	}
	ledger := s.data.Todos[userID]
	if entry.ID > 0 {
		for i, e := range ledger {
			if e.ID == entry.ID {
				e.Content, e.ListType, e.IsFinish = content, entry.ListType, entry.IsFinish
				s.data.Todos[userID][i] = e
				s.markDirtyLocked()
				copied := e
				return copied, nil
			}
		}
		return TodoEntry{}, fmt.Errorf("todo %d not found for user %d", entry.ID, userID)
	}
	if len(ledger) >= TodoCap {
		return TodoEntry{}, fmt.Errorf("todo list is full (%d)", TodoCap)
	}
	if s.data.NextTodoID == 0 {
		s.data.NextTodoID = 1
	}
	entry.ID = s.data.NextTodoID
	s.data.NextTodoID++
	entry.Content = content
	entry.CreatedAt = time.Now().Unix()
	s.data.Todos[userID] = append(ledger, entry)
	s.markDirtyLocked()
	return entry, nil
}

// DeleteTodo removes one todo owned by the account; ok answers whether a row was removed.
func (s *Store) DeleteTodo(userID, id int64) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	ledger := s.data.Todos[userID]
	for i, e := range ledger {
		if e.ID == id {
			s.data.Todos[userID] = append(ledger[:i], ledger[i+1:]...)
			s.markDirtyLocked()
			return true
		}
	}
	return false
}

// LatestAppUseDay answers the newest day the account reported usage for; 0 when none. The card's
// "today" is this day — the guarded phone's local day, not the server's.
func (s *Store) LatestAppUseDay(userID int64) int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	var latest int64
	for _, e := range s.data.AppUses[userID] {
		if e.Day > latest {
			latest = e.Day
		}
	}
	return latest
}

// SettingFor answers one settings slot ("im" today) for an account; empty when never written.
func (s *Store) SettingFor(userID int64, slot string) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.data.Settings == nil {
		return ""
	}
	return s.data.Settings[userID][slot]
}

// SetSetting stores one settings slot verbatim (the client's own bean JSON).
func (s *Store) SetSetting(userID int64, slot, value string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.data.Settings == nil {
		s.data.Settings = map[int64]map[string]string{}
	}
	if s.data.Settings[userID] == nil {
		s.data.Settings[userID] = map[string]string{}
	}
	s.data.Settings[userID][slot] = value
	s.markDirtyLocked()
}

// SetDeviceState records (or refreshes) one device's self-report.
func (s *Store) SetDeviceState(userID int64, state DeviceState) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.data.Devices == nil {
		s.data.Devices = map[int64]DeviceState{}
	}
	s.data.Devices[userID] = state
	s.markDirtyLocked()
}

// RecordScreenshot stores the latest capture one account recorded against a friend.
func (s *Store) RecordScreenshot(me, friend int64, pic string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.data.Screens == nil {
		s.data.Screens = map[string]ScreenshotState{}
	}
	s.data.Screens[extraKey(me, friend)] = ScreenshotState{Pic: pic, At: time.Now().Unix()}
	s.markDirtyLocked()
}

// ScreenshotFor answers that latest capture; empty when none was recorded.
func (s *Store) ScreenshotFor(me, friend int64) ScreenshotState {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.data.Screens[extraKey(me, friend)]
}

// DeviceStateFor answers the last self-report; zero value when the device never reported.
func (s *Store) DeviceStateFor(userID int64) DeviceState {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.data.Devices[userID]
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
