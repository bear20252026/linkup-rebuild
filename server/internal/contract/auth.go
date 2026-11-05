package contract

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Claims are what the client's own identity parameters have to agree with: `userId` and `mainUser`
// come back out of the token, and the client also sends them as query parameters.
type Claims struct {
	UserID   int64 `json:"userId"`
	MainUser int64 `json:"mainUser"`
	jwt.RegisteredClaims
}

// IssueToken signs an account token. The client sends it back raw in `Authorization` — no "Bearer"
// prefix — so verification must not expect one either.
func IssueToken(secret []byte, userID, mainUser int64, ttl time.Duration) (string, error) {
	now := time.Now()
	claims := Claims{
		UserID:   userID,
		MainUser: mainUser,
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(ttl)),
			Issuer:    "linkup",
		},
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(secret)
}

// ParseToken returns the claims, or an error the caller answers with 401 (`token_fail` in the
// client's data source, which stops the request before any business code sees it).
func ParseToken(secret []byte, raw string) (*Claims, error) {
	if strings.TrimSpace(raw) == "" {
		return nil, errors.New("linkup/token: empty Authorization")
	}
	token, err := jwt.ParseWithClaims(raw, &Claims{}, func(*jwt.Token) (any, error) {
		return secret, nil
	}, jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}))
	if err != nil {
		return nil, fmt.Errorf("linkup/token: %w", err)
	}
	claims, ok := token.Claims.(*Claims)
	if !ok || !token.Valid {
		return nil, errors.New("linkup/token: malformed claims")
	}
	return claims, nil
}

// TrailingDashFooter is the footer the recovered client strips. It is *not* valid PEM: the client
// replaces `----END PUBLIC KEY-----` (four dashes) and then removes every whitespace character, so
// a five-dash footer would leave a leading `-` and break Base64 decoding — the client would then
// take its own "no server key" branch and the contact match would fail with no visible reason.
//
// Emitting what the client can parse is the cheaper half of the fix, since the shipped APKs cannot
// be changed. This also settles the open question recorded as verification item 26 for our backend:
// the production service has to have been doing the same, or the feature never worked.
const TrailingDashFooter = "----END PUBLIC KEY-----"

const pemHeader = "-----BEGIN PUBLIC KEY-----"

// PublicKeyPEM renders an RSA public key in the exact form the client's stripper expects:
// five-dash header, 64-column body, four-dash footer.
func PublicKeyPEM(key *rsa.PublicKey) (string, error) {
	der, err := x509.MarshalPKIXPublicKey(key)
	if err != nil {
		return "", fmt.Errorf("linkup/publickey: %w", err)
	}
	body := base64.StdEncoding.EncodeToString(der)
	var out strings.Builder
	out.WriteString(pemHeader + "\n")
	for len(body) > 64 {
		out.WriteString(body[:64] + "\n")
		body = body[64:]
	}
	out.WriteString(body + "\n")
	out.WriteString(TrailingDashFooter)
	return out.String(), nil
}

// GenerateKey is the 2048-bit RSA pair the contact-match envelope seals against.
func GenerateKey() (*rsa.PrivateKey, error) {
	return rsa.GenerateKey(rand.Reader, 2048)
}

// StripPublicKeyPEM mirrors the client's own parsing so the round trip can be tested without a
// device: remove the header, remove the four-dash footer, drop whitespace, decode.
func StripPublicKeyPEM(text string) ([]byte, error) {
	cleaned := strings.ReplaceAll(text, pemHeader, "")
	cleaned = strings.ReplaceAll(cleaned, TrailingDashFooter, "")
	cleaned = strings.Join(strings.Fields(cleaned), "")
	raw, err := base64.StdEncoding.DecodeString(cleaned)
	if err != nil {
		return nil, fmt.Errorf("linkup/publickey: client-side decode would fail: %w", err)
	}
	return raw, nil
}
