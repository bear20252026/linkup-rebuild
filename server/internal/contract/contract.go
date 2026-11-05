// Package contract is the wire format the LinkUp client was built against: an
// `{status,msg,data}` envelope, an AES-128-CBC request signature, and the account JWT.
//
// Everything here is derived from the recovered Android client (1.6.6) plus what a live call
// against our own test server answered — see report/04 and net/.../SignatureInterceptor.kt.
package contract

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/md5"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
)

// Status codes the client branches on. 200 is success; anything else the client toasts `msg` for,
// and the two below are the ones the recovered code treats specially.
const (
	StatusOK        = 200
	StatusParamErr  = 1001 // "参数异常" — the test server's answer to a missing or wrong Signature
	StatusTokenFail = 401  // the client's `token_fail` path: re-login
)

// Envelope is the body every route returns. `Data` is omitted when nil, which is what the client's
// `ApiResponse.data == null` branch expects on failures.
type Envelope struct {
	Status int    `json:"status"`
	Msg    string `json:"msg"`
	Data   any    `json:"data,omitempty"`
}

// OK wraps a payload as a success answer.
func OK(data any) Envelope { return Envelope{Status: StatusOK, Msg: "success", Data: data} }

// Fail wraps an error answer; the client shows `msg` verbatim, so it must be user-readable Chinese
// in the original's style.
func Fail(status int, msg string) Envelope { return Envelope{Status: status, Msg: msg} }

// TypeIDSMS is the login body's typeId for "verify code typed by hand"; 1 and 3 are the two one-tap
// vendor paths (GY token, FlyVerify opToken), which this backend cannot honour until those SDKs exist.
const (
	TypeIDOneTapToken = 1
	TypeIDSMS         = 2
	TypeIDNumberProbe = 3
)

// SignatureKey is the AES key the recovered client both encrypts with and uses as the IV. Raw UTF-8
// bytes, not hex — that detail is why a generic AES helper cannot be dropped in here.
const SignatureKey = "6R4aXTSK3OghHIgA"

var errShortBlock = errors.New("linkup/signature: ciphertext is not block aligned")

// SignatureInput is the exact string that gets encrypted:
//
//	encodedPath + "?" + encodedQuery [+ lowerHex(md5(body)) for non-GET requests with a body]
//
// The client signs the URL *after* it appended the 11 common query parameters, so the server must
// hash what it received, not a re-serialised version of it.
func SignatureInput(path, rawQuery, method string, body []byte) string {
	input := path + "?" + rawQuery
	if !strings.EqualFold(method, "GET") && len(body) > 0 {
		sum := md5.Sum(body)
		input += hex.EncodeToString(sum[:])
	}
	return input
}

// Sign encrypts input the way the client does: AES-128-CBC with PKCS#7 padding, IV == key,
// lower-case hex output.
func Sign(input string) (string, error) {
	padded, err := padPKCS7([]byte(input))
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher([]byte(SignatureKey))
	if err != nil {
		return "", err
	}
	out := make([]byte, len(padded))
	cipher.NewCBCEncrypter(block, []byte(SignatureKey)).CryptBlocks(out, padded)
	return hex.EncodeToString(out), nil
}

// Verify decrypts a signature and compares it to the expected input. A malformed signature is a
// plain false, never an error: the caller answers 1001 either way.
func Verify(signature, input string) bool {
	plain, err := Open(signature)
	return err == nil && plain == input
}

// Open decrypts a client signature back into the string that was signed.
func Open(signature string) (string, error) {
	raw, err := hex.DecodeString(strings.TrimSpace(signature))
	if err != nil {
		return "", fmt.Errorf("linkup/signature: signature is not hex: %w", err)
	}
	key := []byte(SignatureKey)
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	if len(raw) == 0 || len(raw)%block.BlockSize() != 0 {
		return "", errShortBlock
	}
	plain := make([]byte, len(raw))
	cipher.NewCBCDecrypter(block, key).CryptBlocks(plain, raw)
	unpadded, err := unpadPKCS7(plain, block.BlockSize())
	if err != nil {
		return "", err
	}
	return string(unpadded), nil
}

// padPKCS7 is Go's own PKCS#7 padding; for a 16-byte block cipher it is byte-identical to the
// "PKCS7PADDING" the client requested and to what JCE calls PKCS5Padding.
func padPKCS7(in []byte) ([]byte, error) {
	n := aes.BlockSize - len(in)%aes.BlockSize
	if n <= 0 || n > aes.BlockSize {
		return nil, fmt.Errorf("linkup/signature: bad pad length %d", n)
	}
	out := make([]byte, len(in)+n)
	copy(out, in)
	for i := len(in); i < len(out); i++ {
		out[i] = byte(n)
	}
	return out, nil
}

func unpadPKCS7(in []byte, blockSize int) ([]byte, error) {
	if len(in) == 0 || len(in)%blockSize != 0 {
		return nil, errShortBlock
	}
	n := int(in[len(in)-1])
	if n == 0 || n > blockSize || n > len(in) {
		return nil, errors.New("linkup/signature: bad PKCS#7 padding")
	}
	for _, b := range in[len(in)-n:] {
		if int(b) != n {
			return nil, errors.New("linkup/signature: bad PKCS#7 padding bytes")
		}
	}
	return in[:len(in)-n], nil
}
