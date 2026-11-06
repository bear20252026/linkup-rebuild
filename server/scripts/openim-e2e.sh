#!/usr/bin/env bash
# openim-e2e.sh —— 待验 47/48/49 的验收脚本：重建网关接**真 OpenIMServer** 跑一遍加友闭环。
#
# 与 smoke.sh 的分工：smoke.sh 打的是死端口，证明"IM 不可达时报错而不是报空好友"；
# 这个脚本要求 OpenIM 真的在跑，证明"我们照 protocol 模块的 json tag 写的路径与字段名，
# 服务器真的认，也真的这么回"。它同时把 OpenIM 的**原始应答**写进 $EVIDENCE 目录，
# 因为 `FriendRequests` / `friendRequests` / `friendsInfo` 这三个键的拼法只能这样定案。
#
# 用法（OpenIM 栈见 server/README.md 的启动步骤；网关必须和 OpenIM 在同一个网络里可达）：
#   OPENIM_URL=http://127.0.0.1:10002 OPENIM_SECRET=<非默认密钥> bash server/scripts/openim-e2e.sh
# 没有 Go 工具链的机器（比如交叉编译好的 Linux 二进制）传 GATEWAY_BIN=... 跳过 build。
set -uo pipefail

OPENIM_URL="${OPENIM_URL:-}"
OPENIM_SECRET="${OPENIM_SECRET:-}"
GATEWAY_BIN="${GATEWAY_BIN:-}"
ADDR="${ADDR:-127.0.0.1:8095}"
HOST="http://${ADDR}"
DEV_CODE=135790
KEY_HEX=$(printf '%s' '6R4aXTSK3OghHIgA' | xxd -p -c 64 | tr -d '\n')
WORK=$(mktemp -d)
EVIDENCE="${EVIDENCE:-$WORK/openim-raw}"
# store 默认落在临时目录之外：IM 的 userID 直接取自账号 id，**删掉 store 会让 id 从 10001 重开，
# 而 OpenIM 那边的关系不会跟着清空**（实测踩过：第二轮的 add_friend 因此被"已经是好友"拒掉）。
# 换环境重测要连 IM 的库一起清（docker compose down -v），否则就得让 store 一直留着。
STORE="${STORE:-$HOME/linkup-e2e-store.json}"
BIN="${GATEWAY_BIN:-$WORK/linkup-gateway}"
PASSED=0
FAILED=0

if [[ -z "$OPENIM_URL" || -z "$OPENIM_SECRET" ]]; then
	echo "需要先有真 OpenIM：设 OPENIM_URL 与 OPENIM_SECRET（密钥不许是公开的 openIM123）"
	exit 2
fi
if [[ "$OPENIM_SECRET" == "openIM123" ]]; then
	echo "拒绝用公开的 compose demo 密钥跑验收（网关自己也会拒）"
	exit 2
fi
mkdir -p "$EVIDENCE"

sign() { # sign <method> <path[?query]> [body-file]
	local method="$1" target="$2" bodyfile="${3:-}" input
	if [[ "$target" == *"?"* ]]; then input="$target"; else input="${target}?"; fi
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
		printf '  FAIL  %s\n        expected to contain: %s\n        got: %s\n' "$1" "$2" "${3:0:500}"
		FAILED=$((FAILED + 1))
	fi
}

# api <method> <path[?query]> [json-body] [token] —— 照客户端的签名规则发一条请求
api() {
	local method="$1" target="$2" body="${3:-}" token="${4:-}"
	local path="${target%%\?*}" query="" args=()
	[[ "$target" == *"?"* ]] && query="${target#*\?}"
	local sig_input
	if [[ -n "$query" ]]; then sig_input="$target"; else sig_input="${path}?"; fi
	if [[ -n "$body" && "$method" != GET ]]; then
		printf '%s' "$body" >"$WORK/body.json"
		sig_input+=$(md5sum "$WORK/body.json" | cut -d' ' -f1)
	fi
	local sig
	sig=$(printf '%s' "$sig_input" | openssl enc -aes-128-cbc -K "$KEY_HEX" -iv "$KEY_HEX" | xxd -p -c 256 | tr -d '\n')
	args=(-s -X "$method" -H 'Content-Type: application/json' -H "Signature: $sig")
	[[ -n "$token" ]] && args+=(-H "Authorization: $token")
	[[ -n "$body" && "$method" != GET ]] && args+=(--data-binary "@$WORK/body.json")
	curl "${args[@]}" "$HOST$target"
}

# im <path> <json-body> —— 直接问 OpenIM，并把原始应答留证（待验 48 的证据就是这个目录）
IM_TOKEN=""
im() {
	local path="$1" body="$2" out name
	if [[ -z "$IM_TOKEN" ]]; then
		IM_TOKEN=$(curl -s -X POST "$OPENIM_URL/auth/get_admin_token" -H 'Content-Type: application/json' \
			-H 'operationID: e2e' \
			-d "{\"secret\":\"$OPENIM_SECRET\",\"userID\":\"imAdmin\",\"operationID\":\"e2e-bootstrap\"}" |
			sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
	fi
	out=$(curl -s -X POST "$OPENIM_URL$path" -H 'Content-Type: application/json' -H "token: $IM_TOKEN" \
		-H 'operationID: e2e' -d "$body")
	name=$(tr -dc 'a-zA-Z0-9' <<<"$path")
	printf '%s\n' "$out" >"$EVIDENCE/$name.json"
	printf '%s' "$out"
}

if [[ -n "$GATEWAY_BIN" ]]; then
	echo "== 用现成的网关二进制 $GATEWAY_BIN"
	[[ -x "$BIN" ]] || { echo "GATEWAY_BIN 不可执行：$BIN"; exit 2; }
else
	echo "== build gateway"
	(cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" && go build -o "$BIN" ./cmd/server) || exit 1
fi

echo "== start gateway against $OPENIM_URL"
"$BIN" -addr "$ADDR" -store "$STORE" -rsa-key "$WORK/rsa.key" \
	-openim "$OPENIM_URL" -openim-secret "$OPENIM_SECRET" -jwt-secret e2e-jwt-secret \
	-dev-sms-code "$DEV_CODE" -friend-limit 3 >"$WORK/gateway.log" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; rm -rf "$WORK"' EXIT
for _ in $(seq 40); do
	curl -fsS -o /dev/null "$HOST/api/sms/login/cut" && break
	sleep 0.25
done

RUN=$(date +%m%d%H%M%S)
LOGIN_A=$(api POST /api/sms/click_to_login "{\"typeId\":\"2\",\"phone\":\"139${RUN:2:8}\",\"smsCode\":\"$DEV_CODE\",\"region\":\"+86\"}")
check 'A 用短信登录真服链路成功' '"status":200' "$LOGIN_A"
TOKEN_A=$(printf '%s' "$LOGIN_A" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
ID_A=$(printf '%s' "$LOGIN_A" | sed -n 's/.*"userId":\([0-9]*\).*/\1/p')

PHONE_B="139$((80000000 + RANDOM % 19999999))"
LOGIN_B=$(api POST /api/sms/click_to_login "{\"typeId\":\"2\",\"phone\":\"$PHONE_B\",\"smsCode\":\"$DEV_CODE\",\"region\":\"+86\"}")
TOKEN_B=$(printf '%s' "$LOGIN_B" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
ID_B=$(printf '%s' "$LOGIN_B" | sed -n 's/.*"userId":\([0-9]*\).*/\1/p')
check 'B 也登录成功' '"status":200' "$LOGIN_B"
if [[ -z "$TOKEN_A" || -z "$TOKEN_B" || -z "$ID_A" || -z "$ID_B" ]]; then
	printf '  FAIL  两个账号没建立起来（A=%s B=%s），后面全部无意义\n' "$ID_A" "$ID_B"
	exit 1
fi
echo "        A=$ID_A B=$ID_B  原始应答留在 $EVIDENCE"

echo "== 1) A 申请加 B：关系必须真的落到 OpenIM"
APPLIED=$(api POST /api/relation/apply "{\"code\":$ID_B,\"relation\":1,\"sensSetting\":{\"sensLocation\":true,\"sensAppUse\":false}}" "$TOKEN_A")
check '网关 api/relation/apply 成功' '"status":200' "$APPLIED"
RECV=$(im /friend/get_friend_apply_list "{\"userID\":\"$ID_B\",\"pagination\":{\"pageNumber\":1,\"showNumber\":20},\"handleResults\":[0],\"operationID\":\"e2e\"}")
check '待验48：真服在 **FriendRequests** 键下回申请（大写 F）' '"FriendRequests"' "$RECV"
check '申请里的 fromUserID 就是 A' "\"fromUserID\":\"$ID_A\"" "$RECV"
check '申请里带的 reqMsg 到了 IM' '请求添加你为好友' "$RECV"
SENT=$(im /friend/get_self_friend_apply_list "{\"userID\":\"$ID_A\",\"pagination\":{\"pageNumber\":1,\"showNumber\":20},\"handleResults\":[0],\"operationID\":\"e2e\"}")
check '待验48：自发列表真服用小写 **friendRequests**' '"friendRequests"' "$SENT"
check '自发列表里能看到 A 发出的那条' "\"toUserID\":\"$ID_B\"" "$SENT"

echo "== 2) B 同意：网关与 IM 两侧都要看得见"
CONFIRMED=$(api POST "/api/relation/confirm?code=$ID_A" "{\"code\":$ID_A,\"relation\":2,\"sensSetting\":{\"sensLocation\":true,\"sensCall\":true,\"sensAppUse\":true}}" "$TOKEN_B")
check '网关 api/relation/confirm 成功' '"status":200' "$CONFIRMED"
FLIST=$(im /friend/get_friend_list "{\"userID\":\"$ID_B\",\"pagination\":{\"pageNumber\":1,\"showNumber\":20},\"operationID\":\"e2e\"}")
check '待验48：好友列表真服在 **friendsInfo** 键下回' '"friendsInfo"' "$FLIST"
check 'IM 侧 B 的好友里有 A' "\"userID\":\"$ID_A\"" "$FLIST"

LIST=$(api GET '/api/relation/list?page=1&pageSize=20&key=' '' "$TOKEN_B")
check '待验47：网关把真 IM 的好友列表解成了客户端的形状' "\"userId\":$ID_A" "$LIST"
APPLY_LIST=$(api GET '/api/relation/apply_list?page=1&pageSize=20' '' "$TOKEN_B")
check '待验47：网关的申请列表读的是真服' "\"userId\":$ID_A" "$APPLY_LIST"
INFO=$(api GET "/api/relation/info?queryId=$ID_A" '' "$TOKEN_B")
check '好友详情屏的数据也来自这条链' '"status":200' "$INFO"

echo "== 3) 备注与删除要真的穿过边界"
api POST /api/relation/update_remark "{\"friendId\":$ID_A,\"remark\":\"姐姐\"}" "$TOKEN_B" >/dev/null
REMARKED=$(im /friend/get_friend_list "{\"userID\":\"$ID_B\",\"pagination\":{\"pageNumber\":1,\"showNumber\":20},\"operationID\":\"e2e\"}")
check 'set_friend_remark 落到了真服' '"remark":"姐姐"' "$REMARKED"
LOCAL_REMARK=$(api GET '/api/relation/list?page=1&pageSize=20&key=' '' "$TOKEN_B")
check '网关把 IM 的备注读回给客户端' '"remark":"姐姐"' "$LOCAL_REMARK"
DELETED=$(api POST /api/relation/del "{\"friendId\":$ID_A}" "$TOKEN_B")
check 'api/relation/del 成功' '"status":200' "$DELETED"
AFTER=$(im /friend/is_friend "{\"userID1\":\"$ID_B\",\"userID2\":\"$ID_A\",\"operationID\":\"e2e\"}")
check '删除后 B 侧不再认为 A 是好友' '"inUser1Friends":false' "$AFTER"
check '删除是双向的：A 侧也不再认为 B 是好友' '"inUser2Friends":false' "$AFTER"

echo "== 4) 配额走的是网关的 flag，不是 IM 的"
QUOTA=$(api GET "/api/relation/pre_confirm?code=$ID_B" '' "$TOKEN_A")
check 'pre_confirm 仍能用 IM 之外的数据回答' '"status":200' "$QUOTA"

echo
echo "OpenIM 原始应答：$EVIDENCE（把这三个键的拼法写进 DESIGN 待验 48 就靠它）"
ls -1 "$EVIDENCE" 2>/dev/null | sed 's/^/  /'
if [[ $FAILED -eq 0 ]]; then
	echo "E2E OK - $PASSED cases passed against $OPENIM_URL"
else
	echo "E2E FAILED - $FAILED of $((PASSED + FAILED)) cases"
	exit 1
fi
