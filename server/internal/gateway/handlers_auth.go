package gateway

import (
	"crypto/sha256"
	"encoding/hex"
	"net/http"

	"github.com/gin-gonic/gin"

	"linkup.local/server/internal/contract"
)

// 账号密码注册与登录：`POST api/user/register`（需要管理员邀请码）和
// `POST api/user/password_login`。密码存 SHA-256 哈希（不存明文）；邀请码由
// `-invite-code` 启动旗标设定，管理员改码重启即可。

func hashPassword(password string) string {
	h := sha256.Sum256([]byte("linkup:" + password))
	return hex.EncodeToString(h[:])
}

// userRegister: {username, password, inviteCode} → JWT + user (same shape as SMS login).
func (g *Gateway) userRegister(c *gin.Context) {
	form := body(c)
	username := str(form, "username")
	password := str(form, "password")
	inviteCode := str(form, "inviteCode")

	if username == "" || password == "" {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "用户名和密码不能为空"))
		return
	}
	if len(username) < 3 || len(username) > 32 {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "用户名 3-32 字符"))
		return
	}
	if len(password) < 6 {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "密码至少 6 位"))
		return
	}
	if g.opts.InviteCode != "" && inviteCode != g.opts.InviteCode {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusForbidden, "邀请码错误"))
		return
	}

	// Check username uniqueness.
	if existing, err := g.opts.Store.ByUsername(username); err == nil && existing.ID > 0 {
		c.JSON(http.StatusOK, contract.Fail(contract.StatusParamErr, "用户名已存在"))
		return
	}

	// Create user.
	u, err := g.opts.Store.CreateUserWithUsername(username, hashPassword(password))
	if err != nil {
		c.JSON(http.StatusOK, contract.Fail(http.StatusInternalServerError, "注册失败"))
		return
	}

	if g.opts.IM != nil {
		g.ensureIMAccount(c.Request.Context(), *u)
	}

	token, _ := contract.IssueToken(g.opts.JWTSecret, u.ID, u.MainUserID, g.opts.TokenTTL)
	g.opts.Store.SaveToken(u.ID, token)

	c.JSON(http.StatusOK, contract.OK(g.toUser(*u, token)))
}

// userPasswordLogin: {username, password} → JWT + user.
func (g *Gateway) userPasswordLogin(c *gin.Context) {
	form := body(c)
	username := str(form, "username")
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
