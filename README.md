# LinkUp 重建工作区

源码丢失后，从 1.5.6 / 1.6.6 两个安装包恢复并重建**客户端与服务端**的工作区。

## 现在能跑的命令

```bash
# 客户端离线回归：418 个 JVM/Robolectric 测试（:location 93 + :net 31 + :app 294），不需要手机也不需要网络
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

# 地图与 IM 模块回归
sh tools/gradle.sh :map:testDebugUnitTest :im:testDebugUnitTest

# 更多模块回归
sh tools/gradle.sh :app:testDebugUnitTest

# 服务端回归：全离线（不需要 Docker）
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
| `location/` | 定位抽象层：一家 `LocationProvider`/`PlaceResolver`/`LocationGateway` 接口 + 系统源与百度/高德三家实现（反射绑定），17 键调优与停留/离开状态机（`StayLeaveClassifier`），无厂商 SDK 依赖 |
| `map/` | 地图抽象层：`TrackMap`/`TrackMapProvider` 接口 + 百度/高德两家反射实现（WGS84→BD09/GCJ02 换算复用 `:location` 的 golden），无 AAR 时轨迹页维持"尚未接入"占位 |
| `im/` | IM 层：`ImClient` 接口 + OpenIM 官方 SDK 直连（`OpenImImClient`），SDK 类型不出本模块；映射层 JVM 可测 |
| `app/` | Android 壳，包名沿用 `com.youloft.watcher`，已带入 1.6.6 的 25MB assets |
| `fastkit/` | 自研组件库的还原（弹窗基类、ShapeView、Spannable 工具等） |
| `server/` | 重建后端：Go 网关 + OpenIMServer 适配层 + 定位域（20 条路由） |
| `recovered/` | 分析与验收产物：路由表、API 契约、模块清单、设计文档 |
| `tools/` | 反编译与还原脚本、Gradle 包装、发布脚本（可重复执行） |
| `_raw/` | 两个 APK、反编译输出、apktool 输出（**体积大且含内部代码，不入库**） |

## 恢复状态

**P1 安全包已落地（#46，2026-11-14）**：审计 P1 四项服务端闭环——①密码哈希 bcrypt + 双读
迁移（旧无盐 SHA-256 登录即升级，PasswordHash 落盘修复重启失效 bug）②登录限速（按用户名
5 错锁 15 分钟，锁定期内正确密码也拒）③短信码 4→6 位 + 重发不重置尝试计数 + 60s 发送冷却
（客户端对话框口径随行）④隐私旗标服务端强制（位置/通话/应用使用+敏感三域读全部过 Sens 旗标，
确认缺省全开）。待验 84 闭环。

**四象限待办域已接入（#44，2026-11-14）**：`four_quadrant_list` 是艾森豪威尔四象限**待办清单**
（非应用使用）——三路由（get_list 四组全量 / edit 新增·原位更新 / del）+ 待办屏重建（四组列表、
条目点击完成、象限 chip 单选添加），MoreFragment 待办卡接通——**待验 80 闭环**。台账 per-account
封顶 200，id 进程全局。真机项见 DESIGN 待验 90。

**敏感行为域已接入（#43，2026-11-14）**：监控数据面第三域（收尾）——安装/卸载/更新系统广播
采集（免权限 receiver）→ `api/sensitive/report` → store 台账（去重/封顶 200/日区过滤）→
`api/sensitive/user`(+days，关系校验) 与 `api/index` 的 sensitiveCard——**守护 Tab 敏感行为卡
点亮**（第七张）。事件类型表为本重建口径（原版不可恢复）；`server_timer` 语义不可恢复不建路由
（测试钉住落 404）。真机项见 DESIGN 待验 89。

**应用使用域已接入（#42，2026-11-13）**：监控数据面第二域——UsageStatsManager 聚合（前台
配对时长/打开计数，碎片丢弃）→ `api/phone/app_use/report`（v1/v2 同台账，**总量口径 replace**
重报幂等，天数滚动 5 日封顶 400 行）→ `api/index` 的 `appUseCard`——**守护 Tab 应用使用卡
点亮**（今日 N 分/榜首应用/昨日对照，无台账收起）。权限是 Settings 的"使用情况访问权限"AppOp。
真机项见 DESIGN 待验 88。

**通话记录域已接入（#41，2026-11-13）**：监控数据面第一域四层贯通——客户端 CallLog delta
采集（READ_CALL_LOG 运行时授权，未授权静默跳过）→ `api/phone/call_record/report`（批量、
去重、per-account 封顶 200）→ store 台账 → `api/index` 的 `callRecordList`——**守护 Tab 通话
记录卡点亮**（最新一通/方向/时长/批次摘要，无记录收起）。真机项见 DESIGN 待验 87。

**聊天语音消息已接入（#40，2026-11-13）**：🎤 按住说话（MediaRecorder AAC，RECORD_AUDIO 运行
时请求）→ SDK 声音消息直连（上传与图片同链 OpenIMServer→MinIO，零服务端端点）→ 语音气泡按时长
渲染、点击播放（MediaPlayer 直播 URL）。`ImClient.sendVoice` 进接口，中性模型以 `durationSeconds`
分家语音与图片；会话列表语音尾巴读"【语音】"。真机项（各 ROM 权限流/弱网播放/端到端收发）见
DESIGN 待验 86。

**OSM 地图已接入（#39，2026-11-13）**：`osmdroid`（开源 MapView，Maven Central，免 key 免 AAR）
作为第三个 `TrackMapProvider` 落进 `:map`——**轨迹页与守护 Tab 的地图今日真实出图**，不再"尚未
接入"。底图为高德公开道路瓦片（GCJ02，复用既有 golden 转换），vendor AAR 到位之日自动让位
（`preferred()` 取首个已安装，厂商排在前面）。`:map` 独立性门放行 osmdroid 并保留 vendor
清零约束；`:map` 30 条测试全绿（含 9 条真实 osmdroid 类用例）。真机瓦片对齐/缓存/限流见
DESIGN 待验 85。

**账号体系加固已落地（#37–#39，2026-11-12）**：全面审计（`AUDIT.md`，P0×4/P1×5 全部记录）
之后的五项账号域改动——①第三方登录**开关暂停**（客户端 `ENABLE_THIRD_PARTY_LOGIN=false`
隐藏微信/QQ 入口、服务端 `-enable-third-login=false` 时 `api/user/login` 恒 403，代码不删，
恢复前置=厂商 OAuth 服务端校验，见待验 83）；②注册走 #31 已交付的用户名+密码页，本轮增强；
③邀请码模型（存在/未过期/未用尽，`RegisterWithUsername` 单锁原子、注册成功才扣减、重启不回填，
`-invite-code` 永续单码 + `-invite-codes-file` 多码，校验恒开）；④用户名实时查重
（`api/user/username/check` 与注册同口径 + 前端 400ms debounce 内联提示，正确性兜底是
store 唯一索引）；⑤登录不闪退（token 写入顺序本就正确，补四处异常兜底：repository 会话写
失败转失败回答、注册页写失败不进主界面、FGS start 包 runCatching、登录副作用包 runCatching）。
测试：server go test 全绿（含开关两态/邀请码四态/查重端点），:app 369 条全绿
（含 RegisterActivityTest 等 4 个新测试文件）。审计细节与残留项见 `AUDIT.md`。

**守护线三大块已落地（#27–#29）**：定位采集（前台服务 + 停留/离开状态机 + 三道门
隐私/权限/登录）、地图（`:map` 抽象 + 两家反射适配器，AAR 到位即亮，无 AAR 保持占位）、IM
（OpenIM SDK 直连 + `api/user/im_token` + friendList 缓存写入方 + 聊天页）。真机项（FGS 常驻、
SDK 登录收发、地图像素、模拟器↔WSL 连通）见 DESIGN 待验 71–76。

已验证：带签名的 GET 被真实服务端接受（`status=200`）；`location`/`net`/`app` 累计418个测试通过（本轮全量重跑 app 294，定位93/网络31未改动且 UP-TO-DATE）；`server` 49 个 Go 测试
+ 28 条真 socket 用例全绿；同一条签名契约在 **JVM 客户端、Go、OpenSSL** 三个实现下产出同一段密文
（JVM 样本向量钉在 `server/internal/contract`，POST 体的 md5 规则由 MockWebServer 离线锁定）。
**守护线主体已落地（#27–#33）**：采集（前台服务+停留/离开状态机）、地图（:map 抽象+两家反射适配器，
AAR 到位即亮）、IM（OpenIM SDK 直连+好友缓存写入方+图片媒体+锁机命令）、**主界面与启动**
（Splash 隐私门分流→引导→登录→四 Tab 主框架：守护/消息中心/更多/我的）、**锁机**（服务端台账+
IM 命令+应用内锁屏）、**widget 域**（设备状态台账+桌面卡读数）。真机项与剩余卡见 DESIGN
五点三十～三十四与待验 71–80。

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

首启隐私门已恢复：`isAgreePrivacyPolicy` 同意标志、`PrivacyPolicyDialog`（欢迎使用LinkUp，协议链接开网页）、
最小 `CommonWebActivity`（privacy/user 协议文档，fullscreen，只接受 http(s)）与 `EmptyLoginActivity`
（同意写标志并进登录；不同意仅隐藏遮罩；再点登录按钮弹挽留弹窗）。`LoginNavigation` 的网页跳转真正落地。
新增9条测试；Robolectric inflate 真实 WebView 会阻塞，故网页侧只断言跳转意图，真实渲染留待模拟器/真机。
Splash 分流与 GuideActivity 尚未重建（不伪造），一键登录/微信链依赖拿不到的厂商 jar，POST_NOTIFICATIONS 时机仍待拍板。见设计文档五点二十九。

未验证：**向真服发一条带 body 的签名 POST**（要真登录态，且容易撞上写接口，没做）；97 条里剩下的 80 条
路由字段与业务规则（需要线上源码）。逐条见 `recovered/DESIGN_CN.md` 第九节的待验清单。

## 红线

~~`_raw/` 与 `recovered/` 不要把这个目录推到公开仓库。~~
**2026-11-07 仓库所有者拍板：本仓库公开**，recovered/（路由契约、设计文档）与客户端内嵌密钥
（AES 签名 key、百度 AK——这些本来就嵌在每个已发布的 APK 里，可从任意安装包提取）随仓库公开；
`_raw/`（反编译输出）仍不入库，`*.key`（重建后端生成的 RSA 私钥）与 `linkup-store.json`（账号
台账）仍不入库——**服务端真正的秘密从来不在仓库里**，由启动旗标/环境变量注入。
探测公司资产只做只读；**不调 `/api/sms/send` 一类写接口**——那会发真短信。
