package contract

import (
	"strings"
	"testing"
	"time"
)

// The golden values below were produced by a throwaway Java program using javax.crypto
// (AES/CBC/PKCS5Padding, key used as the IV, lower-case hex) — an independent implementation of the
// same recipe the recovered Kotlin signer uses. Cross-checking against a different runtime is the
// point: a Go port that merely matched itself would prove nothing about the client on the other end.
func TestSignMatchesTheJvmOracle(t *testing.T) {
	cases := []struct{ input, want string }{
		{
			"/api/relation/list?page=1&pageSize=20&key=&tz=480&lang=zh&userId=0&client=android&deviceId=dev&channel=3305&av=1.6.6&t=1730000000000&distinctId=&shushuDeviceId=&mainUser=0",
			"82f9535bdfdf3e342eb66333e3785feb502f497e9642b0e74ffb9dba0595d7e0c7a80b193a88a999f192c265afa9e0f6a849f7d14329de64a775c93a8b971d471e17470f95245fd3b92109cf05dabdc8efc98d7143745677ace7d331c3294ed1329153857e7d52e2e3b75bab7411b49a52cccbbbb0560db36511199c513604714f1ad5d68f5b19b96f138eca5964a141aadf4f1c754b7dbd5369a602e43418717cb1d32b6ab14ff25d38c9543df239b0",
		},
		{
			"/api/client/config?tz=480&lang=zh&userId=42&client=android&deviceId=abc&channel=0&av=1.6.6&t=1&distinctId=&shushuDeviceId=&mainUser=42",
			"eff633c0d43008ba6dc9ee9e58f04e8b88351bc01b7e55ed8e3b29f097d59179196b946a066344b6c1f2b51747db5ca57ac0635b2fc0b91d56e9f3f857724ec642379b2061424c3876144d76c76c19ed95409bf49f599e9719e4ef81b82a73c5517b810da2d9a575d1260d6c4fda941181dfcfe61840e3b86bfc65c90e673b8a5f351300b1eff80fea2846f9a1d95cd3",
		},
	}
	for i, c := range cases {
		got, err := Sign(c.input)
		if err != nil {
			t.Fatalf("case %d: Sign: %v", i, err)
		}
		if got != c.want {
			t.Errorf("case %d: signature mismatch\n got %s\nwant %s", i, got, c.want)
		}
		if !Verify(got, c.input) {
			t.Errorf("case %d: Verify rejected its own signature", i)
		}
	}
}

func TestSignatureInputAppendsTheBodyDigestOnlyForNonGet(t *testing.T) {
	body := []byte(`{"friendId":7}`)
	get := SignatureInput("/api/relation/del", "a=1", "GET", body)
	post := SignatureInput("/api/relation/del", "a=1", "POST", body)
	if !strings.HasPrefix(post, get) {
		t.Fatalf("POST input should extend the GET input, got %q", post)
	}
	// The appended part is md5(body) in lower-case hex, exactly as the client computes it.
	if tail := post[len(get):]; len(tail) != 32 || strings.ToLower(tail) != tail {
		t.Errorf("expected a 32-char lower-case hex digest, got %q", tail)
	}
	if got := SignatureInput("/x", "", "POST", nil); got != "/x?" {
		t.Errorf("an empty body must not append a digest, got %q", got)
	}
}

func TestVerifyRejectsAMismatchedRequest(t *testing.T) {
	const path = "/api/relation/list"
	signed, err := Sign(SignatureInput(path, "page=1", "GET", nil))
	if err != nil {
		t.Fatal(err)
	}
	if Verify(signed, SignatureInput(path, "page=2", "GET", nil)) {
		t.Error("a replayed signature must not validate against a changed query")
	}
	if Verify("deadbeef", signed) {
		t.Error("garbage must never validate")
	}
	// Half a block: the client would send a length that is a multiple of 16, so a short buffer is a
	// decoding error rather than a mismatch.
	if _, err := Open("abcd"); err == nil {
		t.Error("a non-block-aligned signature should be rejected")
	}
}

func TestPublicKeyPEMSurvivesTheClientsOwnStripper(t *testing.T) {
	key, err := GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	text, err := PublicKeyPEM(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(text, "-----BEGIN PUBLIC KEY-----") {
		t.Errorf("the client only strips the five-dash header, got %q", firstLine(text))
	}
	if !strings.HasSuffix(text, TrailingDashFooter) {
		t.Errorf("footer must be the four-dash form the client strips, got %q", lastLine(text))
	}

	// What the client's parser must be able to produce: the DER of the same key.
	stripped, err := StripPublicKeyPEM(text)
	if err != nil {
		t.Fatalf("client-side parse failed: %v", err)
	}
	if len(stripped) < 100 {
		t.Fatalf("decoded key material looks wrong (%d bytes)", len(stripped))
	}

	// The negative control: the standard five-dash footer leaves a stray dash behind, which is the
	// silent contact-match failure recorded as verification item 26.
	standard := strings.Replace(text, TrailingDashFooter, "-----END PUBLIC KEY-----", 1)
	if _, err := StripPublicKeyPEM(standard); err == nil {
		t.Error("a five-dash footer should have broken the client's decode")
	} else if !strings.Contains(err.Error(), "client-side decode would fail") {
		t.Errorf("unexpected failure: %v", err)
	}
}

func TestTokenRoundTripAndExpiry(t *testing.T) {
	secret := []byte("unit-test-secret")
	token, err := IssueToken(secret, 4242, 4242, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	claims, err := ParseToken(secret, token)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if claims.UserID != 4242 || claims.MainUser != 4242 {
		t.Errorf("claims lost the identity: %+v", claims)
	}
	if _, err := ParseToken([]byte("other-secret"), token); err == nil {
		t.Error("a token signed with another secret must not verify")
	}
	if _, err := ParseToken(secret, "Bearer "+token); err == nil {
		t.Error("the client sends the token raw; a Bearer prefix must not be accepted quietly")
	}
	expired, err := IssueToken(secret, 1, 1, -time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := ParseToken(secret, expired); err == nil {
		t.Error("an expired token must be rejected (the client answers with token_fail)")
	}
}

func TestStripPublicKeyPEMHandlesBase64ThatIsNotAKey(t *testing.T) {
	// The client would base64-decode whatever we send; a truncated body must fail loudly here rather
	// than hand back garbage that x509 would then choke on.
	if _, err := StripPublicKeyPEM("-----BEGIN PUBLIC KEY-----\n###\n" + TrailingDashFooter); err == nil {
		t.Error("non-base64 content should not decode")
	}
}

func firstLine(s string) string {
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		return s[:i]
	}
	return s
}

func lastLine(s string) string {
	s = strings.TrimRight(s, "\n")
	if i := strings.LastIndexByte(s, '\n'); i >= 0 {
		return s[i+1:]
	}
	return s
}
