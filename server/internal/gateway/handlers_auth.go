package gateway

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"

	"linkup.local/server/internal/contract"
	"linkup.local/server/internal/store"
)

// 账号密码注册与登录：`POST api/user/register`（邀请码校验恒开：必须存在、未过期、未用尽，
// 注册成功才扣减——由 store 的 RegisterWithUsername 单锁原子保证）和 `POST api/user/password_login`。
// 密码存 SHA-256 哈希（不存明文）；邀请码在启动时 seed 进 store（`-invite-code` 单码 +
// `-invite-codes-file` 多码），扣减计数随 store 落盘。密码哈希强度是审计 P1-1，另卡跟进。

func hashPassword(password string) string {
	h := sha256.Sum256([]byte("linkup:" + password))
	return hex.EncodeToString(h[:])
}

// usernameProblem 是注册与实时查重共享的校验口径：trim 后 3-32 字节。返回空串表示合法。
func usernameProblem(username string) string {
	if username == "" {
		return "用户名和密码不能为空"
	}
	if len(username) < 3 || len(username) > 32 {
		return "用户名 3-32 字符"
	}
	return ""
}

// userRegister: {username, password, inviteCode} → JWT + user (same shape as SMS login).
func (g *Gateway) userRegister(c *gin.Context) {
	form := body(c)
	username := strings.TrimSpace(str(form, "username"))
	password := str(form, "password")
	inviteCode := strings.TrimSpace(str(form, "inviteCode"))

	if username == "" || password == "" {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "用户名和密码不能为空"))
		return
	}
	if problem := usernameProblem(username); problem != "" {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, problem))
		return
	}
	if len(password) < 6 {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "密码至少 6 位"))
		return
	}

	// One store call owns uniqueness, the invite code and the burn: the handler only maps its
	// rejections. The old pre-check + create pair had a TOCTOU window where a concurrent duplicate
	// surfaced as a 500 instead of "用户名已存在".
	u, err := g.opts.Store.RegisterWithUsername(username, hashPassword(password), inviteCode)
	if err != nil {
		switch {
		case errors.Is(err, store.ErrUsernameTaken):
			c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "用户名已存在"))
		case errors.Is(err, store.ErrInviteNotSeeded), errors.Is(err, store.ErrInviteUnknown):
			c.JSON(http.StatusOK, contract.Fail(contract.StatusForbidden, "邀请码错误"))
		case errors.Is(err, store.ErrInviteExpired):
			c.JSON(http.StatusOK, contract.Fail(contract.StatusForbidden, "邀请码已过期"))
		case errors.Is(err, store.ErrInviteExhausted):
			c.JSON(http.StatusOK, contract.Fail(contract.StatusForbidden, "邀请码已用尽"))
		default:
			c.JSON(http.StatusOK, contract.Fail(http.StatusInternalServerError, "注册失败"))
		}
		return
	}

	if g.opts.IM != nil {
		g.ensureIMAccount(c.Request.Context(), *u)
	}

	token, _ := contract.IssueToken(g.opts.JWTSecret, u.ID, u.MainUserID, g.opts.TokenTTL)
	g.opts.Store.SaveToken(u.ID, token)

	c.JSON(http.StatusOK, contract.OK(g.toUser(*u, token)))
}

// usernameCheck answers `api/user/username/check` — the register page's live dedup. Same rules as
// registration, so the inline hint and the submit rejection can never disagree.
func (g *Gateway) usernameCheck(c *gin.Context) {
	username := strings.TrimSpace(c.Query("username"))
	if problem := usernameProblem(username); problem != "" {
		c.JSON(http.StatusOK, contract.OK(map[string]any{"available": false, "reason": problem}))
		return
	}
	if _, err := g.opts.Store.ByUsername(username); err == nil {
		c.JSON(http.StatusOK, contract.OK(map[string]any{"available": false, "reason": "用户名已存在"}))
		return
	}
	c.JSON(http.StatusOK, contract.OK(map[string]any{"available": true, "reason": ""}))
}

// userPasswordLogin: {username, password} → JWT + user.
func (g *Gateway) userPasswordLogin(c *gin.Context) {
	form := body(c)
	username := strings.TrimSpace(str(form, "username"))
	password := str(form, "password")

	u, err := g.opts.Store.ByUsername(username)
	if err != nil || u.ID <= 0 {
		c.JSON(http.StatusOK, contract.Fail(StatusNotFoundUser, "账号不存在"))
		return
	}
	if u.PasswordHash != hashPassword(password) {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusForbidden, "密码错误"))
		return
	}

	if g.opts.IM != nil {
		g.ensureIMAccount(c.Request.Context(), u)
	}

	token, _ := contract.IssueToken(g.opts.JWTSecret, u.ID, u.MainUserID, g.opts.TokenTTL)
	g.opts.Store.SaveToken(u.ID, token)

	c.JSON(http.StatusOK, contract.OK(g.toUser(u, token)))
}
