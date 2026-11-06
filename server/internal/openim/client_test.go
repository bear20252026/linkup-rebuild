package openim

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// stubIM answers with the envelope and field names OpenIM's own route table and protocol module use,
// so a change on our side that breaks the contract fails here instead of in a manual test.
type stubIM struct {
	mu      sync.Mutex
	seen    []string
	bodies  map[string]map[string]any
	tokens  int
	failure map[string]int // path -> errCode to answer with
}

func newStub(t *testing.T) (*stubIM, *Client) {
	t.Helper()
	s := &stubIM{bodies: map[string]map[string]any{}, failure: map[string]int{}}
	srv := httptest.NewServer(http.HandlerFunc(s.handle))
	t.Cleanup(srv.Close)
	return s, New(srv.URL, "unit-secret", "imAdmin")
}

func (s *stubIM) handle(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	var parsed map[string]any
	_ = json.Unmarshal(body, &parsed)

	s.mu.Lock()
	defer s.mu.Unlock()
	s.seen = append(s.seen, r.URL.Path)
	s.bodies[r.URL.Path] = parsed

	if token := r.Header.Get("token"); token == "" && r.URL.Path != "/auth/get_admin_token" {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"errCode":1500,"errMsg":"token invalid"}`)
		return
	}
	if code, ok := s.failure[r.URL.Path]; ok {
		_, _ = io.WriteString(w, `{"errCode":`+itoa(code)+`,"errMsg":"boom","errDlt":"stub"}`)
		return
	}

	var data string
	switch r.URL.Path {
	case "/auth/get_admin_token":
		s.tokens++
		data = `{"token":"admin-token-` + itoa(s.tokens) + `","expireTimeSeconds":7200}`
	case "/auth/get_user_token":
		data = `{"token":"user-token","expireTimeSeconds":7200}`
	case "/friend/get_friend_list":
		data = `{"friendsInfo":[{"ownerUserID":"10001","remark":"老妈","createTime":1700000000,
			"friendUser":{"userID":"10002","nickname":"女儿","faceURL":"https://x/a.png","ex":""}}],"total":1}`
	case "/friend/is_friend":
		// v3.8.3-patch.18 answers two flags, not one: each side's view is separate.
		data = `{"inUser1Friends":true,"inUser2Friends":false}`
	case "/friend/get_friend_apply_list":
		data = `{"FriendRequests":[{"fromUserID":"10002","fromNickname":"女儿","fromFaceURL":"https://x/a.png",
			"toUserID":"10001","handleResult":0,"reqMsg":"加一下","createTime":1700000000}],"total":1}`
	case "/friend/get_self_friend_apply_list":
		// The protocol tags this response's key lower-case, unlike the list above. Both spellings are
		// pinned so neither list can silently decode to empty.
		data = `{"friendRequests":[{"fromUserID":"10001","fromNickname":"老妈","fromFaceURL":"https://x/b.png",
			"toUserID":"10002","handleResult":0,"reqMsg":"加一下","createTime":1700000000}],"total":1}`
	default:
		data = `{}`
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = io.WriteString(w, `{"errCode":0,"errMsg":"","data":`+data+`}`)
}

func (s *stubIM) calls() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.seen...)
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var b []byte
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	if neg {
		return "-" + string(b)
	}
	return string(b)
}

func TestAdminTokenIsFetchedOnceAndReused(t *testing.T) {
	stub, client := newStub(t)
	ctx := context.Background()

	for i := 0; i < 3; i++ {
		if _, err := client.AdminToken(ctx); err != nil {
			t.Fatalf("AdminToken: %v", err)
		}
	}
	if got := countPath(stub.calls(), "/auth/get_admin_token"); got != 1 {
		t.Errorf("token should be cached, fetched %d times", got)
	}
	// The secret and the admin id are what the endpoint asks for, by name.
	req := stub.bodies["/auth/get_admin_token"]
	if req["secret"] != "unit-secret" {
		t.Errorf("secret field: %#v", req["secret"])
	}
	// A real v3.8.3-patch.18 server answers an array here with
	// "cannot unmarshal array into Go struct field GetAdminTokenReq.userID of type string",
	// so the scalar shape is pinned deliberately.
	if req["userID"] != "imAdmin" {
		t.Errorf("userID should be the admin id as a string, got %#v", req["userID"])
	}
}

func TestRelationCallsUseTheDocumentedPathsAndFields(t *testing.T) {
	stub, client := newStub(t)
	ctx := context.Background()

	if err := client.RegisterUsers(ctx, Account{UserID: "10001", Nickname: "老妈", FaceURL: "https://x/b.png"}); err != nil {
		t.Fatal(err)
	}
	if err := client.AddFriend(ctx, "10001", "10002", "请求添加你为好友"); err != nil {
		t.Fatal(err)
	}
	if err := client.RespondApply(ctx, "10002", "10001", HandleResultAccepted, ""); err != nil {
		t.Fatal(err)
	}
	if err := client.SetRemark(ctx, "10001", "10002", "女儿"); err != nil {
		t.Fatal(err)
	}
	if err := client.DeleteFriend(ctx, "10001", "10002"); err != nil {
		t.Fatal(err)
	}
	if _, err := client.IsFriend(ctx, "10001", "10002"); err != nil {
		t.Fatal(err)
	}

	want := []string{
		"/auth/get_admin_token", // once, then cached
		"/user/user_register",
		"/friend/add_friend",
		"/friend/add_friend_response",
		"/friend/set_friend_remark",
		"/friend/delete_friend",
		"/friend/is_friend",
	}
	got := stub.calls()
	if len(got) != len(want) {
		t.Fatalf("call sequence changed:\n got %v\nwant %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("call %d: got %s want %s", i, got[i], want[i])
		}
	}

	add := stub.bodies["/friend/add_friend"]
	if add["fromUserID"] != "10001" || add["toUserID"] != "10002" {
		t.Errorf("add_friend fields: %#v", add)
	}
	respond := stub.bodies["/friend/add_friend_response"]
	if v, _ := respond["handleResult"].(float64); v != HandleResultAccepted {
		t.Errorf("handleResult should be %d, got %#v", HandleResultAccepted, respond["handleResult"])
	}
	// is_friend takes userID1/userID2 — measured: the ownerUserID spelling gets
	// `ArgsError: userID1 is empty` from the real server.
	isFriend := stub.bodies["/friend/is_friend"]
	if isFriend["userID1"] != "10001" || isFriend["userID2"] != "10002" {
		t.Errorf("is_friend fields should be userID1/userID2, got %#v", isFriend)
	}
	if _, leaked := isFriend["ownerUserID"]; leaked {
		t.Errorf("is_friend must not send ownerUserID: %#v", isFriend)
	}
	remark := stub.bodies["/friend/set_friend_remark"]
	if remark["ownerUserID"] != "10001" || remark["friendUserID"] != "10002" || remark["remark"] != "女儿" {
		t.Errorf("set_friend_remark fields: %#v", remark)
	}
}

func TestFriendAndApplyListsDecodeTheProtocolFieldNames(t *testing.T) {
	_, client := newStub(t)
	ctx := context.Background()

	friends, total, err := client.Friends(ctx, "10001", 1, 20)
	if err != nil {
		t.Fatal(err)
	}
	if total != 1 || len(friends) != 1 {
		t.Fatalf("unexpected list: total=%d friends=%d", total, len(friends))
	}
	if friends[0].Remark != "老妈" || friends[0].FriendUser.UserID != "10002" {
		t.Errorf("friendsInfo decoding: %+v", friends[0])
	}

	applies, _, err := client.ReceivedApplies(ctx, "10001", 1, 20)
	if err != nil {
		t.Fatal(err)
	}
	if len(applies) != 1 || applies[0].FromUserID != "10002" || applies[0].HandleResult != HandleResultIgnored {
		t.Errorf("FriendRequests decoding: %+v", applies)
	}

	// get_self_friend_apply_list answers with the same rows under a lower-case key.
	sent, _, err := client.SentApplies(ctx, "10001", 1, 20)
	if err != nil {
		t.Fatal(err)
	}
	if len(sent) != 1 || sent[0].FromUserID != "10001" || sent[0].ToUserID != "10002" {
		t.Errorf("friendRequests (lower-case key) decoding: %+v", sent)
	}

	// Pagination has to be nested, not flattened: the server validates `pagination.pageNumber`.
	paginated, _ := json.Marshal(pagination(2, 50))
	if !strings.Contains(string(paginated), `"pageNumber":2`) || !strings.Contains(string(paginated), `"showNumber":50`) {
		t.Errorf("pagination shape wrong: %s", paginated)
	}
}

func TestIsFriendReadsTheOwnersDirection(t *testing.T) {
	_, client := newStub(t)
	got, err := client.IsFriend(context.Background(), "10001", "10002")
	if err != nil {
		t.Fatal(err)
	}
	if !got {
		t.Error("inUser1Friends=true must read as friend for the owner")
	}
}

func TestErrCodeBecomesAnErrorTheGatewayCanMap(t *testing.T) {
	stub, client := newStub(t)
	stub.failure["/friend/add_friend"] = 3102

	err := client.AddFriend(context.Background(), "1", "2", "hi")
	var imErr *Error
	if !errors.As(err, &imErr) {
		t.Fatalf("expected *openim.Error, got %T: %v", err, err)
	}
	if imErr.Code != 3102 || imErr.Path != "/friend/add_friend" {
		t.Errorf("error detail lost: %+v", imErr)
	}
}

func TestUnreachableServerIsADistinctFailure(t *testing.T) {
	// No stub: a client pointed at a dead address must be distinguishable from "IM refused".
	client := New("http://127.0.0.1:1", "s", "a")
	err := client.AddFriend(context.Background(), "1", "2", "x")
	if err == nil {
		t.Fatal("expected an error")
	}
	if _, isIMError := err.(*Error); isIMError {
		t.Errorf("a connection failure must not look like a refused call: %v", err)
	}
	if !strings.Contains(err.Error(), "unreachable") {
		t.Errorf("error should say the service is unreachable, got %v", err)
	}
}

func TestEveryCallCarriesAnOperationID(t *testing.T) {
	stub, client := newStub(t)
	if _, err := client.IsFriend(context.Background(), "1", "2"); err != nil {
		t.Fatal(err)
	}
	if text, ok := stub.bodies["/friend/is_friend"]["operationID"].(string); !ok || text == "" {
		t.Error("operationID missing from the body")
	}
}

func countPath(calls []string, path string) int {
	n := 0
	for _, c := range calls {
		if c == path {
			n++
		}
	}
	return n
}

func TestAlreadyKnownMatchesTheMeasuredServerAnswer(t *testing.T) {
	// v3.8.3-patch.18 answers a duplicate register with exactly this envelope.
	stub := &stubIM{bodies: map[string]map[string]any{}, failure: map[string]int{}}
	stub.failure["/user/user_register"] = ErrRegisteredAlready
	srv := httptest.NewServer(http.HandlerFunc(stub.handle))
	defer srv.Close()

	err := New(srv.URL, "unit-secret", "imAdmin").RegisterUsers(context.Background(), Account{UserID: "10001"})
	if !IsAlreadyKnown(err) {
		t.Fatalf("errCode %d must read as already-registered, got %v", ErrRegisteredAlready, err)
	}
	// A different refusal must not be swallowed by the same predicate.
	stub.failure["/user/user_register"] = 1500
	if IsAlreadyKnown(New(srv.URL, "unit-secret", "imAdmin").RegisterUsers(context.Background(), Account{UserID: "10002"})) {
		t.Fatal("an unrelated errCode must not read as already-registered")
	}
}
