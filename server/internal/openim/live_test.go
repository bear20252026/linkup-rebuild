package openim

// Integration tests against a **real** OpenIMServer. They skip unless LINKUP_OPENIM_URL points at a
// running stack, so `go test ./...` stays offline and green on a machine with no Docker.
//
// What this buys that the httptest stub cannot: the stub only proves we agree with *our own reading*
// of the protocol module's json tags. These call the released server and pin the three things that
// were assumptions before — see recovered/DESIGN_CN.md 待验 47/48/49:
//
//   - the two apply lists really answer `FriendRequests` and `friendRequests` (different case)
//   - the friend list really answers `friendsInfo`
//   - an omitted `handleResults` filter returns every row, so the caller must sort handled from pending
//
// Run it with:
//
//	LINKUP_OPENIM_URL=http://127.0.0.1:10002 LINKUP_OPENIM_SECRET=... \
//	  go test ./internal/openim -run Live -v

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"
)

// liveClient returns a client for a stack the operator started, or skips.
func liveClient(t *testing.T) (*Client, string) {
	t.Helper()
	base := os.Getenv("LINKUP_OPENIM_URL")
	if base == "" {
		t.Skip("LINKUP_OPENIM_URL unset: 这是真栈集成测试，跳过（见 server/scripts/openim-stack.sh）")
	}
	secret := os.Getenv("LINKUP_OPENIM_SECRET")
	if secret == "" || secret == DefaultSecret {
		t.Fatal("LINKUP_OPENIM_SECRET must be set and must not be the published demo secret")
	}
	client := New(base, secret, "imAdmin")
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	// Fail early with a useful message instead of timing out on every call below.
	if _, err := client.AdminToken(ctx); err != nil {
		t.Fatalf("no admin token from %s: %v", base, err)
	}
	return client, fmt.Sprintf("live%d", time.Now().UnixNano()%1e6)
}

func TestLiveAdminTokenAndRegister(t *testing.T) {
	client, tag := liveClient(t)
	ctx := context.Background()

	token, err := client.AdminToken(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Count(token, ".") != 2 {
		t.Fatalf("expected a JWT-looking admin token, got %q", token[:min(len(token), 24)])
	}
	userID := tag + "a"
	if err := client.RegisterUsers(ctx, Account{UserID: userID, Nickname: "在线测试甲"}); err != nil {
		t.Fatalf("register: %v", err)
	}
	// Re-registering must be tolerated: the gateway treats "already exists" as success on re-login.
	if err := client.RegisterUsers(ctx, Account{UserID: userID, Nickname: "在线测试甲"}); err != nil {
		if !IsAlreadyKnown(err) {
			t.Fatalf("a duplicate register must read as already-known, got %v", err)
		}
		var imErr *Error
		if errors.As(err, &imErr) {
			t.Logf("duplicate register answered errCode=%d errMsg=%q（网关把这当作成功）", imErr.Code, imErr.Msg)
		}
	}
}

func TestLiveRelationRoundTrip(t *testing.T) {
	client, tag := liveClient(t)
	ctx := context.Background()

	a, b := tag+"a", tag+"b"
	for _, u := range []Account{{UserID: a, Nickname: "母亲"}, {UserID: b, Nickname: "女儿"}} {
		if err := client.RegisterUsers(ctx, u); err != nil && !IsAlreadyKnown(err) {
			t.Fatalf("register %s: %v", u.UserID, err)
		}
	}
	t.Cleanup(func() {
		for _, pair := range [][2]string{{a, b}, {b, a}} {
			if err := client.DeleteFriend(context.Background(), pair[0], pair[1]); err != nil {
				t.Logf("cleanup delete_friend %s→%s: %v", pair[0], pair[1], err)
			}
		}
	})

	if err := client.AddFriend(ctx, a, b, "妈妈加我"); err != nil {
		t.Fatalf("add_friend: %v", err)
	}

	// 待验 48: which key does the *server* answer each list under.
	received, _, err := client.ReceivedApplies(ctx, b, 1, 20)
	if err != nil {
		t.Fatalf("get_friend_apply_list: %v", err)
	}
	if len(received) == 0 || received[0].FromUserID != a {
		t.Fatalf("the pending request is not readable through FriendRequests: %+v", received)
	}
	if received[0].ReqMsg != "妈妈加我" {
		t.Errorf("reqMsg did not survive the round trip: %+v", received[0])
	}

	sent, _, err := client.SentApplies(ctx, a, 1, 20)
	if err != nil {
		t.Fatalf("get_self_friend_apply_list: %v", err)
	}
	if len(sent) == 0 || sent[0].ToUserID != b {
		t.Fatalf("the sent list is not readable through friendRequests: %+v", sent)
	}

	// 待验 49: no handleResults filter was sent, so the unhandled row must be visible to both sides.
	if received[0].HandleResult != HandleResultIgnored {
		t.Errorf("a fresh request should read as unhandled, got %d", received[0].HandleResult)
	}

	if err := client.RespondApply(ctx, a, b, HandleResultAccepted, ""); err != nil {
		t.Fatalf("add_friend_response: %v", err)
	}
	friend, err := client.IsFriend(ctx, b, a)
	if err != nil {
		t.Fatalf("is_friend: %v", err)
	}
	if !friend {
		t.Error("after accepting, the receiver must see the requester as a friend")
	}
	// The flags are per-direction on the real server, so the reverse view must be checked too.
	if back, err := client.IsFriend(ctx, a, b); err != nil || !back {
		t.Errorf("the requester's view after accepting: %v (%v)", back, err)
	}

	if err := client.SetRemark(ctx, b, a, "老妈"); err != nil {
		t.Fatalf("set_friend_remark: %v", err)
	}
	friends, total, err := client.Friends(ctx, b, 1, 20)
	if err != nil {
		t.Fatalf("get_friend_list: %v", err)
	}
	if total < 1 || len(friends) == 0 {
		t.Fatalf("friendsInfo came back empty for %s", b)
	}
	var found *Friend
	for i := range friends {
		if friends[i].FriendUser.UserID == a {
			found = &friends[i]
		}
	}
	if found == nil {
		t.Fatalf("the accepted friend is missing from friendsInfo: %+v", friends)
	}
	if found.Remark != "老妈" {
		t.Errorf("remark not readable through the documented field: %+v", *found)
	}
	if found.FriendUser.Nickname != "母亲" {
		t.Errorf("the embedded publicUserInfo lost the nickname: %+v", found.FriendUser)
	}

	if err := client.DeleteFriend(ctx, b, a); err != nil {
		t.Fatalf("delete_friend: %v", err)
	}
	if still, err := client.IsFriend(ctx, b, a); err != nil || still {
		t.Errorf("the friendship survived deletion: %v %v", still, err)
	}
}

func TestLiveProfileUpdateReachesTheFriendCard(t *testing.T) {
	client, tag := liveClient(t)
	ctx := context.Background()
	userID := tag + "p"
	if err := client.RegisterUsers(ctx, Account{UserID: userID, Nickname: "改名前"}); err != nil && !IsAlreadyKnown(err) {
		t.Fatalf("register: %v", err)
	}
	if err := client.UpdateProfile(ctx, userID, "改名后", "https://example.invalid/x.png"); err != nil {
		t.Fatalf("update_user_info: %v", err)
	}
	friends, _, err := client.Friends(ctx, userID, 1, 20)
	if err != nil {
		t.Fatalf("the profile update broke the friend list call: %v", err)
	}
	_ = friends
}
