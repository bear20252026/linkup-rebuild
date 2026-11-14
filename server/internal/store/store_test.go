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
