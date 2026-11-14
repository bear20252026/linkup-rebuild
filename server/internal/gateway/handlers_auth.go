package gateway

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"linkup.local/server/internal/contract"
	"linkup.local/server/internal/store"
)

// 账号密码注册与登录：`POST api/user/register`（邀请码校验恒开：必须存在、未过期、未用尽，
// 注册成功才扣减——由 store 的 RegisterWithUsername 单锁原子保证）和 `POST api/user/password_login`
// （bcrypt 双读迁移 + 按用户名登录限速，见 store.VerifyPassword 与下方三件套）。邀请码在启动时
// seed 进 store（`-invite-code` 单码 + `-invite-codes-file` 多码），扣减计数随 store 落盘。

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
	u, err := g.opts.Store.RegisterWithUsername(username, store.HashPassword(password), inviteCode)
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

// userPasswordLogin: {username, password} → JWT + user. 登录失败按用户名计数,连错 5 次锁 15
// 分钟(审计 P1-1 的在线穷举面);成功清零并顺手把存量哈希升级为 bcrypt(双读迁移)。
func (g *Gateway) userPasswordLogin(c *gin.Context) {
	form := body(c)
	username := strings.TrimSpace(str(form, "username"))
	password := str(form, "password")

	if locked := g.loginLocked(username); locked {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusForbidden, "尝试过于频繁，请稍后再试"))
		return
	}

	u, err := g.opts.Store.ByUsername(username)
	if err != nil || u.ID <= 0 {
		c.JSON(http.StatusOK, contract.Fail(StatusNotFoundUser, "账号不存在"))
		return
	}
	ok, needsUpgrade := store.VerifyPassword(u, password)
	if !ok {
		g.recordLoginFail(username)
		c.JSON(http.StatusOK, contract.Fail(contract.StatusForbidden, "密码错误"))
		return
	}
	g.resetLoginFails(username)
	if needsUpgrade {
		// 双读迁移:旧哈希(无盐 SHA-256)登录成功的一刻换成 bcrypt,旧格式自此消失。
		g.opts.Store.UpgradePassword(u.ID, password)
	}

	if g.opts.IM != nil {
		g.ensureIMAccount(c.Request.Context(), u)
	}

	token, _ := contract.IssueToken(g.opts.JWTSecret, u.ID, u.MainUserID, g.opts.TokenTTL)
	g.opts.Store.SaveToken(u.ID, token)

	c.JSON(http.StatusOK, contract.OK(g.toUser(u, token)))
}

// loginLocked answers whether this username is cooling down after too many failures.
func (g *Gateway) loginLocked(username string) bool {
	g.loginMu.Lock()
	defer g.loginMu.Unlock()
	fail := g.loginFails[username]
	if fail == nil {
		return false
	}
	if time.Now().Before(fail.lockedUntil) {
		return true
	}
	if !fail.lockedUntil.IsZero() {
		delete(g.loginFails, username) // 冷却已过:从零开始
	}
	return false
}

// recordLoginFail counts one failed attempt; reaching loginMaxFails locks the username.
func (g *Gateway) recordLoginFail(username string) {
	g.loginMu.Lock()
	defer g.loginMu.Unlock()
	fail := g.loginFails[username]
	if fail == nil {
		fail = &loginFail{}
		g.loginFails[username] = fail
	}
	fail.count++
	if fail.count >= loginMaxFails {
		fail.lockedUntil = time.Now().Add(loginLockDuration)
	}
}

// resetLoginFails clears the failure count on a successful login.
func (g *Gateway) resetLoginFails(username string) {
	g.loginMu.Lock()
	defer g.loginMu.Unlock()
	delete(g.loginFails, username)
}
