// Command server is the rebuilt LinkUp API gateway: the client's own contract on the outside,
// OpenIMServer for the relation and message state on the inside, and a small JSON file for the
// LinkUp-only extras (relation type, sharing switches, verification codes).
//
// Boot refuses the OpenIM demo secret unless -dev is passed: that secret is published in the public
// compose repository, so accepting it silently would mean starting a service anyone can administer.
package main

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"time"

	"linkup.local/server/internal/gateway"
	"linkup.local/server/internal/openim"
	"linkup.local/server/internal/store"
)

func main() {
	var (
		addr        = flag.String("addr", "127.0.0.1:8090", "listen address")
		storePath   = flag.String("store", "linkup-store.json", "account/relation state file; empty keeps everything in memory")
		imAddr      = flag.String("openim", "http://127.0.0.1:10002", "OpenIMServer API address")
		imSecret    = flag.String("openim-secret", os.Getenv("OPENIM_SECRET"), "OpenIM shared secret; refuses the published default without -dev")
		imAdmin     = flag.String("openim-admin", "imAdmin", "OpenIM admin user id")
		jwtSecret   = flag.String("jwt-secret", os.Getenv("LINKUP_JWT_SECRET"), "HS256 secret for account tokens")
		keyPath     = flag.String("rsa-key", "linkup-rsa.key", "RSA private key file for the contact-match envelope (created if missing)")
		tokenTTL    = flag.Duration("token-ttl", 30*24*time.Hour, "account token lifetime")
		cut         = flag.String("cut-login-type", "9", `api/sms/login/cut answer: "1" GY, "3" FlyVerify, anything else means no one-tap path`)
		friendLimit = flag.Int("friend-limit", -1, "friends a non-VIP account may hold; -1 disables the quota (the production number is not recoverable from the APK)")
		devCode     = flag.String("dev-sms-code", "", "when set, this code is accepted for every phone and no message is ever sent")
		dev         = flag.Bool("dev", false, "allow the published OpenIM demo secret and an empty JWT secret")
	)
	flag.Parse()

	log := slog.New(slog.NewTextHandler(os.Stderr, nil))

	if !*dev && *jwtSecret == "" {
		log.Error("refusing to start: -jwt-secret is empty (pass -dev only for a throwaway local run)")
		os.Exit(2)
	}
	if *imSecret == "" && !*dev {
		log.Error("refusing to start: -openim-secret is empty (pass -dev only for a throwaway local run)")
		os.Exit(2)
	}
	if !*dev && *imSecret == openim.DefaultSecret {
		log.Error("refusing to start: -openim-secret is the published compose default (pass -dev only for a throwaway local run)")
		os.Exit(2)
	}

	// -dev stands in for the two secrets an operator would otherwise have to supply, so that a local run
	// can start at all. Both values are fixed and logged on every boot.
	jwt := *jwtSecret
	if jwt == "" {
		jwt = "linkup-dev-insecure-jwt-secret"
		log.Warn("using the fixed development JWT secret", "value", jwt)
	}
	imSk := *imSecret
	if imSk == "" {
		imSk = openim.DefaultSecret
		log.Warn("using the published OpenIM demo secret", "value", imSk)
	}

	db, err := store.Open(*storePath)
	if err != nil {
		log.Error("open store", "err", err)
		os.Exit(1)
	}
	key, err := loadOrCreateKey(*keyPath)
	if err != nil {
		log.Error("rsa key", "err", err)
		os.Exit(1)
	}

	client := openim.New(*imAddr, imSk, *imAdmin)
	engine, err := gateway.New(gateway.Options{
		Store:        db,
		IM:           client,
		JWTSecret:    []byte(jwt),
		PublicKey:    key,
		TokenTTL:     *tokenTTL,
		Logger:       log,
		DevSMSCode:   *devCode,
		FriendLimit:  *friendLimit,
		CutLoginType: *cut,
		ClientConfig: gateway.DefaultClientConfig(),
	})
	if err != nil {
		log.Error("build gateway", "err", err)
		os.Exit(1)
	}

	log.Info("linkup gateway listening",
		"addr", *addr, "openim", *imAddr, "store", *storePath,
		"friendLimit", *friendLimit, "devMode", *dev, "devSmsCode", *devCode != "")
	if *devCode != "" {
		log.Warn("dev sms code is active; verification codes are printed instead of sent")
	}
	if err := engine.Run(*addr); err != nil {
		log.Error("serve", "err", err)
		os.Exit(1)
	}
}

// loadOrCreateKey keeps the envelope key stable across restarts: the client caches the account record
// that carries a sealed contact match, so a new key at every boot would invalidate stored answers.
func loadOrCreateKey(path string) (*rsa.PrivateKey, error) {
	if raw, err := os.ReadFile(path); err == nil {
		block, _ := pem.Decode(raw)
		if block == nil {
			return nil, fmt.Errorf("%s exists but is not a PEM block", path)
		}
		if key, err := x509.ParsePKCS1PrivateKey(block.Bytes); err == nil {
			return key, nil
		}
		parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
		if err != nil {
			return nil, err
		}
		key, ok := parsed.(*rsa.PrivateKey)
		if !ok {
			return nil, fmt.Errorf("%s holds a %T, not an RSA key", path, parsed)
		}
		return key, nil
	}
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, err
	}
	out, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return key, nil // running without persistence is still a usable development server
	}
	defer out.Close()
	if err := pem.Encode(out, &pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)}); err != nil {
		return key, nil
	}
	return key, nil
}
