# LinkUp 重建工作区

源码丢失后，从 1.5.6 / 1.6.6 两个安装包恢复并重建**客户端与服务端**的工作区。

## 现在能跑的命令

```bash
# 客户端离线回归：264 个 JVM/Robolectric 测试（:net 29 + :app 235），不需要手机也不需要网络
sh tools/gradle.sh :net:test :app:testDebugUnitTest

# 实机验证：用重建的网络层向真实测试服发一条签名请求
sh tools/gradle.sh :net:liveVerify      # 期望打印 PROTOCOL_RECOVERED=1

# 出 APK
sh tools/gradle.sh :app:assembleDebug

# 服务端回归：34 个 Go 测试，全离线（不需要 Docker）
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
| `core-net/` | 1.5.6 时代 yftracker 自带的独立 HTTP 栈（定位上报走它，签名算法不同） |
| `server/` | 重建后端第一刀：Go 网关 + OpenIMServer 适配层（17 条路由） |
| `recovered/` | 分析与验收产物：路由表、API 契约、模块清单、设计文档 |
| `tools/` | 反编译与还原脚本、Gradle 包装、发布脚本（可重复执行） |
| `_raw/` | 两个 APK、反编译输出、apktool 输出（**体积大且含内部代码，不入库**） |

## 恢复状态

已验证：带签名的 GET 被真实服务端接受（`status=200`）；`net`/`app` 264 个测试全绿；`server` 34 个 Go 测试
+ 28 条真 socket 用例全绿；同一条签名契约在 **JVM 客户端、Go、OpenSSL** 三个实现下产出同一段密文
（JVM 样本向量钉在 `server/internal/contract`，POST 体的 md5 规则由 MockWebServer 离线锁定）。

未验证：**向真服发一条带 body 的签名 POST**（要真登录态，且容易撞上写接口，没做）；97 条路由逐条字段与
业务规则（需要线上源码）；**网关 ↔ 真 OpenIM 容器栈一次都没跑过**（本机 C 盘只剩个位数 GB，镜像放不下）。
逐条见 `recovered/DESIGN_CN.md` 第九节的待验清单。

## 红线

`_raw/` 与 `recovered/` 含公司产品内部结构与三方密钥。**不要把这个目录推到公开仓库。**
`*.key`（重建后端生成的 RSA 私钥）与 `linkup-store.json` 同样不入库。
探测公司资产只做只读；**不调 `/api/sms/send` 一类写接口**——那会发真短信。
