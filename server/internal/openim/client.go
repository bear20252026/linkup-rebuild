// Package openim is the HTTP client for OpenIMServer's API (v3.8.3 route table).
//
// Why this exists at all: LinkUp's own server-side state that OpenIM *does* model — friendships,
// friend requests, conversations, messages, object storage — is better off in a maintained component
// than re-derived from a lost codebase. Everything LinkUp-only (match codes, relation type, the
// privacy/lock settings, VIP, quotas) stays in the gateway.
//
// The paths and field names below are taken from the released server's own route table
// (internal/api/router.go) and from github.com/openimsdk/protocol v0.0.73-alpha.12's generated json
// tags, not from memory:
//
//	POST /auth/get_admin_token   {secret, userID[]}                -> {token, expireTimeSeconds}
//	POST /auth/get_user_token    {platformID, userID}             -> {token, expireTimeSeconds}
//	POST /user/user_register     {users:[{userID,nickname,faceURL,ex}]}
//	POST /user/update_user_info  {userInfo:{userID,nickname,faceURL}}
//	POST /friend/add_friend      {fromUserID,toUserID,reqMsg,ex}
//	POST /friend/add_friend_response {fromUserID,toUserID,handleResult,handleMsg}
//	POST /friend/get_friend_apply_list      {userID,pagination} -> {FriendRequests,total}
//	POST /friend/get_self_friend_apply_list {userID,pagination} -> {friendRequests,total}
//	POST /friend/get_friend_list            {userID,pagination} -> {friendsInfo,total}
//	POST /friend/set_friend_remark {ownerUserID,friendUserID,remark}
//	POST /friend/delete_friend     {ownerUserID,friendUserID}
//	POST /friend/is_friend         {ownerUserID,friendUserID}
//
// The two apply lists do not agree on the case of their key — the protocol module tags one
// `FriendRequests` and the other `friendRequests`. Decoding happens with encoding/json, which falls
// back to a case-insensitive field match, so a single `Apply` decoder reads both; scripts/pin both
// spellings in client_test.go so neither list can quietly start returning empty.
//
// Every answer is wrapped in {errCode,errMsg,errDlt,data}; errCode 0 means success. Requests carry an
// `operationID` (a trace id) in the header and the body, and a `token` header on everything that is
// not a token request.
package openim

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

// DefaultSecret is the shared secret the public compose file ships with. Anything that is not a
// throwaway local run has to replace it, so the boot path checks for this exact value.
const DefaultSecret = "openIM123"

// PlatformAndroid is the OpenIM platform id for an Android client session.
const PlatformAndroid = 2

// HandleResultRejected / Accepted are the values `add_friend_response` expects for a declined or
// accepted request; they mirror the client-side rule the LinkUp detail screen already implements
// (0 = ignored, 1 = accepted, 2 = rejected).
const (
	HandleResultIgnored  = 0
	HandleResultAccepted = 1
	HandleResultRejected = 2
)

// Client talks to one OpenIM API endpoint (default port 10002 of the compose stack).
type Client struct {
	BaseURL string
	Secret  string
	Admin   string
	HTTP    *http.Client

	mu     sync.Mutex
	token  string
	expiry time.Time
}

// New returns a client for the given API address. Empty fields fall back to the compose defaults,
// which are *not* safe for anything but local development — main.go refuses to start with them
// unless the dev flag is set.
func New(baseURL, secret, adminUserID string) *Client {
	if baseURL == "" {
		baseURL = "http://127.0.0.1:10002"
	}
	if secret == "" {
		secret = DefaultSecret
	}
	if adminUserID == "" {
		adminUserID = "imAdmin"
	}
	return &Client{BaseURL: strings.TrimRight(baseURL, "/"), Secret: secret, Admin: adminUserID, HTTP: &http.Client{Timeout: 10 * time.Second}}
}

// UsesDefaultSecret lets the boot sequence refuse the shipped compose secret in a non-dev run.
func (c *Client) UsesDefaultSecret() bool { return c.Secret == DefaultSecret }

type envelope struct {
	ErrCode int             `json:"errCode"`
	ErrMsg  string          `json:"errMsg"`
	ErrDlt  string          `json:"errDlt"`
	Data    json.RawMessage `json:"data"`
}

// Error is what a non-zero errCode becomes; the gateway maps it onto its own envelope, so the
// Android client never sees an OpenIM code.
type Error struct {
	Code int
	Msg  string
	Dlt  string
	Path string
}

func (e *Error) Error() string {
	return fmt.Sprintf("openim %s: errCode=%d errMsg=%s errDlt=%s", e.Path, e.Code, e.Msg, e.Dlt)
}

// Call posts one request and decodes `out` from the envelope's `data`. Pass a nil out to ignore it.
func (c *Client) Call(ctx context.Context, path string, req, out any) error {
	body, err := c.enrich(req)
	if err != nil {
		return err
	}
	raw, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("openim: encode %s: %w", path, err)
	}

	var token string
	if path != "/auth/get_admin_token" && path != "/auth/get_user_token" {
		if token, err = c.AdminToken(ctx); err != nil {
			return err
		}
	}

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+path, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("operationID", operationID())
	if token != "" {
		httpReq.Header.Set("token", token)
	}

	resp, err := c.HTTP.Do(httpReq)
	if err != nil {
		return fmt.Errorf("openim: %s unreachable: %w", path, err)
	}
	defer resp.Body.Close()
	payload, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("openim: read %s: %w", path, err)
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("openim: %s answered HTTP %d: %s", path, resp.StatusCode, snippet(payload))
	}

	var answered envelope
	if err := json.Unmarshal(payload, &answered); err != nil {
		return fmt.Errorf("openim: %s returned a non-JSON body: %w", path, err)
	}
	if answered.ErrCode != 0 {
		return &Error{Code: answered.ErrCode, Msg: answered.ErrMsg, Dlt: answered.ErrDlt, Path: path}
	}
	if out == nil || len(answered.Data) == 0 {
		return nil
	}
	if err := json.Unmarshal(answered.Data, out); err != nil {
		return fmt.Errorf("openim: decode %s data: %w", path, err)
	}
	return nil
}

// enrich copies the request map and adds the trace id the server expects in the body too.
func (c *Client) enrich(req any) (map[string]any, error) {
	raw, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}
	var body map[string]any
	if err := json.Unmarshal(raw, &body); err != nil {
		return nil, fmt.Errorf("openim: request must be a JSON object: %w", err)
	}
	body["operationID"] = operationID()
	return body, nil
}

// AdminToken fetches and caches the inter-service token until shortly before it expires.
func (c *Client) AdminToken(ctx context.Context) (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.token != "" && time.Until(c.expiry) > time.Minute {
		return c.token, nil
	}
	var answered struct {
		Token             string `json:"token"`
		ExpireTimeSeconds int64  `json:"expireTimeSeconds"`
	}
	if err := c.Call(ctx, "/auth/get_admin_token", map[string]any{
		"secret": c.Secret, "userID": []string{c.Admin},
	}, &answered); err != nil {
		return "", err
	}
	if answered.Token == "" {
		return "", fmt.Errorf("openim: admin token answer carried no token")
	}
	c.token = answered.Token
	c.expiry = time.Now().Add(time.Duration(answered.ExpireTimeSeconds) * time.Second)
	return c.token, nil
}

// UserToken is the token the *device* SDK would log in with; the gateway hands it to the client so
// the IM session is not the admin's.
func (c *Client) UserToken(ctx context.Context, userID string, platformID int) (string, int64, error) {
	var answered struct {
		Token             string `json:"token"`
		ExpireTimeSeconds int64  `json:"expireTimeSeconds"`
	}
	err := c.Call(ctx, "/auth/get_user_token", map[string]any{"userID": userID, "platformID": platformID}, &answered)
	return answered.Token, answered.ExpireTimeSeconds, err
}

// Account is one IM identity to register. The LinkUp user id is used verbatim as OpenIM's userID so
// no second mapping table is needed.
type Account struct {
	UserID   string `json:"userID"`
	Nickname string `json:"nickname"`
	FaceURL  string `json:"faceURL"`
	Ex       string `json:"ex,omitempty"`
}

// RegisterUsers creates IM accounts. Already-existing accounts are not an error: the register call is
// idempotent per OpenIM's own validator, and a re-login must not fail because the account exists.
func (c *Client) RegisterUsers(ctx context.Context, accounts ...Account) error {
	users := make([]any, 0, len(accounts))
	for _, a := range accounts {
		users = append(users, a)
	}
	return c.Call(ctx, "/user/user_register", map[string]any{"users": users}, nil)
}

// AddFriend files the request on `to`'s side; the accepted state is stored by OpenIM, not by us.
func (c *Client) AddFriend(ctx context.Context, from, to, reqMsg string) error {
	return c.Call(ctx, "/friend/add_friend", map[string]any{
		"fromUserID": from, "toUserID": to, "reqMsg": reqMsg,
	}, nil)
}

// RespondApply answers a pending request. `from` is the requester, the caller's token is the handler's.
func (c *Client) RespondApply(ctx context.Context, from, to string, result int, msg string) error {
	return c.Call(ctx, "/friend/add_friend_response", map[string]any{
		"fromUserID": from, "toUserID": to, "handleResult": result, "handleMsg": msg,
	}, nil)
}

// Apply is one row of a request list, in the fields OpenIM actually returns.
type Apply struct {
	FromUserID    string `json:"fromUserID"`
	FromNickname  string `json:"fromNickname"`
	FromFaceURL   string `json:"fromFaceURL"`
	ToUserID      string `json:"toUserID"`
	ToNickname    string `json:"toNickname"`
	ToFaceURL     string `json:"toFaceURL"`
	HandleResult  int32  `json:"handleResult"`
	ReqMsg        string `json:"reqMsg"`
	CreateTime    int64  `json:"createTime"`
	HandlerUserID string `json:"handlerUserID"`
	HandleMsg     string `json:"handleMsg"`
	HandleTime    int64  `json:"handleTime"`
}

// Friend is one accepted relation, with the remark OpenIM stores for it.
type Friend struct {
	OwnerUserID string     `json:"ownerUserID"`
	Remark      string     `json:"remark"`
	CreateTime  int64      `json:"createTime"`
	AddSource   int32      `json:"addSource"`
	FriendUser  PublicUser `json:"friendUser"`
}

// PublicUser is the shared account card OpenIM keeps for every user.
type PublicUser struct {
	UserID   string `json:"userID"`
	Nickname string `json:"nickname"`
	FaceURL  string `json:"faceURL"`
	Ex       string `json:"ex"`
}

// ReceivedApplies is the "who asked to add me" list; the LinkUp request screen reads exactly this.
func (c *Client) ReceivedApplies(ctx context.Context, userID string, page, size int) ([]Apply, int64, error) {
	return c.applies(ctx, "/friend/get_friend_apply_list", userID, page, size)
}

// SentApplies is the "what did I ask for" list.
func (c *Client) SentApplies(ctx context.Context, userID string, page, size int) ([]Apply, int64, error) {
	return c.applies(ctx, "/friend/get_self_friend_apply_list", userID, page, size)
}

func (c *Client) applies(ctx context.Context, path, userID string, page, size int) ([]Apply, int64, error) {
	var answered struct {
		FriendRequests []Apply `json:"FriendRequests"`
		Total          int64   `json:"total"`
	}
	err := c.Call(ctx, path, map[string]any{
		"userID":     userID,
		"pagination": pagination(page, size),
	}, &answered)
	return answered.FriendRequests, answered.Total, err
}

// Friends is the accepted relation list.
func (c *Client) Friends(ctx context.Context, userID string, page, size int) ([]Friend, int64, error) {
	var answered struct {
		FriendsInfo []Friend `json:"friendsInfo"`
		Total       int64    `json:"total"`
	}
	err := c.Call(ctx, "/friend/get_friend_list", map[string]any{
		"userID":     userID,
		"pagination": pagination(page, size),
	}, &answered)
	return answered.FriendsInfo, answered.Total, err
}

// SetRemark stores the per-friend remark in OpenIM, next to the relation itself.
func (c *Client) SetRemark(ctx context.Context, owner, friend, remark string) error {
	return c.Call(ctx, "/friend/set_friend_remark", map[string]any{
		"ownerUserID": owner, "friendUserID": friend, "remark": remark,
	}, nil)
}

// DeleteFriend removes the relation on both sides.
func (c *Client) DeleteFriend(ctx context.Context, owner, friend string) error {
	return c.Call(ctx, "/friend/delete_friend", map[string]any{
		"ownerUserID": owner, "friendUserID": friend,
	}, nil)
}

// IsFriend is the check the client's "already a friend" branch needs.
func (c *Client) IsFriend(ctx context.Context, owner, friend string) (bool, error) {
	var answered struct {
		IsFriend bool `json:"isFriend"`
	}
	err := c.Call(ctx, "/friend/is_friend", map[string]any{
		"ownerUserID": owner, "friendUserID": friend,
	}, &answered)
	return answered.IsFriend, err
}

// UpdateProfile keeps the IM account card in step with the LinkUp profile.
func (c *Client) UpdateProfile(ctx context.Context, userID, nickname, faceURL string) error {
	return c.Call(ctx, "/user/update_user_info", map[string]any{
		"userInfo": map[string]any{"userID": userID, "nickname": nickname, "faceURL": faceURL},
	}, nil)
}

func pagination(page, size int) map[string]any {
	if page < 1 {
		page = 1
	}
	if size < 1 {
		size = 20
	}
	return map[string]any{"pageNumber": int32(page), "showNumber": int32(size)}
}

func operationID() string {
	buf := make([]byte, 8)
	if _, err := rand.Read(buf); err != nil {
		return fmt.Sprintf("%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(buf)
}

func snippet(b []byte) string {
	if len(b) > 200 {
		return string(b[:200]) + "…"
	}
	return string(b)
}
