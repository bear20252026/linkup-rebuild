package store

import (
	"path/filepath"
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

func TestAccountsIdsAndMatchCodes(t *testing.T) {
	s := mustOpen(t, "")
	phones := make([]string, 0, 50)
	for i := 0; i < 50; i++ {
		phones = append(phones, IMID(int64(13800000200+i)))
	}
	byCode := map[int64]int64{}
	ids := map[int64]bool{}
	for _, phone := range phones {
		u, err := s.CreateAccount(phone)
		if err != nil {
			t.Fatal(err)
		}
		if _, taken := byCode[u.MatchCode]; taken {
			t.Fatalf("match code %d was issued twice", u.MatchCode)
		}
		byCode[u.MatchCode] = u.ID
		ids[u.ID] = true
	}
	// The add-friend field also accepts an account id, so no code may double as one.
	for code := range byCode {
		if ids[code] {
			t.Fatalf("match code %d collides with an account id", code)
		}
	}

	first := s.Accounts()[0]
	again, err := s.CreateAccount(first.Phone)
	if err != nil {
		t.Fatal(err)
	}
	if again.ID != first.ID || again.MatchCode != first.MatchCode {
		t.Fatalf("re-login minted a second identity: %+v vs %+v", again, first)
	}
	if err := s.RecordDevice(first.ID, "cid-1", "apns-1"); err != nil {
		t.Fatal(err)
	}
	reloaded, err := s.ByPhone(first.Phone)
	if err != nil || reloaded.GetuiCid != "cid-1" || reloaded.PushToken != "apns-1" {
		t.Fatalf("device record lost: %+v %v", reloaded, err)
	}
	if u, err := s.ByMatchCode(first.MatchCode); err != nil || u.ID != first.ID {
		t.Fatalf("the match code must resolve: %+v %v", u, err)
	}
	if u, err := s.ByMatchCode(first.ID); err != nil || u.ID != first.ID {
		t.Fatalf("an id typed into the same field must resolve: %+v %v", u, err)
	}
	if _, err := s.ByMatchCode(99999999); err != ErrNotFound {
		t.Errorf("unknown match code should be ErrNotFound, got %v", err)
	}
	if _, err := s.ByID(424242); err != ErrNotFound {
		t.Errorf("unknown id should be ErrNotFound, got %v", err)
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

	second := mustOpen(t, path)
	reloaded, err := second.ByPhone("13800000400")
	if err != nil {
		t.Fatalf("nothing survived the restart: %v", err)
	}
	if reloaded.ID != u.ID || reloaded.MatchCode != u.MatchCode {
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

func TestThirdPartyAccountsBindWithoutDoublingUp(t *testing.T) {
	s := mustOpen(t, "")

	// A WeChat login with no phone yet: a phone-less account keyed by the union id.
	anon, err := s.ThirdAccount(4, "wx-union-1", "")
	if err != nil {
		t.Fatal(err)
	}
	again, err := s.ThirdAccount(4, "wx-union-1", "")
	if err != nil {
		t.Fatal(err)
	}
	if again.ID != anon.ID {
		t.Fatalf("the same union id minted a second account: %d vs %d", again.ID, anon.ID)
	}

	// The same identity arriving with a phone binds it, and does not move the account.
	bound, err := s.ThirdAccount(4, "wx-union-1", "13800000500")
	if err != nil {
		t.Fatal(err)
	}
	if bound.ID != anon.ID || bound.Phone != "13800000500" {
		t.Fatalf("binding changed the account instead of the phone: %+v", bound)
	}

	// A phone that already has an account adopts the new union id rather than cloning the person.
	qq, err := s.ThirdAccount(5, "wx-union-2", "13800000500")
	if err != nil {
		t.Fatal(err)
	}
	if qq.ID != bound.ID {
		t.Fatalf("the same phone became a second account: %d vs %d", qq.ID, bound.ID)
	}
	if got, err := s.ByID(anon.ID); err != nil || got.UnionID != "wx-union-1" || got.Platform != 4 {
		t.Fatalf("the first binding was lost: %+v %v", got, err)
	}
	if n := len(s.Accounts()); n != 1 {
		t.Fatalf("one person, %d accounts", n)
	}

	// A vendor identity with no phone at all gets its own account.
	other, err := s.ThirdAccount(5, "wx-union-3", "")
	if err != nil {
		t.Fatal(err)
	}
	if other.ID == anon.ID || other.Platform != 5 {
		t.Fatalf("a different vendor identity reused the WeChat account: %+v", other)
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
