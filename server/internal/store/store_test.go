package store

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func mustOpen(t *testing.T, path string) *Store {
	t.Helper()
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func strPtr(v string) *string { return &v }
func intPtr(v int) *int       { return &v }

func TestVerificationCodesExpireAndAreBurned(t *testing.T) {
	s := mustOpen(t, "")
	code, err := s.IssueCode("13800000100", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if len(code.Value) != 4 {
		t.Fatalf("the client auto-submits at four digits, got %q", code.Value)
	}
	if !s.CheckCode("13800000100", code.Value) {
		t.Fatal("a fresh code should be accepted")
	}
	if s.CheckCode("13800000100", code.Value) {
		t.Error("a code must not be usable twice")
	}

	// Wrong answers are bounded, and the bound is on attempts, not on how many codes were issued.
	if _, err := s.IssueCode("13800000101", time.Hour); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < maxCodeAttempts; i++ {
		if s.CheckCode("13800000101", "0000") {
			t.Fatal("a wrong code logged somebody in")
		}
	}
	fresh, err := s.IssueCode("13800000101", time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if !s.CheckCode("13800000101", fresh.Value) {
		t.Error("a new code should clear the earlier guesses")
	}

	expired, err := s.IssueCode("13800000102", time.Nanosecond)
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(2 * time.Millisecond)
	if s.CheckCode("13800000102", expired.Value) {
		t.Fatal("the ttl argument was accepted and then ignored, so codes would live forever")
	}
	if s.CheckCode("13800000199", "1234") {
		t.Error("a phone that never asked for a code logged in")
	}
}

func TestAccountIdsAreEightDigitsBecauseTheyAreTheMatchCode(t *testing.T) {
	s := mustOpen(t, "")
	seen := map[int64]bool{}
	for i := 0; i < 50; i++ {
		u, err := s.CreateAccount(IMID(int64(13800000200 + i)))
		if err != nil {
			t.Fatal(err)
		}
		// AddFriendActivity only treats a typed or scanned input of exactly 8 characters as a match
		// code, and that code is the user id — a 5-digit id never reaches the server.
		if u.ID < 10_000_000 || u.ID > 99_999_999 {
			t.Fatalf("id %d is not 8 digits, so the client will refuse it as a match code", u.ID)
		}
		if seen[u.ID] {
			t.Fatalf("id %d issued twice", u.ID)
		}
		seen[u.ID] = true
	}

	first := s.Accounts()[0]
	again, err := s.CreateAccount(first.Phone)
	if err != nil {
		t.Fatal(err)
	}
	if again.ID != first.ID {
		t.Fatalf("re-login minted a second identity: %+v vs %+v", again, first)
	}
	if err := s.RecordDevice(first.ID, "cid-1", "apns-1"); err != nil {
		t.Fatal(err)
	}
	reloaded, err := s.ByPhone(first.Phone)
	if err != nil || reloaded.GetuiCid != "cid-1" || reloaded.PushToken != "apns-1" {
		t.Fatalf("device record lost: %+v %v", reloaded, err)
	}
	// pre_confirm?code= and the apply body's code both carry the id.
	if u, err := s.ByID(first.ID); err != nil || u.ID != first.ID {
		t.Fatalf("the id must resolve as the match code: %+v %v", u, err)
	}
	if _, err := s.ByID(99999999); err != ErrNotFound {
		t.Errorf("an unknown code should be ErrNotFound, got %v", err)
	}
	if _, err := s.ByID(1); err != ErrNotFound {
		t.Errorf("a 1-digit code should be ErrNotFound, got %v", err)
	}

	updated, err := s.UpdateProfile(first.ID, Profile{
		Nickname: strPtr("小花"), Avatar: strPtr("http://a/1.png"), Sex: intPtr(2), Stage: intPtr(3),
	})
	if err != nil {
		t.Fatal(err)
	}
	if updated.Nickname != "小花" || updated.Avatar != "http://a/1.png" || updated.Sex != 2 || updated.Stage != 3 {
		t.Fatalf("profile write drifted: %+v", updated)
	}
	// Keys the client left out must not erase what is stored, and `stage: 0` must be honoured —
	// unlike the other three, zero is a value there ("start the profile wizard again").
	if kept, err := s.UpdateProfile(first.ID, Profile{}); err != nil || kept.Nickname != "小花" ||
		kept.Sex != 2 || kept.Stage != 3 {
		t.Fatalf("an absent profile field erased the stored value: %+v %v", kept, err)
	}
	if back, err := s.UpdateProfile(first.ID, Profile{Stage: intPtr(0)}); err != nil || back.Stage != 0 {
		t.Fatalf("stage 0 was ignored: %+v %v", back, err)
	}
	if _, err := s.UpdateProfile(999999, Profile{Nickname: strPtr("x")}); err != ErrNotFound {
		t.Errorf("updating a stranger should be ErrNotFound, got %v", err)
	}
}

func TestExtrasArePerDirectionAndDeletionClearsBoth(t *testing.T) {
	s := mustOpen(t, "")
	a, err := s.CreateAccount("13800000300")
	if err != nil {
		t.Fatal(err)
	}
	b, err := s.CreateAccount("13800000301")
	if err != nil {
		t.Fatal(err)
	}
	if err := s.PutExtra(Extra{OwnerID: a.ID, FriendID: b.ID, Relation: 4, Remark: "妈妈", SensLoc: true}); err != nil {
		t.Fatal(err)
	}
	if err := s.PutExtra(Extra{OwnerID: b.ID, FriendID: a.ID, Relation: 1}); err != nil {
		t.Fatal(err)
	}
	if got := s.ExtraFor(b.ID, a.ID); got.Relation != 1 || got.Remark != "" {
		t.Fatalf("the reverse direction overwrote the forward one: %+v", got)
	}
	if got := s.ExtraFor(a.ID, b.ID); got.Relation != 4 || !got.SensLoc || got.Since == 0 {
		t.Fatalf("forward extras drifted: %+v", got)
	}
	// The client renders createAt as `Date(createAt * 1000L)`, so a millisecond value here would
	// print a date in the 58000s instead of "2026-01-02".
	if got := s.ExtraFor(a.ID, b.ID).Since; got < 1_700_000_000 || got > 9_999_999_999 {
		t.Errorf("Since must be epoch seconds, got %d", got)
	}
	if s.FriendCount(a.ID) != 1 || s.FriendCount(b.ID) != 1 {
		t.Fatalf("quota counting must only see one owner's side: %d / %d", s.FriendCount(a.ID), s.FriendCount(b.ID))
	}
	if n := s.FriendCount(999999); n != 0 {
		t.Errorf("a stranger has %d friends", n)
	}
	// A prefix-sharing id must not be counted as someone else's friend.
	if err := s.PutExtra(Extra{OwnerID: a.ID * 10, FriendID: b.ID, Relation: 2}); err != nil {
		t.Fatal(err)
	}
	if got := s.FriendCount(a.ID); got != 1 {
		t.Errorf("friend counting matched a prefix, got %d", got)
	}

	pending := Apply{FromID: a.ID, ToID: b.ID, Relation: 3, SensAll: true, SensLoc: true}
	if err := s.RememberApply(pending); err != nil {
		t.Fatal(err)
	}
	recorded, ok := s.ApplyFor(a.ID, b.ID)
	if !ok || recorded.Relation != pending.Relation || !recorded.SensAll || !recorded.SensLoc || recorded.SensCall {
		t.Fatalf("apply payload lost: %+v %v", recorded, ok)
	}
	if _, ok := s.ApplyFor(b.ID, a.ID); ok {
		t.Error("the reverse direction invented an application")
	}
	if err := s.ForgetPair(a.ID, b.ID); err != nil {
		t.Fatal(err)
	}
	if got := s.ExtraFor(b.ID, a.ID); got.Relation != 0 {
		t.Errorf("ForgetPair left the reverse direction behind: %+v", got)
	}
	if _, ok := s.ApplyFor(a.ID, b.ID); ok {
		t.Error("ForgetPair left the pending application behind")
	}
}

func TestStoreReloadsFromDisk(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nested", "linkup.json")
	first := mustOpen(t, path)
	u, err := first.CreateAccount("13800000400")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := first.UpdateProfile(u.ID, Profile{Nickname: strPtr("小花"), Avatar: strPtr("http://a/1.png"), Sex: intPtr(2)}); err != nil {
		t.Fatal(err)
	}
	code, err := first.IssueCode("13800000401", time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	// Batching writes means a second process only sees the file after a Flush/Close — the same
	// contract the server honours on shutdown.
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}

	second := mustOpen(t, path)
	reloaded, err := second.ByPhone("13800000400")
	if err != nil {
		t.Fatalf("nothing survived the restart: %v", err)
	}
	if reloaded.ID != u.ID || reloaded.Nickname != "小花" {
		t.Fatalf("identity drifted across reload: %+v vs %+v", reloaded, u)
	}
	if reloaded.Nickname != "小花" || reloaded.Avatar != "http://a/1.png" || reloaded.Sex != 2 {
		t.Fatalf("profile edits were not persisted: %+v", reloaded)
	}
	next, err := second.CreateAccount("13800000402")
	if err != nil {
		t.Fatal(err)
	}
	if next.ID <= reloaded.ID {
		t.Fatalf("NextID restarted at %d after %d", next.ID, reloaded.ID)
	}
	// The code lives in the store, so a restart mid-login must still redeem it.
	if !second.CheckCode("13800000401", code.Value) {
		t.Error("a code issued before the restart could not be redeemed after it")
	}
}

// TestRegisterWithUsernameValidatesTheInviteCode pins the invite-code model: every rejection is its
// own sentinel, nothing is created on a rejection, and the burn happens on success only.
func TestRegisterWithUsernameValidatesTheInviteCode(t *testing.T) {
	s := mustOpen(t, "")
	s.SeedInviteCodes([]InviteCode{
		{Code: "OPEN", MaxUses: 2},
		{Code: "OLD", ExpiresAt: 1}, // unix second 1 is long past
		{Code: "SPENT", MaxUses: 1, UsedCount: 1},
	})

	if _, err := s.RegisterWithUsername("alice", "hash", "NOPE"); !errors.Is(err, ErrInviteUnknown) {
		t.Fatalf("an unknown code: %v", err)
	}
	if _, err := s.RegisterWithUsername("alice", "hash", "OLD"); !errors.Is(err, ErrInviteExpired) {
		t.Fatalf("an expired code: %v", err)
	}
	if _, err := s.RegisterWithUsername("alice", "hash", "SPENT"); !errors.Is(err, ErrInviteExhausted) {
		t.Fatalf("an exhausted code: %v", err)
	}
	if n := len(s.Accounts()); n != 0 {
		t.Fatalf("rejected registrations must not leave accounts: %d", n)
	}

	u, err := s.RegisterWithUsername("alice", "hash", "OPEN")
	if err != nil {
		t.Fatal(err)
	}
	if got, err := s.ByUsername("alice"); err != nil || got.ID != u.ID || got.PasswordHash != "hash" {
		t.Fatalf("the account did not land as asked: %+v %v", got, err)
	}
	if code := s.data.InviteCodes["OPEN"]; code.UsedCount != 1 {
		t.Fatalf("the valid code was not burned on success: %+v", code)
	}

	// The duplicate answers the username, not the code, and does not burn another slot.
	if _, err := s.RegisterWithUsername("alice", "hash", "OPEN"); !errors.Is(err, ErrUsernameTaken) {
		t.Fatalf("a duplicate username: %v", err)
	}
	if code := s.data.InviteCodes["OPEN"]; code.UsedCount != 1 {
		t.Fatalf("a failed registration burned a slot: %+v", code)
	}

	// A fresh store without any seeded codes is closed registration, not open.
	empty := mustOpen(t, "")
	if _, err := empty.RegisterWithUsername("bob", "hash", "OPEN"); !errors.Is(err, ErrInviteNotSeeded) {
		t.Fatalf("an unseeded store: %v", err)
	}
}

// TestInviteCodesSurviveReloadWithUsedCount pins the operational semantics: a restart keeps the
// burn count, and re-seeding a known code updates its metadata without refilling it.
func TestInviteCodesSurviveReloadWithUsedCount(t *testing.T) {
	path := filepath.Join(t.TempDir(), "linkup.json")
	first := mustOpen(t, path)
	first.SeedInviteCodes([]InviteCode{{Code: "OPEN", MaxUses: 1}})
	if _, err := first.RegisterWithUsername("alice", "hash", "OPEN"); err != nil {
		t.Fatal(err)
	}
	first.Close()

	reopened := mustOpen(t, path)
	// The old pattern of the batching store: the file is whole after Close only.
	reopened.SeedInviteCodes([]InviteCode{{Code: "OPEN", MaxUses: 1}})
	if _, err := reopened.RegisterWithUsername("bob", "hash", "OPEN"); !errors.Is(err, ErrInviteExhausted) {
		t.Fatalf("a restart refilled the burned code: %v", err)
	}
	if n := len(reopened.Accounts()); n != 1 {
		t.Fatalf("the refused registration left an account: %d", n)
	}
}

func TestBurstWritesCoalesceIntoOneFlush(t *testing.T) {
	path := filepath.Join(t.TempDir(), "linkup.json")
	s := mustOpen(t, path)

	// A settings screen's worth of writes inside one flush window is one file, not five.
	for i := 0; i < 5; i++ {
		s.SetSetting(int64(10000001+i), "im", `{"a":1}`)
	}
	if _, err := os.Stat(path + ".tmp"); err == nil {
		t.Fatal("a write leaked through before the flush window closed")
	}
	if _, err := os.Stat(path); err == nil {
		t.Fatal("five writes each rewrote the file: debouncing did not happen")
	}
	if err := s.Flush(); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if n := strings.Count(string(raw), `"settings"`); n != 1 {
		t.Fatalf("settings appeared %d times in one file", n)
	}

	// Close is idempotent and a second Close after more writes still flushes them.
	s.SetSetting(int64(10000009), "im", `{"b":2}`)
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("Close is not idempotent: %v", err)
	}
	raw, err = os.ReadFile(path)
	if err != nil || !strings.Contains(string(raw), "10000009") {
		t.Fatalf("the post-Flush write was lost on Close: %v", err)
	}
}

func TestVipRuleMatchesTheClientsOwn(t *testing.T) {
	if !(User{VIPSubType: 3}).IsVip() || !(User{SharedVip: 1}).IsVip() || (User{}).IsVip() {
		t.Fatal("the client's rule is own subscription first, then any shared grant")
	}
}

func TestParseIDOnlyAcceptsPlainDigits(t *testing.T) {
	if got := ParseID(IMID(12345)); got != 12345 {
		t.Fatalf("IMID/ParseID is not a round trip: %d", got)
	}
	for _, text := range []string{"", "abc", "1a2", "-1", "12_34"} {
		if got := ParseID(text); got != 0 {
			t.Errorf("ParseID(%q) = %d, expected 0 for anything that is not plain digits", text, got)
		}
	}
	if got := ParseID("0"); got != 0 {
		t.Errorf("ParseID(\"0\") = %d", got)
	}
}

// TestCallRecordsDedupeAndCap pins the ledger's three rules: a retry is absorbed, the newest-first
// read honours its limit, and the per-account cap keeps the file bounded (audit P2-1's lesson,
// learned the hard way on Locations).
func TestCallRecordsDedupeAndCap(t *testing.T) {
	s := mustOpen(t, "")
	s.AddCallRecords(7, []CallRecordEntry{
		{Phone: "13800000001", Contact: "妈妈", Time: 100, IsIncoming: true, Duration: 60},
		{Phone: "13800000002", Time: 200, IsIncoming: false, Duration: 30},
	})
	// A report retry: the same call again must not double-count.
	s.AddCallRecords(7, []CallRecordEntry{{Phone: "13800000001", Contact: "妈妈", Time: 100, IsIncoming: true}})

	all := s.CallRecordsFor(7, 10)
	if len(all) != 2 {
		t.Fatalf("two distinct calls after the retry, got %d", len(all))
	}
	if all[0].Time != 200 || all[1].Time != 100 {
		t.Fatalf("the read is newest first: %+v", all)
	}
	if got := s.CallRecordsFor(7, 1); len(got) != 1 || got[0].Time != 200 {
		t.Fatalf("the limit takes the newest only: %+v", got)
	}
	if got := s.CallRecordsFor(7, 0); got != nil {
		t.Fatalf("a non-positive limit answers nil, not an empty dump: %+v", got)
	}
	if got := s.CallRecordsFor(99, 10); got != nil {
		t.Fatalf("an account with no calls answers nil: %+v", got)
	}

	// The cap keeps the newest rows: 300 distinct calls leave exactly the last CallRecordCap.
	for i := 0; i < 300; i++ {
		s.AddCallRecords(7, []CallRecordEntry{{Phone: "1", Time: 1000 + int64(i)}})
	}
	capped := s.data.CallRecords[7]
	if len(capped) != CallRecordCap {
		t.Fatalf("cap: %d rows", len(capped))
	}
	if capped[len(capped)-1].Time != 1000+299 || capped[0].Time != 1000+299-CallRecordCap+1 {
		t.Fatalf("the cap keeps the newest window: first=%d last=%d", capped[0].Time, capped[len(capped)-1].Time)
	}
}

// TestCallRecordsSurviveReload pins the persistence: the ledger rides the store file like the rest.
func TestCallRecordsSurviveReload(t *testing.T) {
	path := filepath.Join(t.TempDir(), "linkup.json")
	first := mustOpen(t, path)
	first.AddCallRecords(7, []CallRecordEntry{{Phone: "13800000001", Contact: "妈妈", Time: 100, IsIncoming: true}})
	first.Close()

	reopened := mustOpen(t, path)
	all := reopened.CallRecordsFor(7, 10)
	if len(all) != 1 || all[0].Contact != "妈妈" || all[0].Time != 100 {
		t.Fatalf("the ledger did not survive the restart: %+v", all)
	}
}

// TestAppUsesReplaceAndRoll pins the usage ledger's rules: a re-report of the same day's totals
// **replaces** the row (each report carries totals so far — the idempotent trick), days roll off
// oldest first, and the read sorts by duration within the asked days.
func TestAppUsesReplaceAndRoll(t *testing.T) {
	s := mustOpen(t, "")
	day := int64(1_760_000_000_000 / 1000) // any epoch-seconds day start
	s.AddAppUses(7, day, []AppUseEntry{
		{PackageName: "com.a", AppName: "A", Duration: 600, Count: 3, LastUseTime: day + 100},
		{PackageName: "com.b", Duration: 1200},
	})

	// The same day's totals so far replace, they do not accumulate.
	s.AddAppUses(7, day, []AppUseEntry{
		{PackageName: "com.a", AppName: "", Duration: 900, Count: 5, LastUseTime: day + 50},
		{PackageName: "com.c", Duration: 60},
	})
	rows := s.AppUsesFor(7, []int64{day})
	if len(rows) != 3 {
		t.Fatalf("three rows after the re-report: %+v", rows)
	}
	// The read sorts by duration: com.b (1200) leads, and the replaced com.a row proves the rules.
	a := rows[1]
	if a.PackageName != "com.a" || a.Duration != 900 || a.Count != 5 {
		t.Fatalf("totals replace, they do not accumulate: %+v", a)
	}
	if a.LastUseTime != day+100 {
		t.Fatalf("lastUseTime takes the defensive max: %+v", a)
	}
	if a.AppName != "A" {
		t.Fatalf("an empty display name keeps the stored one: %+v", a)
	}
	if s.AppUseMinutes(7, []int64{day}) != (900+1200+60)/60 {
		t.Fatalf("minutes sum the replaced rows: %d", s.AppUseMinutes(7, []int64{day}))
	}

	// Days roll: writing five days keeps the newest AppUseWindow of them.
	for i := 0; i < AppUseWindow+2; i++ {
		s.AddAppUses(7, day-int64(i+1)*86400, []AppUseEntry{{PackageName: "com.old", Duration: 10}})
	}
	days := map[int64]bool{}
	for _, e := range s.data.AppUses[7] {
		days[e.Day] = true
	}
	if len(days) > AppUseWindow {
		t.Fatalf("the window rolls old days off: %d days left", len(days))
	}
	if s.LatestAppUseDay(7) != day {
		t.Fatalf("the newest day is still today: %d", s.LatestAppUseDay(7))
	}
	if got := s.AppUsesFor(7, []int64{day - 86400*99}); got != nil {
		t.Fatalf("a rolled-off day answers nil: %+v", got)
	}
}

// TestAppUsesSurviveReload pins the persistence of the usage ledger.
func TestAppUsesSurviveReload(t *testing.T) {
	path := filepath.Join(t.TempDir(), "linkup.json")
	first := mustOpen(t, path)
	day := int64(1_760_000_000_000 / 1000)
	first.AddAppUses(7, day, []AppUseEntry{{PackageName: "com.a", AppName: "A", Duration: 600, Count: 2}})
	first.Close()

	reopened := mustOpen(t, path)
	rows := reopened.AppUsesFor(7, []int64{day})
	if len(rows) != 1 || rows[0].Duration != 600 || rows[0].AppName != "A" {
		t.Fatalf("the usage ledger did not survive the restart: %+v", rows)
	}
	if reopened.LatestAppUseDay(7) != day {
		t.Fatalf("the day bookkeeping did not survive: %d", reopened.LatestAppUseDay(7))
	}
}

// TestSensRecordsDedupeCapAndDays pins the sensitive-behavior ledger: retry absorbed, the day
// bucketing answers yyyyMMdd newest first, and the per-account cap keeps the file bounded.
func TestSensRecordsDedupeCapAndDays(t *testing.T) {
	s := mustOpen(t, "")
	zone := time.UTC
	base := int64(1_760_000_000_000 / 1000) // inside some yyyyMMdd day
	s.AddSensRecords(7, []SensEntry{
		{Type: 1, EventTime: base, AppName: " игры", Phone: "", CreatedAt: base},
		{Type: 2, EventTime: base + 60, AppName: "com.b", CreatedAt: base},
	})
	s.AddSensRecords(7, []SensEntry{{Type: 1, EventTime: base, AppName: " игры"}}) // retry

	all := s.SensRecordsFor(7, "", 10, zone)
	if len(all) != 2 {
		t.Fatalf("retry absorbed: %d rows", len(all))
	}
	if all[0].Type != 2 || all[1].Type != 1 {
		t.Fatalf("newest first: %+v", all)
	}

	days := s.SensDaysFor(7, zone)
	if len(days) != 1 || days[0] != time.Unix(base, 0).In(zone).Format("20060102") {
		t.Fatalf("one day bucket, yyyyMMdd: %+v", days)
	}
	if got := s.SensRecordsFor(7, days[0], 10, zone); len(got) != 2 {
		t.Fatalf("the day filter keeps both rows: %+v", got)
	}
	if got := s.SensRecordsFor(7, "19990101", 10, zone); got != nil {
		t.Fatalf("another day answers nil: %+v", got)
	}
	if s.LatestSensDay(7, zone) != days[0] {
		t.Fatalf("the latest day bookkeeping: %q", s.LatestSensDay(7, zone))
	}
	if got := s.SensRecordsFor(99, "", 10, zone); got != nil {
		t.Fatalf("an account with no events answers nil: %+v", got)
	}

	// The cap keeps the newest rows.
	for i := 0; i < SensCap+50; i++ {
		s.AddSensRecords(7, []SensEntry{{Type: 1, EventTime: base + int64(i) + 10}})
	}
	if len(s.data.SensRecords[7]) != SensCap {
		t.Fatalf("cap: %d rows", len(s.data.SensRecords[7]))
	}
}

// TestSensRecordsSurviveReload pins the persistence of the sensitive-behavior ledger.
func TestSensRecordsSurviveReload(t *testing.T) {
	path := filepath.Join(t.TempDir(), "linkup.json")
	first := mustOpen(t, path)
	zone := time.UTC
	base := int64(1_760_000_000_000 / 1000)
	first.AddSensRecords(7, []SensEntry{{Type: 2, EventTime: base, AppName: "com.b", CreatedAt: base}})
	first.Close()

	reopened := mustOpen(t, path)
	all := reopened.SensRecordsFor(7, "", 10, zone)
	if len(all) != 1 || all[0].Type != 2 || all[0].AppName != "com.b" {
		t.Fatalf("the ledger did not survive the restart: %+v", all)
	}
}

// TestTodoLedgerPinsInsertUpdateDeleteAndCap: ids are process-global, updates are in place for the
// owner, the four quadrants validate, and the cap refuses new inserts.
func TestTodoLedgerPinsInsertUpdateDeleteAndCap(t *testing.T) {
	s := mustOpen(t, "")
	first, err := s.PutTodo(7, TodoEntry{ListType: 1, Content: " 交作业 "})
	if err != nil {
		t.Fatal(err)
	}
	if first.ID == 0 || first.Content != "交作业" || first.CreatedAt <= 0 {
		t.Fatalf("the insert trims and stamps: %+v", first)
	}
	second, err := s.PutTodo(7, TodoEntry{ListType: 4, Content: "玩"})
	if err != nil || second.ID == first.ID {
		t.Fatalf("ids are process-global and increasing: %+v vs %+v", second, first)
	}

	// An update in place: same id, new content and quadrant.
	updated, err := s.PutTodo(7, TodoEntry{ID: first.ID, ListType: 2, Content: "交作业（数学）", IsFinish: true})
	if err != nil || updated.Content != "交作业（数学）" || updated.ListType != 2 || !updated.IsFinish {
		t.Fatalf("the update: %+v %v", updated, err)
	}
	if len(s.TodosFor(7)) != 2 {
		t.Fatalf("an update must not add a row: %d", len(s.TodosFor(7)))
	}

	// The refusals: another owner's id is invisible, an unknown id errors, quadrant 0 and empty
	// content are refused.
	if _, err := s.PutTodo(8, TodoEntry{ID: first.ID, ListType: 1, Content: "x"}); err == nil {
		t.Fatalf("another owner's todo is not updatable")
	}
	if _, err := s.PutTodo(7, TodoEntry{ID: 9999, ListType: 1, Content: "x"}); err == nil {
		t.Fatalf("an unknown id errors instead of inserting")
	}
	if _, err := s.PutTodo(7, TodoEntry{ListType: 0, Content: "x"}); err == nil {
		t.Fatalf("quadrant 0 is outside the four quadrants")
	}
	if _, err := s.PutTodo(7, TodoEntry{ListType: 1, Content: "   "}); err == nil {
		t.Fatalf("an empty content is refused")
	}

	// The cap refuses new inserts (updates to existing rows still work at the cap): two rows are
	// already in, so exactly TodoCap-2 more fit before the refusal.
	for i := 0; i < TodoCap-2; i++ {
		if _, err := s.PutTodo(7, TodoEntry{ListType: 3, Content: "t"}); err != nil {
			t.Fatalf("the cap filled early at %d", i)
		}
	}
	if _, err := s.PutTodo(7, TodoEntry{ListType: 3, Content: "溢出"}); err == nil {
		t.Fatalf("the cap must refuse new inserts")
	}

	if !s.DeleteTodo(7, first.ID) {
		t.Fatalf("the owner deletes their row")
	}
	if s.DeleteTodo(7, first.ID) {
		t.Fatalf("a second delete answers false")
	}
}
