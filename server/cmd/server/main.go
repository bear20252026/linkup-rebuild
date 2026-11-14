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
	"encoding/json"
	"encoding/pem"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"time"

	"linkup.local/server/internal/gateway"
	"linkup.local/server/internal/location"
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
		staySeconds = flag.Int64("stay-seconds", 120, "how long a phone must sit still before the day counts another arrival point (not observable from the APK)")
		gapSeconds  = flag.Int64("gap-seconds", 1800, "how long a silence becomes 没有新定位 instead of movement (not observable from the APK)")
		dayZone     = flag.String("loc-timezone", "Asia/Shanghai", "timezone the track days are counted in")
		inviteCode  = flag.String("invite-code", "LINKUP2026", "admin invitation code for username/password registration (never expires, unlimited uses)")
		inviteFile  = flag.String("invite-codes-file", "", "JSON file seeding richer invite codes: [{\"code\":\"...\",\"expiresAt\":0,\"maxUses\":100}] (unix seconds; 0 = never/unlimited)")
		thirdLogin  = flag.Bool("enable-third-login", false, "gate for POST api/user/login (WeChat/QQ). Off is the pause; on without vendor OAuth verification is audit P0-1 — do not flip alone")
		imWs        = flag.String("openim-ws", "ws://10.0.2.2:10001", "msg_gateway address handed to clients by api/user/im_token (10.0.2.2 is the emulator's host loopback)")
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
	// The store batches writes; shutdown is the one moment the file must be whole on disk.
	defer db.Close()
	key, err := loadOrCreateKey(*keyPath)
	if err != nil {
		log.Error("rsa key", "err", err)
		os.Exit(1)
	}

	client := openim.New(*imAddr, imSk, *imAdmin)
	zone, err := loadZone(*dayZone)
	if err != nil {
		log.Error("timezone", "err", err)
		os.Exit(1)
	}
	// Registration needs a live invite code: the -invite-code convenience seed plus whatever the
	// codes file adds. Seeding happens on every boot; the burn counts already in the store survive.
	seeds := []store.InviteCode{}
	if *inviteCode != "" {
		seeds = append(seeds, store.InviteCode{Code: *inviteCode})
	}
	if *inviteFile != "" {
		fileCodes, err := loadInviteCodes(*inviteFile)
		if err != nil {
			log.Error("invite codes", "err", err)
			os.Exit(1)
		}
		seeds = append(seeds, fileCodes...)
	}
	db.SeedInviteCodes(seeds)

	engine, err := gateway.New(gateway.Options{
		Store:            db,
		IM:               client,
		JWTSecret:        []byte(jwt),
		PublicKey:        key,
		TokenTTL:         *tokenTTL,
		Logger:           log,
		DevSMSCode:       *devCode,
		FriendLimit:      *friendLimit,
		CutLoginType:     *cut,
		ClientConfig:     gateway.DefaultClientConfig(),
		LocationSettings: location.Settings{StaySeconds: *staySeconds, GapSeconds: *gapSeconds},
		DayZone:          zone,
		OpenIMWs:         *imWs,
		ThirdPartyLogin:  *thirdLogin,
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

// loadInviteCodes reads the codes file: a JSON array of InviteCode records. A code without a "code"
// field is a config typo, not a valid invitation, and refuses the boot rather than seeding garbage.
func loadInviteCodes(path string) ([]store.InviteCode, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var codes []store.InviteCode
	if err := json.Unmarshal(raw, &codes); err != nil {
		return nil, fmt.Errorf("%s is not an invite-code array: %w", path, err)
	}
	for _, c := range codes {
		if c.Code == "" {
			return nil, fmt.Errorf("%s holds an entry without a code field", path)
		}
	}
	return codes, nil
}

// loadZone accepts a named zone and falls back to a fixed +08:00 when the machine has no tzdata
// (a Windows build of Go without the tzdb tag has no embedded database).
func loadZone(name string) (*time.Location, error) {
	if name == "" {
		return time.Local, nil
	}
	if zone, err := time.LoadLocation(name); err == nil {
		return zone, nil
	}
	if name == "Asia/Shanghai" {
		return time.FixedZone(name, 8*60*60), nil
	}
	return nil, fmt.Errorf("unknown timezone %q", name)
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
