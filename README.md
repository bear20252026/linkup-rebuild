# LinkUp 重建工作区

源码丢失后，从 1.5.6 / 1.6.6 两个安装包恢复并重建**客户端与服务端**的工作区。

## 现在能跑的命令

```bash
# 客户端离线回归：266 个 JVM/Robolectric 测试（:net 31 + :app 235），不需要手机也不需要网络
sh tools/gradle.sh :net:test :app:testDebugUnitTest

# 客户端 ↔ 重建网关会师：30 条（网关要先起来；打的是本网关，不发真短信）
sh tools/gradle.sh :net:gatewayVerify --args="http://127.0.0.1:8090/ 000000"

# 实机验证：用重建的网络层向真实测试服发一条签名请求
sh tools/gradle.sh :net:liveVerify      # 期望打印 PROTOCOL_RECOVERED=1

# 出 APK
sh tools/gradle.sh :app:assembleDebug

# 服务端回归：49 个 Go 测试，全离线（不需要 Docker）
cd server && go test ./...

# 服务端真进程 + 真 socket：28 条用例，签名由 openssl 产出
bash server/scripts/smoke.sh
```

`tools/gradle.sh` 直接用本机 `~/.gradle` 里已缓存的 Gradle 9.3.1，所以不用装 Gradle；需要 JDK 17+。
`server/` 需要 Go 1.27+。

## 目录

| 路径 | 内容 |
|---|---|
| `net/` | 纯 JVM 网络层模块：AES 签名、公共参数拦截器、97 条路由的 Retrofit 接口 |
| `app/` | Android 壳，包名沿用 `com.youloft.watcher`，已带入 1.6.6 的 25MB assets |
| `fastkit/` | 自研组件库的还原（弹窗基类、ShapeView、Spannable 工具等） |
| `server/` | 重建后端：Go 网关 + OpenIMServer 适配层 + 定位域（20 条路由） |
| `recovered/` | 分析与验收产物：路由表、API 契约、模块清单、设计文档 |
| `tools/` | 反编译与还原脚本、Gradle 包装、发布脚本（可重复执行） |
| `_raw/` | 两个 APK、反编译输出、apktool 输出（**体积大且含内部代码，不入库**） |

## 恢复状态

已验证：带签名的 GET 被真实服务端接受（`status=200`）；`net`/`app` 266 个测试全绿；`server` 49 个 Go 测试
+ 28 条真 socket 用例全绿；同一条签名契约在 **JVM 客户端、Go、OpenSSL** 三个实现下产出同一段密文
（JVM 样本向量钉在 `server/internal/contract`，POST 体的 md5 规则由 MockWebServer 离线锁定）。
**重建网关 ↔ 真 OpenIMServer 已跑通**：20 条端到端用例 + 3 个在线集成测试全绿；
**重建客户端 ↔ 重建网关已会师**：`gatewayVerify` 46 条全绿——真 Retrofit + 真签名 + 真 Gson 模型打自家服务端，含定位域整条链
（上报 → 天 → 当天轨迹 → 权限）。OpenIM 的原始应答
留证在 `recovered/openim_live_evidence_2026-11-05/`（真栈是把 Ubuntu WSL rootfs import 到 D 盘的独立
发行版里跑的，Docker Desktop 与 C 盘都没被碰）。

未验证：**向真服发一条带 body 的签名 POST**（要真登录态，且容易撞上写接口，没做）；97 条里剩下的 80 条
路由字段与业务规则（需要线上源码）。逐条见 `recovered/DESIGN_CN.md` 第九节的待验清单。

## 红线

`_raw/` 与 `recovered/` 含公司产品内部结构与三方密钥。**不要把这个目录推到公开仓库。**
`*.key`（重建后端生成的 RSA 私钥）与 `linkup-store.json` 同样不入库。
探测公司资产只做只读；**不调 `/api/sms/send` 一类写接口**——那会发真短信。
