# server/ —— 后端重建第一刀（Go 网关 + OpenIMServer 适配层）

客户端已经会说的这套 HTTP 契约，服务端照它重建：**路径、`{status,msg,data}` 信封、AES 签名头、JWT
都不改**；好友关系与消息状态放进 OpenIMServer，OpenIM 存不下的 LinkUp 自有字段（关系类型、九项共享
开关、备注、验证码、配额）留在本地文件。

这一刀覆盖 **17 条路由**（GET 9 / POST 8）：登录域、客户端配置、RSA 公钥、关系域全套。其余 80 条
（定位、锁机、截图、纪念日、VIP/支付、上传、风控……）还没动——它们大多是"业务表 + 推送"，不阻塞
"两台设备能加上好友"这条主链路。

## 现在能跑的命令

```bash
cd server

# 回归：36 个测试，全部离线，不需要 Docker 也不需要网络
go test ./...

# 真进程 + 真 socket：28 条用例，签名由第三个实现（openssl）产出
bash scripts/smoke.sh

# 真栈端到端：20 条用例（要求 OpenIMServer 在跑，见下面"把真栈跑起来"）
OPENIM_URL=http://127.0.0.1:10002 OPENIM_SECRET=<非默认密钥> bash scripts/openim-e2e.sh

# 在线集成测试：没给环境变量就自动 skip，不会拖红上面的离线套件
LINKUP_OPENIM_URL=http://127.0.0.1:10002 LINKUP_OPENIM_SECRET=<非默认密钥> \
  go test ./internal/openim -run Live -v

# 起一个只用内存的本地网关
go run ./cmd/server -dev -store= -openim-secret= -dev-sms-code 000000
```

`smoke.sh` 存在的理由不是重复 `go test`，而是两件 `go test` 证明不了的事：`cmd/server` 的 flag、
密钥文件、store 接线真的能起一个可用的监听；以及签名在 **Go / JVM 客户端 / OpenSSL 三个实现**下产出
同一段密文（JVM 的样本向量已经钉在 `internal/contract/contract_test.go`）。
`openim-e2e.sh` 则只有一件事：证明**服务器真的认**我们照协议写的路径与字段名。

## 三层结构

| 包 | 职责 | 依据 |
|---|---|---|
| `internal/contract` | 信封、AES 签名、JWT、RSA 公钥 PEM | 1.6.6 反编译 + 真实测试服应答 + JDK 产出的黄金样本 |
| `internal/openim` | OpenIMServer 的 REST 客户端（管理员令牌、关系域 8 个调用） | 跑着的 `openim-server:v3.8.3-patch.18` 实测 + `openimsdk/protocol v0.0.73-alpha.12` 的 json tag |
| `internal/store` | OpenIM 存不下的那部分：账号 / 匹配码 / 每方向的 extras / 验证码 / 配额 | 客户端字段名与调用点 |
| `internal/gateway` | 17 条路由 + 两个中间件（签名、会话） | `recovered/api_166_contract.md` |
| `internal/dto` | 客户端 Gson 解析的 JSON 形状 | 两个 APK 的 bean 字段名 |

## 哪些是抄来的、哪些是我定的

这是本目录最重要的一张表。分不清这两类的地方，代码里都写了注释。

**照客户端，改不了（改了就登录不上 / 字段读不到）**

- 路径与 query/body 的放法：`api/relation/confirm` 的 `code` 在 **query**（`@Query("code")` + body 里
  重复一份），`pre_confirm` 用 `code`、`relation/info` 用 `queryId`、`relation/list` 用 `page/pageSize/key`
- 信封字段名 `status/msg/data`；`status=200` 成功、`1001 "参数异常"`、HTTP `401` 走客户端的 `token_fail`
- 签名 = `AES-128-CBC/PKCS#7( encodedPath + "?" + encodedQuery [+ lower-hex md5(body)] )`，
  **key 与 IV 都是** `6R4aXTSK3OghHIgA` 的原始 UTF-8 字节，输出小写 hex
- 客户端自己不签名的路径：路径分段里有 `login` 或 `sms`（`api/client/config` **是要签名的**）
- `Authorization` 放的是**裸 JWT**，没有 `Bearer ` 前缀
- `typeId` 在 JSON 里是**字符串**；`api/user/login` 的 `platform` 是 `"4"`/`"5"` 字符串
- RSA 公钥必须输出**四个横杠**的结束标记 `----END PUBLIC KEY-----`：客户端就剥这个字面量，标准五横杠
  会留下一个 `-`，Base64 解不开，然后静默走 10086 分支

**我定的，APK 里没有答案（换人来实现会得出不同值，所以每条都能用 flag 改掉）**

- `-friend-limit`（默认 `-1` = 不配额）：免费用户能加几个好友，客户端只读 `isFriendLimit`，原值不可观测
- `-cut-login-type`（默认 `9` = 不走一键登录）：GY / FlyVerify 两个 SDK 都拿不到，答 `1`/`3` 是骗客户端
  它有条走不通的路
- `1002` 这个"该用户不存在"的状态码：客户端只判 `status != 200` 然后 toast `msg`，原码不可观测
- `api/client/config` 的九个开关取值：全部是运营决定，默认取**客户端自己的 fallback 值**，
  IM 群 id 留空而不是编一个
- `permission` 里 `all` 一个字段摊到 8 个开关：客户端存的是三个粒度，服务端原来怎么分组看不见
- 好友配额按 **extras 行数**算（申请未通过不计数），因为 `apply` 时不写 extras
- confirm 时**两个方向各留各的选择**：申请方的 relation/开关来自它自己 `apply` 提交的那份

**未知，留给真服对比**：`api/client/config` 线上答 12 个键（客户端模型只有 9 个）；一键登录 typeId 1/3
的真实入参；`relation/main_user`、`match_by_phone` 这些没实现的路由的字段。

## 与线上现状两处**刻意**不同

1. **签名在这里是强制的。** 2026-11-02 实测：生产网关 `link.tttick.com` 根本没启用签名中间件，不带
   `Signature` 也能拿到真配置（`recovered/DESIGN_CN.md` §七），只有测试服会以 `1001 参数异常` 拒。重建
   版按测试服的行为校验，即客户端密钥在这台服务器上重新有意义。要不要给线上补这道闸，是单独一件要
   拍板的事。
2. **typeId 1 / 3 直接拒绝，不假装成功。** 一键登录要厂商 SDK；`api/sms/click_to_login` 只认 `typeId=2`。

## OpenIM：真栈跑过了，而且它当场纠正了我四条

`internal/openim` 的每个调用都对着**跑着的** `openim/openim-server:v3.8.3-patch.18` 验过：
20 条端到端用例（`scripts/openim-e2e.sh`）+ 3 个在线集成测试（`internal/openim/live_test.go`，
没真服时自动 skip，不拖红离线套件）。OpenIM 的原始应答留证在
`../recovered/openim_live_evidence_2026-11-05/`。

被真服纠正的四条（都是"照着名字想当然"那一类，httptest 打桩永远抓不到）：

| 我之前写的 | 真服说的 | 后果 |
|---|---|---|
| `get_admin_token` 发 `"userID":["imAdmin"]` | `cannot unmarshal array into Go struct field GetAdminTokenReq.userID of type string` | **每次 IM 调用都拿不到令牌**，关系功能全废。协议模块里 `UserID` 本来就是 `string`，是我读错 |
| `is_friend` 请求发 `{ownerUserID,friendUserID}` | `ArgsError: userID1 is empty`，字段是 **userID1/userID2** | 该调用必失败 |
| `is_friend` 响应读 `isFriend` | 回 **`inUser1Friends` / `inUser2Friends` 两个方向的旗标** | 读错键 ⇒ 永远 false，"已经是好友"提示不会出现 |
| `delete_friend` 调一次就算删干净 | 删除是**单向**的（另一边 `inUser2Friends` 仍 true） | 好友在另一台手机上复活。现在两个方向都删 |

另外两条实测口径：**不传 `handleResults` = 不过滤**（已同意的行也会回来，所以未读数只能按
`handleResult==0` 数）；重复注册回 `errCode 1102 / RegisteredAlreadyError`，我原来的 `IsAlreadyKnown`
只认 "exist" 子串，接不住这条。

`smoke.sh` 依然**故意**把 `-openim` 指到死端口：它证明的是另一件事——OpenIM 不可达时网关必须答
`{"status":500,"msg":"服务暂时不可用，请稍后重试"}`，而不是一个看起来像成功的空好友列表。

## 把真栈跑起来（本机验证环境，2026-11-05 实测步骤）

C 盘放不下 Docker 镜像（当时 7.2 GB / 99% 满），所以这套栈**不在 Docker Desktop 里跑**，
而是单独 import 一个装在 **D 盘** 的 WSL 发行版，让它自己跑 dockerd。全部字节落在
`D:\linkup-im\`，Docker Desktop 与它的 C 盘 vhdx 一个字节都没动，要撤就是 `wsl --unregister linkup-im`。

```bash
# 1) 取 Ubuntu 官方 WSL rootfs（校验 sha256 再 import，别跳过这步）
curl -O https://cloud-images.ubuntu.com/wsl/releases/noble/current/ubuntu-noble-wsl-amd64-24.04lts.rootfs.tar.gz
curl -O https://cloud-images.ubuntu.com/wsl/releases/noble/current/SHA256SUMS
sha256sum -c <(grep 24.04lts.rootfs.tar.gz SHA256SUMS)
wsl --import linkup-im 'D:\linkup-im\rootfs' '<下载下来的>.tar.gz' --version 2

# 2) 开 systemd；这台机器的 WSL 是 NAT 模式且**没有 IPv6 路由**，
#    不关 v6 的话 curl 会在 0 秒内失败（解析只回 AAAA），apt/镜像全拉不动
wsl -d linkup-im -u root -- bash -c 'printf "[boot]\nsystemd=true\n" > /etc/wsl.conf'
wsl --terminate linkup-im
wsl -d linkup-im -u root -- bash -c 'printf "net.ipv6.conf.all.disable_ipv6=1\nnet.ipv6.conf.default.disable_ipv6=1\n" >/etc/sysctl.d/90-no-ipv6.conf; sysctl --system >/dev/null'

# 3) 装 docker（Ubuntu 自带 29.1.3 + compose v2，不用加三方 apt 源）
wsl -d linkup-im -u root -- bash -c 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker.io docker-compose-v2'

# 4) Docker Hub 在这台机器上直连不通（registry-1.docker.io 连不上），走加速源
wsl -d linkup-im -u root -- bash -c 'mkdir -p /etc/docker && printf "{\"registry-mirrors\":[\"https://docker.1ms.run\"]}\n" > /etc/docker/daemon.json && systemctl restart docker'

# 5) 官方 compose 栈（tag v3.8.3-patch.1 里 pin 的正是 openim/openim-server:v3.8.3-patch.18）
git clone --depth 1 --branch v3.8.3-patch.1 https://github.com/openimsdk/openim-docker.git
#    改 .env 里的 OPENIM_SECRET（**不要用公开的 openIM123**，网关在 -dev 之外会拒绝它）
#    只起用得到的六个：mongo redis etcd kafka minio openim-server
```

**想回到干净库不能只靠 `docker compose down -v`**：这套 compose 的 `DATA_DIR=./`，数据是 **bind mount**
到 `components/mnt/` 的，删命名卷对它无效（实测：`down -v` 之后 IM 仍认得上一轮的账号与昵称）。
真要清空就 `docker compose down && rm -rf components/mnt && docker compose up -d …`。
连带一条：网关的 store 与 IM 的库要**一起**对待——删掉 store 让账号 id 从 10001 重开，而 IM 那边还留着
10001 的旧关系，下一轮的 `add_friend` 就会被"已经是好友"拒掉（这个坑我踩过一次）。

跑完在**外部**做两件事：

```bash
# a) 网关 ↔ 真 OpenIM 的加友闭环（待验 47/48/49），并把 OpenIM 的原始应答留证
OPENIM_URL=http://127.0.0.1:10002 OPENIM_SECRET=<那个非默认密钥> bash server/scripts/openim-e2e.sh

# b) 照协议模块 json tag 写的路径/字段名，逐条对真服做集成测试（默认 skip，不会拖红离线套件）
LINKUP_OPENIM_URL=http://127.0.0.1:10002 LINKUP_OPENIM_SECRET=<同上> \
  go test ./internal/openim -run Live -v
```

`internal/openim/live_test.go` 平时是 **skip** 的：`go test ./...` 在没有 Docker 的机器上仍然全绿，
但它把"我们对协议 tag 的读数"和"服务器真的这么回"分开钉住了——httptest 打桩只能证明前者。

## 安全边界（这个目录不会做什么）

- **发不出一条真短信。** 没有任何短信通道：`api/sms/send` 只会把 4 位验证码写进 store 并打进日志，
  `-dev-sms-code` 设了就连码都不发。这也顺带守住了"绝不碰写接口"那条红线。
- 启动**拒绝**用 OpenIM 公开 compose 里那个 `openIM123` 默认密钥、拒绝空 JWT secret，除非显式 `-dev`；
  `-dev` 下顶替的两个值每次都打进日志。
- 落盘的 RSA 私钥以 `0600` 请求创建。本机实测：**Windows 不实现这些权限位，文件落成 `644`**；这条只在
  Linux 部署时真正生效。`*.key` / `linkup-store.json` 已进 `.gitignore`，任何平台都不会被提交。
- 验证码 5 分钟过期、错 5 次作废、一次性的（兑换即删）。`maxCodeAttempts` 是运营选择，不是恢复值。
- `api/client/config`、`api/sms/login/cut`、`api/sms/send`、`api/sms/click_to_login`、`api/user/login`
  五条不要会话；其余一律 401。
