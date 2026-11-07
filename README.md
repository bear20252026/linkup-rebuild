# LinkUp 重建工作区

源码丢失后，从 1.5.6 / 1.6.6 两个安装包恢复并重建**客户端与服务端**的工作区。

## 现在能跑的命令

```bash
# 客户端离线回归：409 个 JVM/Robolectric 测试（:location 93 + :net 31 + :app 285），不需要手机也不需要网络
sh tools/gradle.sh :location:testDebugUnitTest :net:test :app:testDebugUnitTest

# 轨迹页原生 UI 验收（只读模拟器 + 离线测试数据，不触网；见设计文档五点二十八）
sh tools/gradle.sh :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5580 shell am instrument -w com.youloft.watcher.debug.test/com.youloft.watcher.pages.track.TrackUiInstrumentation

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
| `location/` | 定位抽象层：一家 `LocationProvider`/`PlaceResolver`/`LocationGateway` 接口 + 系统源与百度源两家实现，无厂商定位/地图 SDK 依赖（使用 Kotlin 协程） |
| `app/` | Android 壳，包名沿用 `com.youloft.watcher`，已带入 1.6.6 的 25MB assets |
| `fastkit/` | 自研组件库的还原（弹窗基类、ShapeView、Spannable 工具等） |
| `server/` | 重建后端：Go 网关 + OpenIMServer 适配层 + 定位域（20 条路由） |
| `recovered/` | 分析与验收产物：路由表、API 契约、模块清单、设计文档 |
| `tools/` | 反编译与还原脚本、Gradle 包装、发布脚本（可重复执行） |
| `_raw/` | 两个 APK、反编译输出、apktool 输出（**体积大且含内部代码，不入库**） |

## 恢复状态

已验证：带签名的 GET 被真实服务端接受（`status=200`）；`location`/`net`/`app` 累计409个测试通过（本轮全量重跑 app 285，定位93/网络31未改动且 UP-TO-DATE）；`server` 49 个 Go 测试
+ 28 条真 socket 用例全绿；同一条签名契约在 **JVM 客户端、Go、OpenSSL** 三个实现下产出同一段密文
（JVM 样本向量钉在 `server/internal/contract`，POST 体的 md5 规则由 MockWebServer 离线锁定）。
**重建网关 ↔ 真 OpenIMServer 已跑通**：20 条端到端用例 + 3 个在线集成测试全绿；
**重建客户端 ↔ 重建网关已会师**：`gatewayVerify` 46 条全绿——真 Retrofit + 真签名 + 真 Gson 模型打自家服务端，含定位域整条链
（上报 → 天 → 当天轨迹 → 权限）。OpenIM 的原始应答
留证在 `recovered/openim_live_evidence_2026-11-05/`（真栈是把 Ubuntu WSL rootfs import 到 D 盘的独立
发行版里跑的，Docker Desktop 与 C 盘都没被碰）。

定位已经换成**一家接口**：`location/` 模块定义 `LocationProvider` / `PlaceResolver` / `LocationGateway`，
系统 `LocationManager` 实现已通过 Robolectric 测试，真机效果待验；百度实现用反射绑定（签名逐条对着 `recovered/baidu_location_api_156.txt`，
测试用同签名的 SDK 替身跑**生产反射代码**），接口层无厂商包依赖由 `LocationLayerIndependenceTest` 把门。
坐标换算逐行复现 `recovered/geo_golden_156.txt`（那份样本是拿原包 `h0.java` 的算术在 JDK 上真跑出来的）。

轨迹页已恢复原版 UI 外壳：全屏地图槽、44dp 标题、32dp 圆角面板、原图日期底纹与拖柄，
折叠123dp/半展开383dp/全展开三档随拖动渐变；日期为星期单字＋日号＋圆形选中背景，说明弹窗底部弹出。
列表侧功能不变：本人轨迹、15天日期条与数据圆点、摘要/五类连接线、点击选中取消、加载/空/错与刷新。
原生验收在只读模拟器（API33）用离线测试数据跑通：真拖动展开、日期/列表点击、底部弹窗、折叠、错误刷新与空态，
六阶段截图留证 `build/track-ui-shots/`；JVM 侧轨迹合计42条。地图容器仍明确标注尚未接入，不画假底图。
原版地图 SDK、VIP/隐私与 IM 提醒、后台采集及停留/离开判定仍未完成；模拟器不等于真机像素验收，见设计文档五点二十七/二十八、待验67–70。

未验证：**向真服发一条带 body 的签名 POST**（要真登录态，且容易撞上写接口，没做）；97 条里剩下的 80 条
路由字段与业务规则（需要线上源码）。逐条见 `recovered/DESIGN_CN.md` 第九节的待验清单。

## 红线

`_raw/` 与 `recovered/` 含公司产品内部结构与三方密钥。**不要把这个目录推到公开仓库。**
`*.key`（重建后端生成的 RSA 私钥）与 `linkup-store.json` 同样不入库。
探测公司资产只做只读；**不调 `/api/sms/send` 一类写接口**——那会发真短信。
