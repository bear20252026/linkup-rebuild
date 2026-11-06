package gateway

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"linkup.local/server/internal/contract"
	"linkup.local/server/internal/openim"
	"linkup.local/server/internal/store"
)

const (
	testJWT     = "unit-test-jwt-secret"
	testImSk    = "unit-im-secret"
	devCode     = "000000"
	friendMax   = 2
	cutNoVendor = "9"
)

// stubIM is the slice of OpenIM the gateway touches. It answers with the field names from the
// protocol module and records what it was asked, so both directions of the contract are asserted.
type stubIM struct {
	mu    sync.Mutex
	calls []stubCall
}

type stubCall struct {
	path string
	body map[string]any
}

func (s *stubIM) record(path string) []map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []map[string]any
	for _, c := range s.calls {
		if c.path == path {
			out = append(out, c.body)
		}
	}
	return out
}

func (s *stubIM) handle(w http.ResponseWriter, r *http.Request) {
	raw, _ := io.ReadAll(r.Body)
	var body map[string]any
	_ = json.Unmarshal(raw, &body)

	s.mu.Lock()
	s.calls = append(s.calls, stubCall{path: r.URL.Path, body: body})
	s.mu.Unlock()

	data := "{}"
	switch r.URL.Path {
	case "/auth/get_admin_token":
		data = `{"token":"t","expireTimeSeconds":3600}`
	case "/friend/get_friend_list":
		data = `{"friendsInfo":[{"ownerUserID":"1","remark":"","createTime":1700000000,` +
			`"friendUser":{"userID":"2","nickname":"女儿","faceURL":"http://a/2.png","ex":""}}],"total":1}`
	case "/friend/get_friend_apply_list":
		data = `{"FriendRequests":[{"fromUserID":"2","fromNickname":"女儿","toUserID":"1",` +
			`"handleResult":0,"reqMsg":"妈加我","createTime":1700000000}],"total":1}`
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = io.WriteString(w, `{"errCode":0,"errMsg":"","data":`+data+`}`)
}

type harness struct {
	engine http.Handler
	im     *stubIM
	store  *store.Store
	zone   *time.Location
}

// newTest builds a gateway over a stub OpenIM. Mutators let a case change the boot options the way
// an operator would — the track thresholds and the day timezone are flags, so they must be testable
// as flags rather than as constants.
func newTest(t *testing.T, mutators ...func(*Options)) *harness {
	t.Helper()
	db, err := store.Open("")
	if err != nil {
		t.Fatal(err)
	}
	stub := &stubIM{}
	server := httptest.NewServer(http.HandlerFunc(stub.handle))
	t.Cleanup(server.Close)

	key, err := contract.GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	gin.SetMode(gin.TestMode)
	opts := Options{
		Store:        db,
		IM:           openim.New(server.URL, testImSk, "imAdmin"),
		JWTSecret:    []byte(testJWT),
		PublicKey:    key,
		TokenTTL:     time.Hour,
		Logger:       slog.New(slog.NewTextHandler(io.Discard, nil)),
		DevSMSCode:   devCode,
		FriendLimit:  friendMax,
		CutLoginType: cutNoVendor,
		DayZone:      time.UTC,
	}
	for _, mutate := range mutators {
		mutate(&opts)
	}
	engine, err := New(opts)
	if err != nil {
		t.Fatal(err)
	}
	return &harness{engine: engine, im: stub, store: db, zone: opts.DayZone}
}

type envelope struct {
	Status int             `json:"status"`
	Msg    string          `json:"msg"`
	Data   json.RawMessage `json:"data"`
}

// request signs and sends one call exactly the way the recovered client does: the 11 common query
// parameters are already baked into the query string by the app, so the test supplies the path/query
// it wants signed, and the signature is computed over that same text.
func (h *harness) request(t *testing.T, method, path, query string, body map[string]any, token string) envelope {
	t.Helper()
	var raw []byte
	if body != nil {
		var err error
		raw, err = json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
	}
	target := "https://api.example" + path
	if query != "" {
		target += "?" + query
	}
	req, err := http.NewRequest(method, target, bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", token)
	}
	if clientSigns(path) {
		sig, err := contract.Sign(contract.SignatureInput(req.URL.Path, req.URL.RawQuery, method, raw))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Signature", sig)
	}

	rec := httptest.NewRecorder()
	h.engine.ServeHTTP(rec, req)

	var answered envelope
	if err := json.Unmarshal(rec.Body.Bytes(), &answered); err != nil {
		t.Fatalf("%s answered outside the client's envelope: HTTP %d %s", path, rec.Code, rec.Body.String())
	}
	return answered
}

func decode[T any](t *testing.T, raw json.RawMessage) T {
	t.Helper()
	var out T
	if err := json.Unmarshal(raw, &out); err != nil {
		t.Fatalf("decode %s: %v", raw, err)
	}
	return out
}

// login runs the SMS path end to end and returns the account token, id and record.
func (h *harness) login(t *testing.T, phone string) (string, store.User) {
	t.Helper()
	if answered := h.request(t, http.MethodGet, "/api/sms/send", "phone="+phone+"&region=%2B86", nil, ""); answered.Status != 200 {
		t.Fatalf("send code: %+v", answered)
	}
	answered := h.request(t, http.MethodPost, "/api/sms/click_to_login", "", map[string]any{
		"typeId": "2", "phone": phone, "smsCode": devCode, "region": "+86",
	}, "")
	if answered.Status != 200 {
		t.Fatalf("login: %+v %s", answered, answered.Data)
	}
	var u struct {
		UserID int64  `json:"userId"`
		Token  string `json:"token"`
	}
	if err := json.Unmarshal(answered.Data, &u); err != nil {
		t.Fatal(err)
	}
	if u.Token == "" || u.UserID == 0 {
		t.Fatalf("login answer lacked a token or id: %s", answered.Data)
	}
	record, err := h.store.ByID(u.UserID)
	if err != nil {
		t.Fatal(err)
	}
	return u.Token, record
}

func TestUnsignedRequestToASignedRouteIsRefusedLikeTheTestServer(t *testing.T) {
	h := newTest(t)
	req, _ := http.NewRequest(http.MethodGet, "https://api.example/api/relation/list?page=1", nil)
	rec := httptest.NewRecorder()
	h.engine.ServeHTTP(rec, req)

	if !strings.Contains(rec.Body.String(), `"status":1001`) || !strings.Contains(rec.Body.String(), "参数异常") {
		t.Fatalf("expected the recovered refusal, got %s", rec.Body.String())
	}
}

func TestSignatureDoesNotSurviveAChangedQuery(t *testing.T) {
	h := newTest(t)
	token, _ := h.login(t, "13800000001")

	if answered := h.request(t, http.MethodGet, "/api/user/info", "x=1", nil, token); answered.Status != 200 {
		t.Fatalf("the signed call should pass: %+v", answered)
	}
	// Reuse that signature on another query string, as a replay would.
	req, _ := http.NewRequest(http.MethodGet, "https://api.example/api/user/info?x=2", nil)
	req.Header.Set("Authorization", token)
	req.Header.Set("Signature", mustSign(t, "/api/user/info", "x=1", http.MethodGet, nil))
	rec := httptest.NewRecorder()
	h.engine.ServeHTTP(rec, req)
	if !strings.Contains(rec.Body.String(), `"status":1001`) {
		t.Errorf("a replayed signature must fail, got %s", rec.Body.String())
	}
}

func TestMissingTokenIsA401TheClientTreatsAsTokenFail(t *testing.T) {
	h := newTest(t)
	req, _ := http.NewRequest(http.MethodGet, "https://api.example/api/tool/rsa/public_key", nil)
	req.Header.Set("Signature", mustSign(t, "/api/tool/rsa/public_key", "", http.MethodGet, nil))
	rec := httptest.NewRecorder()
	h.engine.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected HTTP 401, got %d %s", rec.Code, rec.Body.String())
	}
}

func TestLoginThenInfoAgreeOnIdentity(t *testing.T) {
	h := newTest(t)
	token, record := h.login(t, "13800000002")

	answered := h.request(t, http.MethodGet, "/api/user/info", "", nil, token)
	if answered.Status != 200 {
		t.Fatalf("user/info: %+v", answered)
	}
	u := decode[struct {
		UserID     int64  `json:"userId"`
		MainUser   int64  `json:"mainUser"`
		Phone      string `json:"phone"`
		IsRegister bool   `json:"isRegister"`
		Token      string `json:"token"`
	}](t, answered.Data)

	if u.UserID != record.ID || u.MainUser != record.MainUserID || u.Phone != "13800000002" || !u.IsRegister {
		t.Errorf("the cached identity and the answer disagree: %+v vs %+v", u, record)
	}
	if u.Token != "" {
		t.Error("user/info must not re-issue a token; the client would overwrite a live session")
	}
}

func TestWrongVerificationCodeIsRefused(t *testing.T) {
	h := newTest(t)
	if _, err := h.store.IssueCode("13800000003", time.Minute); err != nil {
		t.Fatal(err)
	}
	answered := h.request(t, http.MethodPost, "/api/sms/click_to_login", "", map[string]any{
		"typeId": "2", "phone": "13800000003", "smsCode": "9999", "region": "+86",
	}, "")
	if answered.Status == 200 {
		t.Fatalf("a wrong code logged somebody in: %s", answered.Data)
	}
	if !strings.Contains(answered.Msg, "验证码") {
		t.Errorf("the client toasts msg verbatim, expected a code complaint, got %q", answered.Msg)
	}
}

func TestOneTapLoginIsRefusedRatherThanFaked(t *testing.T) {
	h := newTest(t)
	answered := h.request(t, http.MethodPost, "/api/sms/click_to_login", "", map[string]any{
		"typeId": "1", "phone": "13800000004", "token": "vendor-token", "gyuId": "gy",
	}, "")
	if answered.Status == 200 {
		t.Fatal("typeId 1 cannot be honoured without the vendor SDK, and must not hand out a session")
	}
}

// TestThirdPartyLoginKeysByUnionIdAndBindsThePhone covers `api/user/login`, the WeChat (platform "4")
// and QQ (platform "5") path. The client sends the phone only when the user typed it to bind, so the
// same person must end up on one account whichever of the three calls brings them in.
func TestThirdPartyLoginKeysByUnionIdAndBindsThePhone(t *testing.T) {
	h := newTest(t)
	body := func(phone any) map[string]any {
		return map[string]any{
			"unionId": "wx-union-77", "platform": "4",
			"avatar": "https://x/a.png", "nickname": "微信用户", "phone": phone,
		}
	}

	first := h.request(t, http.MethodPost, "/api/user/login", "", body(nil), "")
	if first.Status != 200 {
		t.Fatalf("wechat login: %+v", first)
	}
	anon := decode[struct {
		UserID   int64  `json:"userId"`
		Nickname string `json:"nickname"`
		Avatar   string `json:"avatar"`
		Token    string `json:"token"`
	}](t, first.Data)
	if anon.UserID == 0 || anon.Nickname != "微信用户" || anon.Token == "" {
		t.Fatalf("login answer wrong: %+v", anon)
	}

	again := h.request(t, http.MethodPost, "/api/user/login", "", body(nil), "")
	if back := decode[struct {
		UserID int64 `json:"userId"`
	}](t, again.Data); back.UserID != anon.UserID {
		t.Fatalf("the same union id minted a second account: %d vs %d", back.UserID, anon.UserID)
	}

	bound := h.request(t, http.MethodPost, "/api/user/login", "", body("13800000077"), "")
	after := decode[struct {
		UserID int64  `json:"userId"`
		Phone  string `json:"phone"`
	}](t, bound.Data)
	if after.UserID != anon.UserID || after.Phone != "13800000077" {
		t.Fatalf("binding moved the account or lost the phone: %+v", after)
	}

	// The number that was just bound has to log into the *same* account by SMS.
	token, record := h.login(t, "13800000077")
	if record.ID != anon.UserID {
		t.Fatalf("the bound phone opened a different account: %d vs %d", record.ID, anon.UserID)
	}
	info := h.request(t, http.MethodGet, "/api/user/info", "", nil, token)
	if got := decode[struct {
		Nickname string `json:"nickname"`
	}](t, info.Data); got.Nickname != "微信用户" {
		t.Errorf("the WeChat nickname did not survive the phone login: %q", got.Nickname)
	}
}

// TestUpdateInfoRoundTripsStage pins the fourth key of `api/user/update/info`. The client keeps
// `user.stage` and reopens the profile wizard at that step, so a server that drops it silently sends
// every half-finished profile back to the beginning.
func TestUpdateInfoRoundTripsStage(t *testing.T) {
	h := newTest(t)
	token, record := h.login(t, "13800000078")

	answered := h.request(t, http.MethodPost, "/api/user/update/info", "", map[string]any{
		"avatar": "https://x/me.png", "nickname": "妈妈", "sex": 2, "stage": 3,
	}, token)
	if answered.Status != 200 {
		t.Fatalf("update/info: %+v", answered)
	}
	updated := decode[struct {
		UserID   int64  `json:"userId"`
		Nickname string `json:"nickname"`
		Avatar   string `json:"avatar"`
		Sex      int    `json:"sex"`
		Stage    int    `json:"stage"`
	}](t, answered.Data)
	if updated.UserID != record.ID || updated.Nickname != "妈妈" || updated.Avatar != "https://x/me.png" ||
		updated.Sex != 2 || updated.Stage != 3 {
		t.Fatalf("profile answer wrong: %+v", updated)
	}

	// A later call that omits the keys must not reset them — the client sends only what it changed.
	partial := h.request(t, http.MethodPost, "/api/user/update/info", "", map[string]any{"nickname": "老妈"}, token)
	kept := decode[struct {
		Nickname string `json:"nickname"`
		Avatar   string `json:"avatar"`
		Sex      int    `json:"sex"`
		Stage    int    `json:"stage"`
	}](t, partial.Data)
	if kept.Nickname != "老妈" || kept.Avatar != "https://x/me.png" || kept.Sex != 2 || kept.Stage != 3 {
		t.Fatalf("an absent field erased stored state: %+v", kept)
	}
	if stored, err := h.store.ByID(record.ID); err != nil || stored.Stage != 3 {
		t.Fatalf("stage never reached the store: %+v %v", stored, err)
	}
	// And the IM side is told about the new profile card, not just the local record.
	if calls := h.im.record("/user/update_user_info"); len(calls) != 2 {
		t.Errorf("each profile write should reach openim, got %d calls", len(calls))
	}
}

func TestUserIdIsTheMatchCodeAndSelfAddIsBlocked(t *testing.T) {
	h := newTest(t)
	token, me := h.login(t, "13800000005")
	_, other := h.login(t, "13800000006")

	found := h.request(t, http.MethodGet, "/api/relation/pre_confirm", "code="+idOf(other.ID), nil, token)
	if found.Status != 200 {
		t.Fatalf("pre_confirm: %+v", found)
	}
	friend := decode[struct {
		UserID        int64  `json:"userId"`
		Nickname      string `json:"nickname"`
		IsFriendLimit bool   `json:"isFriendLimit"`
	}](t, found.Data)
	if friend.UserID != other.ID || friend.Nickname == "" {
		t.Errorf("match code resolved wrong: %+v", friend)
	}

	if self := h.request(t, http.MethodGet, "/api/relation/pre_confirm", "code="+idOf(me.ID), nil, token); self.Status == 200 {
		t.Error("adding yourself must not be offered")
	}
	// A code is an id, so an unissued 8-digit one must not resolve.
	if missing := h.request(t, http.MethodGet, "/api/relation/pre_confirm", "code=99999999", nil, token); missing.Status == 200 {
		t.Error("an unknown match code must not resolve")
	}
}

func TestApplyAndConfirmCrossTheOpenIMBoundary(t *testing.T) {
	h := newTest(t)
	tokenA, a := h.login(t, "13800000007")
	tokenB, b := h.login(t, "13800000008")

	applied := h.request(t, http.MethodPost, "/api/relation/apply", "", map[string]any{
		"code": b.ID, "relation": 1,
		"sensSetting": map[string]any{"sensLocation": true, "sensAppUse": false},
	}, tokenA)
	if applied.Status != 200 {
		t.Fatalf("apply: %+v", applied)
	}
	calls := h.im.record("/friend/add_friend")
	if len(calls) != 1 {
		t.Fatalf("expected one add_friend call, got %d", len(calls))
	}
	if calls[0]["fromUserID"] != idOf(a.ID) || calls[0]["toUserID"] != idOf(b.ID) {
		t.Errorf("add_friend carried the wrong identities: %#v", calls[0])
	}

	if list := h.request(t, http.MethodGet, "/api/relation/apply_list", "page=1&pageSize=20", nil, tokenB); list.Status != 200 {
		t.Fatalf("apply_list: %+v", list)
	} else if len(list.Data) == 0 {
		t.Error("apply_list answered without data")
	}

	// The recovered declaration is `@Query("code") code: Long` plus a body that repeats it; a confirm
	// without the query parameter is the one shape the shipped client cannot produce.
	if noQuery := h.request(t, http.MethodPost, "/api/relation/confirm", "", map[string]any{"code": a.ID}, tokenB); noQuery.Status != contract.StatusParamErr {
		t.Errorf("confirm takes the requester from the query, got %+v", noQuery)
	}

	confirmed := h.request(t, http.MethodPost, "/api/relation/confirm", "code="+idOf(a.ID), map[string]any{
		"code": a.ID, "relation": 2,
		"sensSetting": map[string]any{"sensLocation": true, "sensCall": true, "sensAppUse": true},
	}, tokenB)
	if confirmed.Status != 200 {
		t.Fatalf("confirm: %+v", confirmed)
	}
	handled := h.im.record("/friend/add_friend_response")
	if len(handled) != 1 {
		t.Fatalf("expected one add_friend_response, got %d", len(handled))
	}
	if result, _ := handled[0]["handleResult"].(float64); result != openim.HandleResultAccepted {
		t.Errorf("confirming has to accept the request, got %#v", handled[0]["handleResult"])
	}

	// Each direction keeps the choices its own side submitted; OpenIM only knows the friendship.
	if extra := h.store.ExtraFor(b.ID, a.ID); extra.Relation != 2 || !extra.SensLoc || !extra.SensCall || !extra.SensAll {
		t.Errorf("the confirmer's settings were lost: %+v", extra)
	}
	if extra := h.store.ExtraFor(a.ID, b.ID); extra.Relation != 1 || !extra.SensLoc || extra.SensAll || extra.SensCall {
		t.Errorf("the requester's own apply choices were overwritten: %+v", extra)
	}
	if _, pending := h.store.ApplyFor(a.ID, b.ID); pending {
		t.Error("the answered request is still recorded as pending")
	}
}

func TestRemarkRelationAndDelete(t *testing.T) {
	h := newTest(t)
	token, me := h.login(t, "13800000009")
	_, other := h.login(t, "13800000010")
	if err := h.store.PutExtra(store.Extra{OwnerID: me.ID, FriendID: other.ID, Relation: 3, Remark: "旧"}); err != nil {
		t.Fatal(err)
	}

	if answered := h.request(t, http.MethodPost, "/api/relation/update_remark", "", map[string]any{
		"friendId": other.ID, "remark": "妹妹",
	}, token); answered.Status != 200 {
		t.Fatalf("update_remark: %+v", answered)
	}
	remarked := h.im.record("/friend/set_friend_remark")
	if len(remarked) != 1 || remarked[0]["remark"] != "妹妹" || remarked[0]["ownerUserID"] != idOf(me.ID) {
		t.Errorf("the remark never reached openim: %#v", remarked)
	}
	if extra := h.store.ExtraFor(me.ID, other.ID); extra.Remark != "妹妹" || extra.Relation != 3 {
		t.Errorf("local remark/relation drifted: %+v", extra)
	}

	if answered := h.request(t, http.MethodPost, "/api/relation/update", "", map[string]any{
		"friendId": other.ID, "relation": 1,
	}, token); answered.Status != 200 {
		t.Fatalf("update: %+v", answered)
	}
	if extra := h.store.ExtraFor(me.ID, other.ID); extra.Relation != 1 {
		t.Errorf("relation not updated: %+v", extra)
	}

	if answered := h.request(t, http.MethodPost, "/api/relation/del", "", map[string]any{"friendId": other.ID}, token); answered.Status != 200 {
		t.Fatalf("del: %+v", answered)
	}
	deled := h.im.record("/friend/delete_friend")
	if len(deled) != 2 {
		t.Fatalf("deletion must clear both directions, got %d IM calls: %#v", len(deled), deled)
	}
	seen := map[string]bool{}
	for _, call := range deled {
		seen[fmt.Sprint(call["ownerUserID"], "→", call["friendUserID"])] = true
	}
	for _, want := range []string{
		fmt.Sprint(idOf(me.ID), "→", idOf(other.ID)),
		fmt.Sprint(idOf(other.ID), "→", idOf(me.ID)),
	} {
		if !seen[want] {
			t.Errorf("missing delete_friend %s, saw %#v", want, seen)
		}
	}
	if extra := h.store.ExtraFor(me.ID, other.ID); extra.Relation != 0 || extra.Remark != "" {
		t.Errorf("local extras survived the deletion: %+v", extra)
	}
}

func TestFriendListCombinesOpenIMRowsWithLocalExtras(t *testing.T) {
	h := newTest(t)
	token, me := h.login(t, "13800000011")
	if err := h.store.PutExtra(store.Extra{OwnerID: me.ID, FriendID: 2, Relation: 4, Remark: "闺女", SensAll: true, SensLoc: true}); err != nil {
		t.Fatal(err)
	}

	answered := h.request(t, http.MethodGet, "/api/relation/list", "page=1&pageSize=20&key=", nil, token)
	if answered.Status != 200 {
		t.Fatalf("relation/list: %+v", answered)
	}
	// This is `bean/FriendList.Info`, not the detail screen's bean: relation and sex are numbers,
	// the timestamp key is createAt, and there is no permission object here.
	list := decode[struct {
		List []struct {
			UserID   int64  `json:"userId"`
			Nickname string `json:"nickname"`
			Remark   string `json:"remark"`
			Relation int    `json:"relation"`
			Sex      int    `json:"sex"`
			CreateAt int64  `json:"createAt"`
		} `json:"list"`
	}](t, answered.Data)

	if len(list.List) != 1 {
		t.Fatalf("expected the single stub friend, got %d", len(list.List))
	}
	row := list.List[0]
	if row.UserID != 2 || row.Nickname != "女儿" || row.Relation != 4 || row.Remark != "闺女" {
		t.Errorf("row mapping wrong: %+v", row)
	}
	// The client renders `Date(createAt * 1000L)`, so a millisecond value here is a date in the 58000s.
	if row.CreateAt < 1_700_000_000 || row.CreateAt > 9_999_999_999 {
		t.Errorf("createAt must stay epoch seconds, got %d", row.CreateAt)
	}
	if row.Sex != 0 {
		t.Errorf("the stub friend has no sex recorded, got %d", row.Sex)
	}
	raw := string(answered.Data)
	if strings.Contains(raw, "\"permission\":{") {
		t.Error("relation/list must not carry the detail screen's permission object")
	}
	if !strings.Contains(raw, "\"createAt\":") {
		t.Error("the client's key is createAt, not createTime")
	}
}

func TestQuotaUsesTheConfiguredLimitNotAnInventedOne(t *testing.T) {
	h := newTest(t)
	token, me := h.login(t, "13800000012")
	_, other := h.login(t, "13800000013")

	for i := 0; i < friendMax; i++ {
		if err := h.store.PutExtra(store.Extra{OwnerID: me.ID, FriendID: int64(9000 + i)}); err != nil {
			t.Fatal(err)
		}
	}
	answered := h.request(t, http.MethodGet, "/api/relation/pre_confirm", "code="+idOf(other.ID), nil, token)
	found := decode[struct {
		IsFriendLimit bool `json:"isFriendLimit"`
	}](t, answered.Data)
	if !found.IsFriendLimit {
		t.Errorf("at %d relations with a limit of %d the upsell must be flagged", friendMax, friendMax)
	}
	if got := h.store.FriendCount(me.ID); got != friendMax {
		t.Errorf("relation counting went wrong: %d", got)
	}
}

func TestEnvelopePublicKeyIsStableAndParsableByTheClient(t *testing.T) {
	h := newTest(t)
	token, _ := h.login(t, "13800000014")

	first := h.request(t, http.MethodGet, "/api/tool/rsa/public_key", "", nil, token)
	second := h.request(t, http.MethodGet, "/api/tool/rsa/public_key", "", nil, token)
	a := decode[struct {
		PublicKey string `json:"publicKey"`
	}](t, first.Data)
	b := decode[struct {
		PublicKey string `json:"publicKey"`
	}](t, second.Data)

	if a.PublicKey == "" || a.PublicKey != b.PublicKey {
		t.Fatalf("the envelope key must be stable across calls: %q vs %q", truncate(a.PublicKey), truncate(b.PublicKey))
	}
	if _, err := contract.StripPublicKeyPEM(a.PublicKey); err != nil {
		t.Errorf("the shipped client could not parse our key: %v", err)
	}
}

// TestColdStartRoutesAnswerBeforeAnyoneHasSignedIn covers the two calls the app makes before a session
// exists. `api/sms/login/cut` is sent by the client without a signature (its path has a `login`
// segment), so it has to answer a request carrying no headers at all; `api/client/config` is signed by
// the client but must not demand a session.
//
// The signature requirement on the config route is a deliberate change from today's production host:
// measured 2026-11-02, `link.tttick.com` answers an unsigned `GET /api/client/config` with 200 and real
// config (DESIGN_CN §七), while the test server refuses with 1001. Enforcing matches the test server and
// the client's own behaviour, and is the security-positive side of that difference.
func TestColdStartRoutesAnswerBeforeAnyoneHasSignedIn(t *testing.T) {
	h := newTest(t)
	req, _ := http.NewRequest(http.MethodGet, "https://api.example/api/sms/login/cut", nil)
	rec := httptest.NewRecorder()
	h.engine.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"status":200`) {
		t.Fatalf("an unsigned cut must answer cold: HTTP %d %s", rec.Code, rec.Body.String())
	}

	answered := h.request(t, http.MethodGet, "/api/client/config", "", nil, "")
	if answered.Status != 200 {
		t.Fatalf("config: %+v", answered)
	}
	if unsigned := h.requestUnsigned(t, http.MethodGet, "/api/client/config", ""); unsigned.Status != contract.StatusParamErr {
		t.Errorf("the gateway is the one place the recovered key still means something, got %+v", unsigned)
	}

	cut := h.request(t, http.MethodGet, "/api/sms/login/cut", "", nil, "")
	body := decode[struct {
		LoginType string `json:"loginType"`
	}](t, cut.Data)
	if body.LoginType != cutNoVendor {
		t.Errorf("cut should answer the configured value, got %q", body.LoginType)
	}
}

// requestUnsigned sends the same call with no Signature header, which is what the recovered
// interceptor produces only for `sms`/`login` paths — everything else it signs.
func (h *harness) requestUnsigned(t *testing.T, method, path, query string) envelope {
	t.Helper()
	target := "https://api.example" + path
	if query != "" {
		target += "?" + query
	}
	req, err := http.NewRequest(method, target, nil)
	if err != nil {
		t.Fatal(err)
	}
	rec := httptest.NewRecorder()
	h.engine.ServeHTTP(rec, req)

	var answered envelope
	if err := json.Unmarshal(rec.Body.Bytes(), &answered); err != nil {
		t.Fatalf("%s answered outside the client's envelope: HTTP %d %s", path, rec.Code, rec.Body.String())
	}
	return answered
}

func mustSign(t *testing.T, path, query, method string, body []byte) string {
	t.Helper()
	sig, err := contract.Sign(contract.SignatureInput(path, query, method, body))
	if err != nil {
		t.Fatal(err)
	}
	return sig
}

func idOf(n int64) string { return strconv.FormatInt(n, 10) }

func truncate(s string) string {
	if len(s) > 60 {
		return s[:60] + "…"
	}
	return s
}
