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

# 回归：34 个测试，全部离线，不需要 Docker 也不需要网络
go test ./...

# 真进程 + 真 socket：28 条用例，签名由第三个实现（openssl）产出
bash scripts/smoke.sh

# 起一个只用内存的本地网关
go run ./cmd/server -dev -store= -openim-secret= -dev-sms-code 000000
```

`smoke.sh` 存在的理由不是重复 `go test`，而是两件 `go test` 证明不了的事：`cmd/server` 的 flag、
密钥文件、store 接线真的能起一个可用的监听；以及签名在 **Go / JVM 客户端 / OpenSSL 三个实现**下产出
同一段密文（JVM 的样本向量已经钉在 `internal/contract/contract_test.go`）。

## 三层结构

| 包 | 职责 | 依据 |
|---|---|---|
| `internal/contract` | 信封、AES 签名、JWT、RSA 公钥 PEM | 1.6.6 反编译 + 真实测试服应答 + JDK 产出的黄金样本 |
| `internal/openim` | OpenIMServer 的 REST 客户端（管理员令牌、关系域 8 个调用） | `github.com/openimsdk/protocol` 模块里的真实结构体字段名 |
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

## OpenIM：代码写完并测过，真栈**还没跑过**

`internal/openim` 的路径与字段名是照着 `github.com/openimsdk/protocol` 的真实结构体核对的
（`v3.8.3-patch.9`），测试用一个 httptest 打桩按同样的字段名应答：令牌缓存、`operationID` 头、
`{errCode,errMsg,errDlt,data}` 信封、`friendsInfo`/`FriendRequests` 这两种不规则的 key、分页嵌套
`pagination{pageNumber,showNumber}`、"不可达"与"被拒绝"两种失败的区别。

**真容器一次都没起过**，原因很实际：Docker 的 data-root 在 C 盘，而 C 盘只剩 7.2 GB（99% 满），
OpenIM 那一套镜像（mongo:7.0.28、redis:7.0.0、bitnamilegacy 的 kafka/minio/etcd、
openim/openim-server:v3.8.3-patch.18）解压约 2.5–3.5 GB。所以在把 Docker 磁盘迁到 D 盘之前，
"网关 ↔ 真 OpenIM"只能算**待验**，不能算绿。迁好之后的验收步骤写在
`recovered/DESIGN_CN.md` 的待验清单里。

未跑真栈的情况下，`smoke.sh` 是**故意**把 `-openim` 指到一个死端口的：最后两条用例证明 OpenIM 不可达时
网关答 `{"status":500,"msg":"服务暂时不可用，请稍后重试"}`，而不是一个看起来很像成功的空好友列表。

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
