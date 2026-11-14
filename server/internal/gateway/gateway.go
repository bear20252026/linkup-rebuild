// Package gateway is the HTTP surface the LinkUp Android client already speaks: the same paths, the
// same `{status,msg,data}` envelope, the same AES signature header, the same account token.
//
// Two rules shape it:
//
//  1. Where the client is the fixed side, the server matches it — the signature is verified over the
//     URL the client actually sent, and the RSA public key is emitted in the four-dash footer form the
//     client's own stripper expects (contract.PublicKeyPEM).
//  2. Where only the server knew the answer, the value is a flag, never a guess. The friend quota and
//     the client-config switches are configured at boot and documented as unknown-from-the-APK, because
//     inventing a number here would look identical to a recovered one.
package gateway

import (
	"bytes"
	"crypto/rsa"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"linkup.local/server/internal/contract"
	"linkup.local/server/internal/dto"
	"linkup.local/server/internal/location"
	"linkup.local/server/internal/openim"
	"linkup.local/server/internal/store"
)

// Options is everything boot has to decide.
type Options struct {
	Store       *store.Store
	IM          *openim.Client
	JWTSecret   []byte
	PublicKey   *rsa.PrivateKey
	TokenTTL    time.Duration
	Logger      *slog.Logger
	DevSMSCode  string // when set, every issued code is this value and nothing is sent
	FriendLimit int    // <0 means "no quota"; the real number is not recoverable from the APK

	// CutLoginType is what `api/sms/login/cut` answers with. It selects the one-tap vendor on the
	// device, so until a vendor SDK is wired up anything but "1"/"3" is the honest choice.
	CutLoginType string
	// ClientConfig is `api/client/config`. Every switch in it is an operational decision, so the
	// defaults here are the ones the shipped APKs' own fallbacks used, not invented values.
	ClientConfig dto.ChannelConfig

	// LocationSettings holds the two track numbers that are not observable from the APK
	// (stay length that counts as an arrival point, silence that counts as a gap).
	LocationSettings location.Settings
	// DayZone is the timezone days are counted in; `api/location/days` answers date strings and
	// `api/location/v2/tracks` takes one back, so the same zone must serve both.
	DayZone *time.Location

	// OpenIMWs is the msg_gateway address handed to the client by `api/user/im_token`. The Android
	// emulator reaches host loopback only through 10.0.2.2, so that is the default; the client never
	// hardcodes the address, it reads it from this response.
	OpenIMWs string
	// ThirdPartyLogin gates `api/user/login` (WeChat/QQ). Default off: the identity on that body is
	// client-asserted, and turning it on without vendor-side OAuth verification is the audit's P0-1.
	// The client hides its vendor buttons behind the same switch (LoginPlatform's constant).
	ThirdPartyLogin bool
}

// Gateway holds the routes' shared state.
type Gateway struct {
	opts Options
	log  *slog.Logger
	pem  string
}

const bodyLimit = 1 << 20

// DefaultClientConfig is what `api/client/config` answers until operations says otherwise. The nine
// switches are all operational choices — none of their production values is readable from the APK —
// so they start at the values the client itself falls back to when the field is absent, and the
// im group ids stay empty rather than invented.
func DefaultClientConfig() dto.ChannelConfig {
	return dto.ChannelConfig{AutoReportAddressShow: 0, NotRelativesLockShow: false}
}

// New builds the engine. Routes are registered exactly as the client spells them, without a leading
// slash in the recovered annotation but with one on the wire.
func New(opts Options) (*gin.Engine, error) {
	if opts.Store == nil {
		return nil, errors.New("gateway: store is required")
	}
	if opts.IM == nil {
		return nil, errors.New("gateway: openim client is required")
	}
	if len(opts.JWTSecret) == 0 {
		return nil, errors.New("gateway: jwt secret is required")
	}
	if opts.PublicKey == nil {
		return nil, errors.New("gateway: rsa key is required")
	}
	if opts.TokenTTL <= 0 {
		opts.TokenTTL = 30 * 24 * time.Hour
	}
	if opts.Logger == nil {
		opts.Logger = slog.Default()
	}
	if opts.LocationSettings.StaySeconds <= 0 || opts.LocationSettings.GapSeconds <= 0 {
		opts.LocationSettings = location.DefaultSettings()
	}
	if opts.DayZone == nil {
		opts.DayZone = time.Local
	}
	if opts.OpenIMWs == "" {
		opts.OpenIMWs = "ws://10.0.2.2:10001"
	}
	pemText, err := contract.PublicKeyPEM(&opts.PublicKey.PublicKey)
	if err != nil {
		return nil, err
	}
	g := &Gateway{opts: opts, log: opts.Logger, pem: pemText}

	gin.SetMode(gin.ReleaseMode)
	engine := gin.New()
	engine.Use(gin.Recovery(), g.signature(), g.account())

	api := engine.Group("/api")
	{
		// Unauthenticated, unsigned (the client's own signer skips any path containing sms/login).
		api.GET("/client/config", g.clientConfig)
		api.GET("/sms/login/cut", g.loginCut)
		api.GET("/sms/send", g.sendCode)
		api.POST("/sms/click_to_login", g.loginByPhone)
		api.POST("/user/login", g.loginByThird)
		api.POST("/user/register", g.userRegister)
		api.POST("/user/password_login", g.userPasswordLogin)
		// The register page's live dedup: signed (no sms/login segment) but pre-login.
		api.GET("/user/username/check", g.usernameCheck)

		// Authenticated.
		api.GET("/user/info", g.userInfo)
		api.POST("/user/update/info", g.updateUserInfo)
		api.GET("/user/im_token", g.imToken)
		api.GET("/tool/rsa/public_key", g.publicKey)

		api.GET("/relation/pre_confirm", g.preConfirm)
		api.POST("/relation/apply", g.apply)
		api.POST("/relation/confirm", g.confirm)
		api.GET("/relation/apply_list", g.applyList)
		api.GET("/relation/list", g.friendList)
		api.GET("/relation/info", g.friendInfo)
		api.POST("/relation/del", g.deleteFriend)
		api.POST("/relation/update", g.updateRelation)
		api.POST("/relation/update_remark", g.updateRemark)

		// Lock domain: the ledger; the command itself rides the IM channel (lockMachine CMD).
		api.GET("/relation/lock_list", g.lockList)
		api.GET("/relation/lock/question_list", g.lockQuestionList)
		api.GET("/relation/lock/cover/list", g.lockCoverList)
		api.POST("/relation/lock", g.lock)
		api.POST("/relation/unlock", g.unlock)
		api.POST("/relation/lock/cover/del", g.lockCoverDel)

		// Device state + widget domain (待验 59): pairing = confirmed relation, per-device facts
		// recorded by api/phone/state/report.
		api.POST("/phone/state/report", g.phoneStateReport)
		// 通话记录域：被守护端批量上报（delta），守护端的 通话记录卡 从 api/index 读台账。
		api.POST("/phone/call_record/report", g.callRecordReport)
		// 应用使用域（v1/v2 两代上报合并同一台账，原版形状不可恢复见 handler 注释）。
		api.POST("/phone/app_use/report", g.appUseReport)
		api.POST("/phone/app_use/v2/report", g.appUseReport)
		// 敏感行为域（DESIGN 五点四十一）：安装/卸载/更新事件台账；server_timer 原语义不可
		// 恢复且客户端无调用方，路由不建（DESIGN 记录）。
		api.POST("/sensitive/report", g.sensReport)
		api.GET("/sensitive/user", g.sensUser)
		api.GET("/sensitive/user/days", g.sensUserDays)
		// 四象限待办域（DESIGN 五点四十二）：本人台账的三路由（get_list 的 listType 参数语义
		// 不可恢复，本重建忽略它、一次回答全部四组）。
		api.GET("/four_quadrant_list/get_list", g.todoGetList)
		api.POST("/four_quadrant_list/edit", g.todoEdit)
		api.POST("/four_quadrant_list/del", g.todoDel)
		api.GET("/widget/location", g.widgetLocation)
		api.GET("/widget/track", g.widgetTrack)

		// Realtime-screenshot domain (166): friend rows + the puller's done receipt. The capture
		// round itself is the IM `screenShot` command; these routes are the ledger the screen reads.
		api.GET("/screenshot/list", g.screenshotList)
		api.GET("/screenshot/done", g.screenshotDone)

		// Settings + aggregation domain.
		api.GET("/setting/im", g.settingIMGet)
		api.POST("/setting/im", g.settingIMUpdate)
		api.GET("/setting/user_list", g.privacyUserList)
		api.POST("/setting/update", g.privacyUpdate)
		api.GET("/index", g.index)
		api.GET("/version/check", g.versionCheck)

		// Location domain: report, then read one day back.
		api.POST("/location/report", g.locationReport)
		api.GET("/location/days", g.locationDays)
		api.GET("/location/v2/tracks", g.locationTracks)
	}
	return engine, nil
}

// signature verifies the AES signature the client puts in the `Signature` header. The skipped paths
// are the ones the client's interceptor also skips, so a missing header there is not an error.
//
// The body is read on every route, skipped ones included: the login calls carry their payload in it,
// and dropping it here would silently hand the handlers an empty form.
func (g *Gateway) signature() gin.HandlerFunc {
	return func(c *gin.Context) {
		var body []byte
		if !strings.EqualFold(c.Request.Method, http.MethodGet) && c.Request.ContentLength > 0 {
			raw, err := io.ReadAll(io.LimitReader(c.Request.Body, bodyLimit))
			if err != nil {
				g.deny(c, "read body")
				return
			}
			body = raw
			c.Request.Body = io.NopCloser(bytes.NewReader(raw))
		}
		c.Set(bodyKey, body)

		if !clientSigns(c.Request.URL.Path) {
			c.Next()
			return
		}
		input := contract.SignatureInput(c.Request.URL.Path, c.Request.URL.RawQuery, c.Request.Method, body)
		if !contract.Verify(c.GetHeader("Signature"), input) {
			g.deny(c, "signature")
			return
		}
		c.Next()
	}
}

// clientSigns reproduces the recovered interceptor's own skip list: a path with a `login` or `sms`
// segment is sent without Signature and Authorization headers, so demanding one there would reject
// every real login. Everything else — including `api/client/config` and `api/user/login`'s neighbours
// — arrives signed.
func clientSigns(path string) bool {
	for _, segment := range strings.Split(strings.Trim(path, "/"), "/") {
		if segment == "login" || segment == "sms" {
			return false
		}
	}
	return true
}

const (
	bodyKey     = "linkup.body"
	claimsKey   = "linkup.claims"
	authSkipKey = "linkup.anonymous"
)

// deny answers the way the test server answered a bad signature: 1001, "参数异常".
func (g *Gateway) deny(c *gin.Context, reason string) {
	g.log.Warn("rejected", "path", c.Request.URL.Path, "reason", reason)
	c.AbortWithStatusJSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "参数异常"))
}

// account requires the raw JWT in `Authorization` for every route except the three that log a user in.
func (g *Gateway) account() gin.HandlerFunc {
	return func(c *gin.Context) {
		switch c.FullPath() {
		case "/api/client/config", "/api/sms/login/cut", "/api/sms/send", "/api/sms/click_to_login", "/api/user/login", "/api/user/register", "/api/user/password_login", "/api/user/username/check":
			c.Set(authSkipKey, true)
			c.Next()
			return
		}
		claims, err := contract.ParseToken(g.opts.JWTSecret, c.GetHeader("Authorization"))
		if err != nil {
			// The client's data source treats this as token_fail and stops before business code.
			c.AbortWithStatusJSON(http.StatusUnauthorized, contract.Fail(contract.StatusTokenFail, "登录已失效"))
			return
		}
		c.Set(claimsKey, claims)
		c.Next()
	}
}

func (g *Gateway) claims(c *gin.Context) *contract.Claims {
	v, ok := c.Get(claimsKey)
	if !ok {
		return &contract.Claims{}
	}
	claims, _ := v.(*contract.Claims)
	if claims == nil {
		return &contract.Claims{}
	}
	return claims
}

// rawBody returns the bytes the signature middleware read off the request, before the handlers
// parsed them into a map. The bean-shaped routes need the whole object, not one key at a time.
func rawBody(c *gin.Context) []byte {
	raw, _ := c.Get(bodyKey)
	text, _ := raw.([]byte)
	return text
}

// body decodes the JSON body the signature middleware already read.
func body(c *gin.Context) map[string]any {
	raw, _ := c.Get(bodyKey)
	text, _ := raw.([]byte)
	if len(text) == 0 {
		return map[string]any{}
	}
	var out map[string]any
	if err := json.Unmarshal(text, &out); err != nil {
		return map[string]any{}
	}
	return out
}

func num(m map[string]any, key string) int64 {
	switch v := m[key].(type) {
	case float64:
		return int64(v)
	case string:
		n, _ := strconv.ParseInt(v, 10, 64)
		return n
	case json.Number:
		n, _ := v.Int64()
		return n
	}
	return 0
}

// optStr and optNum distinguish "the client sent null / left the key out" from "the client sent a
// value", which matters for fields whose zero means something (stage 0 restarts the profile wizard).
func optStr(m map[string]any, key string) *string {
	if v, ok := m[key].(string); ok {
		return &v
	}
	return nil
}

func optNum(m map[string]any, key string) *int {
	raw, ok := m[key]
	if !ok || raw == nil {
		return nil
	}
	n := int(num(m, key))
	return &n
}

func str(m map[string]any, key string) string {
	if v, ok := m[key].(string); ok {
		return v
	}
	return ""
}

func queryInt(c *gin.Context, key string, def int) int {
	n, err := strconv.Atoi(c.Query(key))
	if err != nil || n <= 0 {
		return def
	}
	return n
}
