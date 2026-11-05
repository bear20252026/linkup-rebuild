#!/usr/bin/env bash
# smoke.sh runs the rebuilt gateway as a real process on a real socket and drives it with curl.
#
# Two things this proves that `go test` cannot:
#   - the boot flags, RSA key file and store wiring in cmd/server really do start a usable listener
#   - the signature here is produced by a *third* implementation (openssl, on the shell), so a matching
#     answer means Go, the JVM client and OpenSSL agree on the recovered AES-128-CBC contract. The JVM
#     vectors themselves are pinned in internal/contract/contract_test.go.
#
# The gateway is pointed at a dead OpenIM address on purpose: the last cases show that an unreachable
# relation backend is answered as an error, never as an empty friend list.
#
# Usage:  bash server/scripts/smoke.sh [addr]
set -uo pipefail

ADDR="${1:-127.0.0.1:8098}"
HOST="http://${ADDR}"
KEY_HEX=$(printf '%s' '6R4aXTSK3OghHIgA' | xxd -p -c 64 | tr -d '\n')
DEV_CODE=000000
WORK=$(mktemp -d)
BIN="$WORK/linkup-gateway.exe"
PASSED=0
FAILED=0

# sign <method> <path[?query]> [body-file] — the recovered client signs
# encodedPath + "?" + encodedQuery, plus lower-hex md5(body) for anything but GET.
sign() {
	local method="$1" target="$2" bodyfile="${3:-}" input
	if [[ "$target" == *"?"* ]]; then
		input="$target"
	else
		input="${target}?"
	fi
	if [[ "$method" != GET && -n "$bodyfile" ]]; then
		input+=$(md5sum "$bodyfile" | cut -d' ' -f1)
	fi
	printf '%s' "$input" | openssl enc -aes-128-cbc -K "$KEY_HEX" -iv "$KEY_HEX" | xxd -p -c 256 | tr -d '\n'
}

check() { # check <name> <expected-substring> <actual>
	if [[ "$3" == *"$2"* ]]; then
		printf '  ok    %-58s\n' "$1"
		PASSED=$((PASSED + 1))
	else
		printf '  FAIL  %s\n        expected to contain: %s\n        got: %s\n' "$1" "$2" "${3:0:400}"
		FAILED=$((FAILED + 1))
	fi
}

echo "== build"
(cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" && go build -o "$BIN" ./cmd/server) || exit 1

echo "== boot $HOST (openim deliberately unreachable, store in memory)"
"$BIN" -dev -addr "$ADDR" -store= -openim http://127.0.0.1:1 -openim-secret= \
	-rsa-key "$WORK/linkup-rsa.key" \
	-dev-sms-code "$DEV_CODE" -cut-login-type 9 -friend-limit 3 \
	>"$WORK/server.log" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; rm -rf "$WORK"' EXIT

for _ in $(seq 40); do
	curl -fsS -o /dev/null "$HOST/api/sms/login/cut" && break
	sleep 0.25
done

echo "== cases"
check 'the envelope key was generated and persisted' 'BEGIN RSA PRIVATE KEY' "$(head -1 "$WORK/linkup-rsa.key" 2>/dev/null)"
check 'boot log announces the listener' 'linkup gateway listening' "$(cat "$WORK/server.log")"
check 'the dev sms code is announced rather than silent' 'dev sms code is active' "$(cat "$WORK/server.log")"
check 'an empty jwt secret is substituted and logged' 'development JWT secret' "$(cat "$WORK/server.log")"

CUT=$(curl -s "$HOST/api/sms/login/cut")
check 'cut answers a request carrying no headers at all' '"loginType":"9"' "$CUT"

UNSIGNED=$(curl -s "$HOST/api/client/config")
check 'config refuses an unsigned call, like the test server' '"status":1001' "$UNSIGNED"
check 'the refusal is the recovered 参数异常 wording' '参数异常' "$UNSIGNED"

SIGNED=$(curl -s -H "Signature: $(sign GET /api/client/config)" "$HOST/api/client/config")
check 'config answers an openssl-signed call' '"status":200' "$SIGNED"
check 'config is up before anyone has a session' 'notRelativesLockShow' "$SIGNED"

NO_TOKEN=$(curl -s -o /dev/null -w '%{http_code}' -H "Signature: $(sign GET /api/user/info)" "$HOST/api/user/info")
check 'a signed call with no session is HTTP 401' '401' "$NO_TOKEN"

printf '%s' "{\"typeId\":\"2\",\"phone\":\"13800008000\",\"smsCode\":\"$DEV_CODE\",\"region\":\"+86\"}" >"$WORK/login.json"
LOGIN=$(curl -s -H 'Content-Type: application/json' --data-binary "@$WORK/login.json" "$HOST/api/sms/click_to_login")
check 'the sms path (unsigned, typeId as a string) logs in' '"status":200' "$LOGIN"
check 'login carries a token' '"token":"' "$LOGIN"
check 'login marks the account registered' '"isRegister":true' "$LOGIN"
check 'the phone comes back whole, as the client stores it' '"phone":"13800008000"' "$LOGIN"

TOKEN=$(printf '%s' "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
USER_ID=$(printf '%s' "$LOGIN" | sed -n 's/.*"userId":\([0-9]*\).*/\1/p')

INFO=$(curl -s -H "Signature: $(sign GET /api/user/info)" -H "Authorization: $TOKEN" "$HOST/api/user/info")
check 'user/info takes the raw token as Authorization' '"status":200' "$INFO"
check 'user/info reports the id login handed out' "\"userId\":$USER_ID" "$INFO"
check 'user/info does not re-issue a token' '"token":""' "$INFO"

# A POST with a body: the signature has to cover path?query plus the lower-hex md5 of the exact bytes
# curl sends, which is the half of the contract a GET cannot exercise.
printf '%s' '{"avatar":"https://x/me.png","nickname":"妈妈","sex":2,"stage":3}' >"$WORK/profile.json"
UPDATED=$(curl -s -H 'Content-Type: application/json' -H "Signature: $(sign POST /api/user/update/info "$WORK/profile.json")" \
	-H "Authorization: $TOKEN" --data-binary "@$WORK/profile.json" "$HOST/api/user/update/info")
check 'a body-signed POST is accepted with the md5 of exactly those bytes' '"status":200' "$UPDATED"
check 'the profile wizard step round-trips' '"stage":3' "$UPDATED"
check 'the nickname round-trips' '"nickname":"妈妈"' "$UPDATED"

REPLAY=$(curl -s -H "Signature: $(sign GET '/api/user/info?x=1')" -H "Authorization: $TOKEN" "$HOST/api/user/info")
check 'a signature that does not cover the query actually sent is refused' '"status":1001' "$REPLAY"

MATCHED=$(curl -s -H "Signature: $(sign GET '/api/user/info?x=1')" -H "Authorization: $TOKEN" "$HOST/api/user/info?x=1")
check 'that same signature passes once the query matches' '"status":200' "$MATCHED"

PUB=$(curl -s -H "Signature: $(sign GET /api/tool/rsa/public_key)" -H "Authorization: $TOKEN" "$HOST/api/tool/rsa/public_key")
check 'the contact-match envelope key answers' '"publicKey"' "$PUB"
if [[ "$PUB" == *'-----END PUBLIC KEY'* ]]; then
	printf '  FAIL  the public key footer\n        the client strips four dashes; a five-dash footer leaves a "-" and breaks its Base64\n'
	FAILED=$((FAILED + 1))
else
	printf '  ok    %-58s\n' 'the footer is the four-dash form the client strips'
	PASSED=$((PASSED + 1))
fi

LIST=$(curl -s -H "Signature: $(sign GET '/api/relation/list?page=1&pageSize=20')" -H "Authorization: $TOKEN" "$HOST/api/relation/list?page=1&pageSize=20")
check 'an unreachable openim is answered as an error' '服务暂时不可用' "$LIST"
check 'and never as a successful empty friend list' '"status":500' "$LIST"

GATE=$(curl -s -o /dev/null -w '%{http_code}' "$HOST/api/does/not/exist")
check 'an unknown path hits the signature gate before a 404' '200' "$GATE"

echo "== restart with the same key file"
PORT=${ADDR##*:}
ADDR2="127.0.0.1:$((PORT + 1))"
HOST2="http://${ADDR2}"
kill $SERVER 2>/dev/null
wait $SERVER 2>/dev/null
"$BIN" -dev -addr "$ADDR2" -store= -openim http://127.0.0.1:1 -openim-secret= \
	-rsa-key "$WORK/linkup-rsa.key" -dev-sms-code "$DEV_CODE" \
	>"$WORK/server2.log" 2>&1 &
SERVER=$!
for _ in $(seq 40); do
	curl -fsS -o /dev/null "$HOST2/api/sms/login/cut" && break
	sleep 0.25
done
TOKEN2=$(curl -s -H 'Content-Type: application/json' --data-binary "@$WORK/login.json" \
	"$HOST2/api/sms/click_to_login" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
PUB2=$(curl -s -H "Signature: $(sign GET /api/tool/rsa/public_key)" -H "Authorization: $TOKEN2" "$HOST2/api/tool/rsa/public_key")
KEY1=$(printf '%s' "$PUB" | sed -n 's/.*"publicKey":"\([^"]*\)".*/\1/p')
check 'the persisted envelope key is reused, not regenerated' "${KEY1:0:120}" "$PUB2"

echo
if [[ $FAILED -eq 0 ]]; then
	echo "SMOKE OK - $PASSED cases passed over $HOST"
else
	echo "SMOKE FAILED - $FAILED of $((PASSED + FAILED)) cases"
	exit 1
fi
