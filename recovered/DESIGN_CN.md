# LinkUp 客户端重建 —— 设计文档与工作量清单

> 编制日期：2026-11-02
> 依据：`com.youloft.watcher` 1.5.6 与 1.6.6 两个安装包的本机反编译产物 + 对活着的测试服做实机协议验证
> 工作区：`D:\linkup-rebuild`（原料在 `_raw/`，重建工程在 `net/` 与 `app/`）

---

## 一、一句话结论

**协议已经真的找回来了，而且是被重建出来的代码在真实服务器上跑通的**——`D:\linkup-rebuild` 里的新 Kotlin 网络层向 `linkup-test.51wnl-cq.com` 与生产网关 `link.tttick.com` 发出签名请求，都返回 `status=200 msg=success` 和真实业务配置。**后端不用重写**（第七节）。剩下的活是把 660 个手写业务文件重写成工程，这是排期问题，不是能不能的问题。

## 二、家底：现在磁盘上确定有的东西

| 资产 | 位置 | 状态 |
|---|---|---|
| 1.5.6 APK（119MB） | `_raw/com.youloft.watcher1.5.6.rebuilt.apk` | MD5 `8d8e19b9f7435e2f5b33355c9cc897d4` ✅ 与记录一致 |
| 1.6.6 APK（122MB） | `_raw/com.youloft.watcher1.6.6.apk` | MD5 `5ddd548a8b01b3b85fa1f9153c75d393` ✅ 与记录一致 |
| 1.5.6 反编译 Java（28031 文件） | `_raw/out_jadx_156/sources/` | 本机 jadx 1.5.6 产出，**业务类名可读** |
| 1.6.6 反编译 Java（28056 文件） | `_raw/src166/sources/` | Release 产物，业务类名被 R8 压成字母 |
| 1.6.6 布局/资源文本化 | `_raw/out_apktool_166/` | 936 个 layout（其中业务命名 327 个）、strings.xml 188KB |
| 1.5.6 资源与 assets | `_raw/res_156.zip`、`_raw/assets_156.zip` | 已在 Release |
| 1.6.6 assets（25MB） | `app/src/main/assets/` | **已拷进重建工程** |
| 路由表 | `recovered/routes_166.json`、`recovered/api_166_contract.md` | 97 条，动词与参数名已还原 |
| 模块清单 | `recovered/module_inventory.md` | 按文件数统计的真实工作量分布 |

### 之前卡住的地方，现在通了

原报告写"手机上没有 Java 运行时，反编译必须在 Mac mini 上跑，产物靠 GitHub Actions 中转"。**这台 Windows 机器上 JDK 21 + Android SDK + jadx + apktool + Gradle 全都有**，所以：

- 1.5.6 的反编译（原本从未做过）本机 3.5 分钟跑完
- 对测试服的协议验证从"改代码→推 commit→等 Actions 跑完→读日志"变成一条本地命令
- `gradle :net:liveVerify` 随跑随验

结论：不再需要 Mac mini，也不再需要 Actions 做侦察通道。

## 三、必须让代码说话的部分（已完成并验证）

### 3.1 网络层：`net/src/main/kotlin/com/youloft/linkup/net/`

严格照 1.6.6 的 `gh/d.java`（拦截器）与 `com.youloft.watcher.utils.a`（AESCBCUtil）逐行还原：

```
KEY = IV = "6R4aXTSK3OghHIgA"                 （16 字节直接当 UTF-8 用，不做 hex 解码）
Signature = hex( AES-128-CBC-PKCS7( 签名输入 ) )   ← 不再套 MD5
签名输入   = encodedPath + "?" + encodedQuery
POST 追加  = 签名输入 += md5Hex(请求体字符串).lowercase()
Authorization = JWT（头名已在 retrofit2/w.java 的错误串中确认）
```

11 个公共 query 参数，**顺序即协议**（原实现用 `addEncodedQueryParameter` 依次追加）：

```
tz, lang, userId, client, deviceId, channel, av, t, distinctId, shushuDeviceId, mainUser
```

两条豁免规则也照抄：

- 路径段里含 `login` 或 `sms` → 加公共参数，但**不加** Signature / Authorization
- host 以 `api.weixin.qq.com` 结尾 → 整个拦截器直接放行（微信换 token 用）

### 3.2 已验证 vs 未验证（这条很重要，不能混着报）

| 项 | 状态 |
|---|---|
| GET + 签名被服务端接受 | ✅ 实机 200，拿到真实配置 JSON |
| GET + 签名被**生产服**接受 | ✅ 实机 200（`link.tttick.com`，见第七节） |
| 未签名被拒（`参数av必须` / `1001`） | ✅ 实机复现 |
| 401 JWT 拦截说明签名关已过 | ✅ 实机复现 |
| AES 加解密自逆 | ✅ 单元测试 |
| 拦截器 11 参数顺序 / 两条豁免 / POST 体 md5 | ✅ 5 个 MockWebServer 单元测试，离线可跑 |
| **POST + 体 md5 被真服务端接受** | ⚠️ **未实机验证**——需要真实账号，见第八节 |
| 登录/好友/IM 等 90 余条路由的实际行为 | ⚠️ 未验证（同样需要登录态） |

`gradle :net:test` 5 tests / 0 failures；`gradle :net:liveVerify` 打印 `PROTOCOL_RECOVERED=1`。

复跑（测试服与生产服都可指）：

```bash
sh tools/gradle.sh :net:liveVerify                                  # 默认测试服
sh tools/gradle.sh :net:liveVerify --args=https://link.tttick.com/  # 生产服
```

### 3.3 整包已能编出 APK

本机 `gradle :app:assembleDebug` 产出 `app/build/outputs/apk/debug/app-debug.apk`（17.9MB），`aapt2 dump badging` 实测：

```
package: name='com.youloft.watcher.debug' versionCode='166' versionName='1.6.6'
minSdkVersion:'26'   targetSdkVersion:'34'   application-label:'LinkUp'
uses-permission: android.permission.INTERNET
```

壳里只有一个 `RecoveryProbeActivity`：上真机后自己发一条签名请求并把服务端返回的配置键打在屏幕上——它是"Android 侧协议通路"的活体探针，不是业务界面。

### 3.4 服务端真实回包已留证

`recovered/live_config_sample.json` 是测试服对重建签名的完整原始响应，`data` 共 12 个键：

```
notRelativesLockShow, halfScreenVipPageShow, vipMarketingCopy, ifCheckRegin,
profileSkipButton, autoReportAddressShow,
imGroupHuawei, imGroupIos, imGroupHonor, imGroupVivo, imGroupXiaomi, imGroupOppo
```

这 12 个键就是后端认下来的功能开关与环信分组配置，可直接作为重建工程 config 模型的种子。

### 3.5 遗留的一处不确定

`recovered/api_166_contract.md` 里有一个查询参数名是常量引用而非字面量（`SocializeConstants.KEY_PIC`，环信/友盟常量），重建时按 `"pic"` 试；若被拒再对齐。

## 四、1.5.6 与 1.6.6 的取舍（这决定抄哪份）

| | 1.5.6 | 1.6.6 |
|---|---|---|
| 业务类名 | **可读**（`pages/friend/…` 304 个文件仍在 `com/youloft/watcher/pages`） | 被 R8 压成字母，64% 文件是 1–3 字母壳 |
| 网络层 | 自研 `OkHttpManager`，走 `dmp.51wnl-cq.com`，旧密钥 `be2524844ec4bb25`，签名**多套一层 MD5** | Retrofit，走 `linkup-test.51wnl-cq.com`，新密钥，签名不套 MD5 |
| 服务器 | 定位上报服务在线 | **业务主 API 在线**（生产域名已指向静态桶，业务只剩测试环境） |
| 用途 | **行为参考**：读逻辑、读字段、读流程 | **协议与资源参考**：路由、签名、布局、assets |

所以重建的正确姿势是：**协议照 1.6.6，业务逻辑读 1.5.6**，两者互为对照。旧版密钥 `be2524844ec4bb25` 只服务定位上报 SDK（`com.youloft.yftracker`），不要混进主 API。

## 五、重建范围到底多大（按文件数，不按感觉）

`com/youloft/watcher` 下 1.5.6 可读业务文件 **981 个**，但其中 296 个是 DataBinding 生成物、`R.java` 一个文件 1.4MB——**真正要人重写的约 660 个文件**。

页面模块按文件数排（`pages/`）：

| 模块 | 文件 | 模块 | 文件 |
|---|---|---|---|
| friend | 48 | track | 14 |
| user | 35 | voice | 10 |
| im | 29 | mine | 9 |
| login | 24 | permission | 7 |
| vip | 23 | test | 7（**不用重建**，位置 Mock 调试页） |
| home | 19 | common | 6 |
| todo | 17 | protection | 6 |
| lock | 16 | camera | 5 |
| privacy | 14 | guide/main/risk | 3 each |

另有 `bean` 113、`view` 77、`utils` 60、`adapter` 29、`manager` 23、`widget` 13、`repository` 10、`datasource` 4、`service` 6。

组件面（1.6.6 Manifest 实测）：**237 Activity / 44 Service / 16 Receiver / 30 Provider / 73 权限**。

界面侧的实际情况比"235 个 Activity"这个数字乐观：

- 1.6.6 的 936 个 layout 里，**以 `<layout>` 为根的 DataBinding 布局 = 0 个**（1.5.6 反而有 296 个 DataBinding 生成类）。也就是说 1.6.6 已改用普通布局，**XML 可以基本原样迁进重建工程**，不需要重写绑定表达式。
- `strings.xml` 2169 条文案已文本化，可直接复用。
- 327 个布局是业务命名（`activity_/fragment_/dialog_/item_/view_` 前缀），其余 609 个来自三方库，不必迁。

> 原报告写"2~6 周（2-3 人）"。按上面的文件分布，我维持这个量级判断，但把 P1（网络层）划掉——它已经做完了。

## 五点五、资源银行 `:res166`（2026-11-02 已迁入并可链接）

1.6.6 的 `res/` 整棵搬进独立模块 `:res166`：**3982 个文件 / 37.8MB**，936 个布局、2169 条默认文案
（业务文案占绝对多数，像三方库的只有约 115 条）、`values-zh` 363 条、`values-zh-rCN` 195 条。
跳过 146 个纯语言目录（AppCompat/Material 翻译，接真 AAR 时自带）。`:app` 依赖它，
`R.layout.activity_login` / `R.string.input_address_tag_hint` 这类引用现在**直接可解析**。

三条硬约束，后来人别踩：

1. ~~`:app` 不能再同时依赖 androidx UI 库~~ **已解决**：`tools/strip_lib_res.py` 按各 AAR 里的真实资源清单
   把库自有条目从 `:res166` 剥掉，AAR 已全部接回（见第五点六节）。这条原本是硬约束：APK 的 `res/` 是合并后
   产物，已含 AppCompat/Material 的 `attrs.xml`/`styles.xml` 副本，两边同时在会触发 `attr/navigationMode`
   等重复定义——而且**多数重复藏在 `<declare-styleable>` 的子 `<attr>` 里，只看顶层条目会漏**。
2. **AGP 在 Windows 上会吞掉 aapt2 的真实报错**（`InvalidPathException: Illegal char <:>`）。
   要看原话，把 `merged.dir/values/values.xml` 拷成 `res/values/values.xml` 后直接
   `aapt2 compile --dir … -o out.zip`。
3. **`@array/side_bar_array` 与两个 `material_ic_keyboard_arrow_*` 不是缺失**，它们在
   `values/arrays.xml`、`values/drawables.xml` 里是 `<item type=… name=…/>` **空占位**（id 表保留、值被 shrink），
   对应布局没有任何代码路径 inflate。补定义反而撞重复——原样保留。

未剥离前的唯一编辑：`values/strings.xml#indicator_color_error` 里库文案的字面 `@` 转义成 `\@`，
清单见 `recovered/res_migration_deltas.json`。

## 五点六、AAR 已接回，界面在 JVM 上真 inflate 过（2026-11-02）

`tools/strip_lib_res.py` 用 Gradle 解析出的 `debugRuntimeClasspath` **真实 AAR 清单**做减法（不是前缀猜测，
传递依赖一并覆盖）：读每个 `.aar` 的 `res/**` 与 `res/values*/values.xml`，把「同 type 同 name」的条目
从 `:res166` 移除。关键细节：**多数 attr 重复藏在 `<declare-styleable>` 的子 `<attr>` 里**，只看顶层会漏；
处理方式是把它们**降级成引用**（`<attr name="x"/>`）而非删掉整个 styleable，否则业务自己的 styleable 被误伤。

剥离量：库标识 **5040** ⇒ 删 **770 个文件** + **3892 条 values 条目**
（style 1080、dimen 863、color 485+213、**attr 1263**、id 116、integer 71、anim 44、layout 114、drawable 348…）。
明细 `recovered/stripped_lib_resources.json`；未剥离的原始树仍在 `_raw/out_apktool_166/res`，可整棵重放。

接回依赖：appcompat 1.7.0、material 1.10.0、constraintlayout 2.1.4、recyclerview 1.3.1、viewpager2、
swiperefreshlayout、fragment/activity/lifecycle-ktx、core-ktx 1.13.0、lottie 6.4.0、
BaseRecyclerViewAdapterHelper 3.0.10（需 jitpack 仓库）。
`android.nonTransitiveRClass` 改回 **false**——`:app` 的 `R` 必须看得见 `:res166` 的资源。

**跑起来的证据**（Robolectric，不需设备、不碰网络）：`app/src/test/.../RecoveredUiTest.kt` 在 `Theme.Watcher`
下真 inflate 了还原出的 `activity_friend_request_list.xml`，断言 ConstraintLayout 根、
`layout_title`/`iv_back`/`iv_add_friend`/`recyclerView`/`emptyView` 齐全，且标题文案解析成「好友申请」。
这条比"XML 文本存在"强得多：**还原出的布局+样式+属性+图片引用在运行时能整体解开**。

同时重建了第一个真业务屏 `FriendRequestListActivity`（AppCompatActivity + LinearLayoutManager +
已类型化的 `apiRelationApplyList`）。全套 **9 tests / 0 failures**（5 协议 + 2 模型 + 2 界面 inflate），
`:app:assembleDebug` 出 **57.9MB** APK。

新发现的依赖缺口：`com.mc.fastkit`（**545 处引用**，`ShapedImageView` / `ShapedConstraintLayout` 等）
不在 Maven Central 或任何公开仓，基本可确定是**公司内部组件库**，源码也在本次丢失范围内。
好在 APK 里有：1.5.6 反编译出 91 个可读 `com/mc/fastkit/**` 文件（dialog/ext/log/ui/utils/view/widget）。
建议单开一张卡抽成 `:fastkit`；在那之前**列表行** `item_friend_request.xml` 还 inflate 不了。

复核：`:net:test` 5/0/0、`:net:liveVerify` `PROTOCOL_RECOVERED=1`、`:app:assembleDebug` 出 **50.6MB** APK
（内含 937 个布局、2272 条 string、`resources.arsc` 1.56MB）。**该 APK 能否正常启动仍未验**（本机无设备）。

## 五点七、`:fastkit` 已建，UI 可跑面已量化（2026-11-02）

新模块 `:fastkit`（namespace `com.mc.fastkit`）覆盖布局中出现的全部 14 个 fastkit 标签；
银行里 201 个 `mc_*` 顶层 attr 已够，布局用到的 78 个**零缺失**。`ShapeStyle` 按原语义实现
`mc_renderMode`（background/foreground）、四角独立半径、渐变（枚举值取自 attrs.xml）、虚线描边，
`ShapedImageView` 的 `mc_cornerSize` 同时支持 dimension 与 fraction。
**刻意没做**：`mc_font` 不套用 Typeface（原库有字体缓存，硬接会静默改字形）；`SheetLayout` 未实现
（带状态机与手势，空壳等于骗人），其 3 个布局仍在失败清单里。

`UiInflateSurvey` 把 829 个布局逐个在 Robolectric 上吹开，产出逐布局 CSV + 原因排名
（`recovered/ui_inflate_summary.json`）：

| 口径 | 总数 | 今天能 inflate |
|---|---|---|
| 全部布局 | 829 | 404（48%） |
| **业务命名布局** | 335 | **260（77%）** |
| 广告 SDK 自带布局 | 360 | 84（23%） |

425 个失败的归属分布：**广告 SDK 自带 UI 276**、其它三方 UI 库 58、**我方自有 View 43（25 个类）**、
环信 EaseUI 40、androidx 5、非类名 3。
⇒ 排期上只有那 **25 个自有 View** 属于"重写业务"，其余是"接回某个 SDK"。最挡路的：
`VoiceTimesView(5)`、`StrokeTextView(4)`、`PayTypeView(4)`、`lock.UnlockButton(3)`。

体检方法自己踩的两坑已修并记录：`<merge>` 需要 `attachToRoot=true`（误判 57 个），
以及必须取异常链最内层按 `Error inflating class X` 归类（否则 450 条失败全是同一句无信息量的话）。

## 六、架构决定（写死，避免重写时反复）

1. **包名保持 `com.youloft.watcher`**。三方后台配置（微信/QQ/环信/百度/各推送）都绑包名，换包名等于全部重申请。
2. **minSdk 26 / compileSdk 34 / targetSdk 34**，与原包一致。
3. **`:net` 是纯 JVM 模块**，Android 与单元测试都依赖它。设备状态通过 `DeviceContext` 接口注入，所以签名逻辑可以在没有手机的情况下回归。
4. Retrofit + OkHttp + Gson（原 1.6.6 即如此），不引 Room/Hilt 之前先把 `:net` 站稳。原包用了 Hilt 2.51.1、Room 2.6.1、MMKV，重建时在 `:app` 引入即可。
5. **响应 bean 暂用 `JsonElement`**，先把 97 条路由全部接上；字段结构按 1.5.6 的 `bean`（113 个可读文件）逐个补成 data class。这样不会出现"为了编译通过而瞎猜字段"。
6. 签名输入用 `encodedPath + "?" + encodedQuery`（原实现走 `java.net.URL.getPath()/getQuery()`，即已编码形态）。**重写时别改成解码形态**，否则签名静默失效。
7. **baseUrl 走配置，默认指向生产 `https://link.tttick.com/`**。1.6.6 把测试服地址 `ag.a.f1732c` 硬编码进了正式包，等于整个线上客户端在打测试环境——这是一处独立的发布事故，重建时不要再复制。三个候选地址已固化在 `net/.../LinkUpClient.kt` 的 `LinkUpEndpoints`：`PRODUCTION` / `API`(测试) / `TRACKER_LEGACY`(定位上报)。

## 六点五、API 面已强类型（2026-11-02）

`tools/gen_beans.py` 从 1.6.6 的 bean 生成 **79 个 Kotlin data class**（`api/dto/Dtos.kt`），
并重出 `LinkUpApi.kt`：**96/97 条路由强类型**。唯一那条 `JsonElement` 是忠实还原——
原代码把 `POST api/address/tag` 声明成 `ApiResponse<Object>`。请求体类型化 4 条，其余 40 条保持
`Map<String, Any?>`（原实现就发动态 map，不硬凑字段）。

依据：两个 APK 的 `@SerializedName` 都被 R8 抹掉，Gson 仍工作 ⇒ **JSON key == 字段名**；
`ChannelConfig` 的 9 个字段与生产实时回包一一对上，`RecoveredModelTest` 用抓包留证锁住解析。

三方 SDK 的类型（如腾讯 TuringFD 的 `Date<T>`）**不生成**，一律 `JsonElement`：那不是本产品的 API 面。
所有引用字段生成成可空默认 null——原 app 声明非空，但 Gson 缺字段会塞 null，照抄会 NPE。
详见 `report/08_typed_models_CN.md`。

## 七、后端现状：生产 API 其实还活着，后端不用重写

2026-11-02 本轮实测（我方自有资产，只读探测，未碰任何写接口）：

- 三个业务域名（`51wnl-cq.com` / `51wnl.com` / `tttick.com`）RDAP 查得注册商均为 Alibaba Cloud Computing (Beijing)，**全部续费到 2027–2029**——后端资产仍在付费维护，不是被放弃的产品。
- **生产业务 API 活在 `link.tttick.com`**。这个域名在 1.6.6 里被当作 H5/深链 origin 使用（`ag.a.f1733d`、`f1750u = /index/#/`），所以 `report/03` 把它记成了"深链服务"，漏掉它同时路由 `/api`。实测：
  - `GET /api/client/config` → **200 + 真实配置**，且 `notRelativesLockShow=false` 与测试服的 `true` 不同，说明是独立环境的真数据
  - `GET /api/user/info`、`/api/vip/index`、`/api/version/check`、`/api/sensitive/server_timer` → **401 permission denied**（已过路由、卡在 JWT，符合预期）
  - 重建的 `:net` 客户端把它当 baseUrl 直接跑通：`gradle :net:liveVerify --args=https://link.tttick.com/` → `PROTOCOL_RECOVERED=1`
- `linkup.51wnl-cq.com`（原生产域名）确认只剩火山引擎 OSS 静态桶（`NoSuchKey`，`server: Byte-nginx`）；`dmp.51wnl-cq.com` 只跑定位上报（Go，`404 page not found`）；`score.51wnl-cq.com` 是 IIS 默认页。
- Jenkins **没有暴露在公网**：`linkup-test.51wnl-cq.com/login`、`/jenkins/login` 都 404，`139.129.22.130:8080` 外部不可达。服务端源码只能在内网 CI 上找。

**结论修正**：`report/00` 写的"最大不确定性在后端"可以划掉。**后端不需要重写**，重建客户端把 baseUrl 指到 `link.tttick.com` 就能对接真实数据，97 条路由的字段对齐可以在生产服逐条打出来，不用靠猜和抓包。

**顺带要报给安全/运维的一条**：`link.tttick.com` 这台网关**没有启用 Signature 中间件**——不带 `Signature` 头的请求照样过（测试服会以 `参数异常1001` 拒）。线上 API 目前只有 JWT 一道闸，客户端密钥 `6R4aXTSK3OghHIgA` 在这台上形同虚设。客户端侧照已还原的算法继续签名不变，但**服务端要不要补齐签名校验需要单独拍板**：补齐才能挡住脚本直接打线上接口。

## 八、APK 里根本没有、只能人去要

1. **后端服务端源码**。好消息是**线上服务还活着、不必重写**（第七节），所以这份源码现在影响的是"维护与字段还原精度"，而不是"能不能上线"。线索很具体：服务名 `always_guard.api`，CI job `desyb_always_guard.api`，Go 中间件 `internal/middleware/signature.go` / `jwt.go`。**Jenkins 没暴露在公网，只能内网找**。
2. **签名 keystore**。丢了就只能换签名重新上架，老用户覆盖安装会失败。
3. **三方后台账号**：微信开放平台、QQ 互联、环信、百度地图、小米/华为/荣耀/OPPO/vivo/魅族/个推/Mob、穿山甲/广点通/百度联盟/快手/AnyThink、数数 ThinkingData、阿里云 SLS、友盟/Bugly。
   AppID 已从 APK 提取（`report/04` 第五节），可凭 AppID 直接向平台发起主体认领，不必等找到密码。
4. 原注释、Gradle 打包配置、CI 脚本。

## 九、我在这台机器上物理做不了的验收（交给你/团队）

| # | 待验项 | 为什么这里做不了 |
|---|---|---|
| 1 | POST 带体请求被真服务端接受 | 需要真实登录态与合法手机号；未登录时业务关在 JWT 之前还是之后无法区分 |
| 2 | 探针页在真机上显示 200 与配置键 | `app-debug.apk` 已编出，但这台机器没有连可装的设备；装包与看屏必须人来 |
| 3 | 定位上报旧链路（`dmp.51wnl-cq.com` + 旧密钥）仍可用 | 属另一套协议，尚未接入重建工程 |
| 4 | 97 条路由逐条字段对齐 | 需要登录态逐条打 |
| 5 | ~~生产后端是否还活着、归属谁~~ | **本轮已答**：归属我方，生产 API 活在 `link.tttick.com`（第七节） |
| 6 | 服务端 Go 源码在内网哪台机 | 公网探不到 Jenkins（`8080` 不可达、`/login` 404），只能内网找 CI 与仓库 |

## 十、下一步顺序（建议）

1. **今天**：内网找 `desyb_always_guard.api` 这个 Jenkins job 与它的 git 仓库地址。后端**不用重写**（第七节），但服务端源码决定了字段与业务规则的还原精度，也决定了能不能继续给老用户发版。
2. 把 baseUrl 从硬编码改成分环境配置，**默认指向生产 `link.tttick.com`**（1.6.6 把测试服地址打进了正式包，这本身就是一处发布事故）。
3. 把 1.6.6 的 936 个布局（业务命名 327 个）+ 2169 条文案迁入 `:app`（要先补齐 appcompat/material/lottie 等依赖，否则会引用不到）。
4. 按第五节文件数从大到小补 bean → repository → pages；**bean 的字段现在可以在生产服逐条打出来**，不必靠 1.5.6 猜；每补一个模块加一条 `:net:liveVerify` 的真实请求验证。
5. 找回 keystore；找不回就新签名 + 老用户重装方案。
6. 权限从 73 条砍到最小必要（`QUERY_ALL_PACKAGES`、`REQUEST_INSTALL_PACKAGES`、后台精确定位是上架审核与监管重点）。
7. 与安全/运维定：生产网关要不要补齐 Signature 校验（第七节末）。
