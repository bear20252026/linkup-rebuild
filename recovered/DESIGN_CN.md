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

**2026-11-02 第二轮补自有 View 后的最新数字**（详见 `report/11_app_views_batch_CN.md`）：
业务命名布局 **290 / 335 = 86%** 可 inflate（第一轮 77%），全部布局 434 / 829，
因自有 View 缺失而失败的布局从 43 降到 **6**。

一条要记住的判断依据：**类的包名必须以布局里那串全类名为准**，不是 1.5.6 的目录印象。
第一版我把 `VoiceTimesView`/`UnlockButton`/`AvatarsView` 等建在 `view` 根包，体检几乎不动（77%→81%）；
按 `view.voice` / `view.lock` / `view.mine` / `view.vip` 归位后立刻 274→287，还顺带暴露出被外层
遮住的内部缺失类。剩 6 个自有 View 各只挡 1 个布局，且都是真活（自绘开关、侧滑、WebView 封装、
需先接回 contrarywind 的 WheelView 等）。

## 五点八、自有 View 收尾（2026-11-02）

`view.SwitchButton` 经查是 kyleduo 的 vendored 副本（布局用的正是 `sb_*` 上游属性），
上一轮接回库后**薄封装委托**而非重造 29KB 自绘；`view.im.CameraButtonView` 自绘快门，
把原版的 `TimerTask` 换成 `ValueAnimator`；`webview.WebComponent` + `CommonWebView` 按可读到的
原始方法名还原容器 API，**JS bridge 与 protocol dispatcher 不写空壳**（留给 H5 容器卡片）。

业务屏可 inflate：**306 / 335 = 91%**；因自有 View 失败的布局从 8 降到 5。
剩 5 个不是一个量级的活：`ProductsView`、`VipCard`、`SlideRecyclerView`（侧滑触摸处理）、
`FixWheelView`（要先接回 contrarywind）、`ShotPreviewView`（1.5.6 无源）。
一条操作约束：**被布局引用的 asset 必须随仓库走**——43 个 Lottie/SVGA JSON（约 3.7MB）不在 git 里时，
干净 clone 会少 13 个业务布局（87% vs 91%），因为 Lottie 按 `lottie_fileName` 读不到文件就直接 inflate 失败；
其余 25MB 二进制仍走 `tools/stage_assets.sh` 还原。

UI 资源层"补控件就能解"的部分至此见底，再往上要么接厂商依赖（广告 276 + 环信 40），要么逐屏重建。
详见 `report/13_last_three_views_CN.md`。

## 五点九、逐屏重建的第一块垂直切片（2026-11-03）

第一屏选 **好友申请列表**（`FriendRequestListActivity`），因为它在 1.5.6 里是可读源码，
且把重建一屏需要的四类东西全碰上了：分页列表、行渲染状态机、写接口动作、跨屏跳转。

**这一屏的还原依据**（全部按代码，不按印象）：

| 事实 | 来源 |
|---|---|
| 分页状态在 Activity 而不是 ViewModel，`page` 从 1 起，`list.size < 20` 即到底 | `FriendRequestListActivity.java` `b.invokeSuspend` / `v0()` |
| 失败不推进 `page`，只 toast `tips_net_err` | 同上，`apiCall` 的 catch 分支 |
| 同意键有 500ms 去抖 | `A0()` 第一句 `System.currentTimeMillis() - f64688y < 500` |
| 四条前置闸门：过期 30 天 → 非 VIP 且服务端说满 → 对方已注销 → 对方自己满 | `A0()` 顺序 |
| 请求体是 `{code, relation, sensSetting}` 且 query 也带 `code` | `ApiDataSource$confirmAdd` 的 `a1.j0(...)` |
| 服务端不总回 `friendUserId`，客户端把申请者的 userId 回填进响应 | `FriendRequestListViewModel$a.invokeSuspend` |
| 行首圆角在**左**两角、行尾在**右**两角，半径 28dp；分割线末行是 `INVISIBLE` 不是 `GONE` | `t0.java` 的 `P().k/t` `P().l/r` 与 `setVisibility(4)` |
| 三态标签：`{1,4}`→已添加；`status≠0 或 <30 天`→同意；否则→已过期 | `t0.java` 后两个分支 |
| 风险提示只在 `riskLevel==3` 显示，且只有再满足 `riskTypes.contains(4)` 才改文案 | `t0.java` 的两次 `llWarning` 赋值 |

两处**原样的坑我没有"顺手修好"**，因为修不修是产品决定，不是还原：① 风险文案只在命中时覆写，
复用行会残留上一行的文案；② `status` 既不是 0/1/4 时行尾仍出现可点的"同意"。

**顺带补的 fastkit 真接口**：`ShapedView` 的样式对象在运行时可改（`ShapeAttrs.setTopLeftRadius(...).render()`），
这是从上面那个 28dp 圆角调用点反推出来的——之前只做了 XML 一次性应用，业务屏一 bind 就会缺 API。

**验证（全在本机 JVM 上跑，无设备）**：`FriendRequestListActivityTest` **17 条**，
含真实 BRVAH 子键点击 → ViewModel → repository → 状态回写 → 跳转的整链路（`performClick()` 走真视图树，
RecyclerView 手动 measure/layout，不是反射也不是桩），以及行状态机、分页、四条闸门、去抖、空态、返回与加友键。
`:net:test` 与 `:app:assembleDebug` 同轮通过（APK 60.6MB）。业务屏可 inflate 仍 **306 / 335**，本轮没有倒退。

**这一轮暴露的架构约束（写进第六节，别再反复）**：`:res166` 里 936 个布局的自定义 View 类
（百度地图、各广告 SDK、环信…）不在该模块 classpath 上，**ViewBinding 在资源银行里根本编不过**；
所以绑定必须留在 `:app`，每重建一屏就把那屏用到的 layout 从银行复制进 `app/src/main/res/layout/`
（应用模块资源覆盖库同名资源，不会 duplicate）。绑定包名因此是 `com.youloft.watcher.databinding`，
与原版一致——上一轮临时造的 `res166.databinding` 已废。

**BRVAH 版本澄清**：APK 里是 `com.chad.library.**adapter4**`（4.x），不是之前记的 3.0.10（那是 `adapter.base`）。
现按 Maven Central 可解析的 `io.github.cymchad:BaseRecyclerViewAdapterHelper4:4.1.7` 接；
**APK 内的精确小版本没提取出来**，列入待验：子键点击、动画与 `submitList` 行为若与线上不一致，需要按包内类结构再对一次。

**这一屏留下的口子（都有主，不是散落 TODO）**：6 个跳转目标（`AddFriendActivity`、`FriendRequestDetailActivity`、
`FriendInfoActivity`、`AgreeAddActivity`、`FriendNumLimitDialog`、`FriendPermissionsSettingsDialog`）走
`FriendRequestNavigation` 接口，实现暂为空——各自屏重建时填；`isVip` / `cachedFriendCount` /
`askPermissionsAfterAdd` 三个 MMKV 与服务端渠道开关的读数在 `LinkUpApplication` 上留成 open 值；
`PrivacyRepository.updatePermissions`（确认后逐项同步权限）与全局错误处理（登录态刷新）、
IM 本地同步（`manager.u`）、埋点（`mf.b.i("好友申请页")`）都还没接。

## 五点十、第二屏：好友申请详情（2026-11-03）

列表屏 6 个跳转目标里的**申请详情**已重建，`FriendRequestNavigation.openFriendRequestDetail` 从空实现
换成真 `startActivity`——这一屏是"从上一屏跳进来"的第一个真实闭环，不是又一个孤立切片。

**按 1.5.6 还原到的事实**：

| 事实 | 出处 |
|---|---|
| Intent 契约：`getParcelableExtra("data")` + `getBooleanExtra("myFriendLimit", false)` | `M()` 前两行；调用方 `a(context, friendRequest, z10)` |
| **只有一个"同意"键，没有拒绝键**（布局里根本没有那个 view） | `activity_friend_request_detail.xml` 全文 |
| 三条前置闸门顺序：超 30 天 → 非 VIP 且**我**配额满（弹窗）→ **对方**配额满（toast） | `o0()` |
| 这里**没有**注销用户判断（和列表屏不同） | `o0()` 无 `isUnregister` 分支 |
| `status != 0` 时按钮 `setEnabled(false)` 且文案换成"与该用户已为非好友关系" | `M()` 末段 |
| 关系文字/图标四分支：1 恋人、2 挚友、3 亲人、其他；性别 1 男 2 女，图标挂在文字**尾侧** | `p0()`、`M()` 的 `z.k(tvSex, GravityCompat.END, drawable)` |
| 匹配码是 HTML：`匹配码：<font color=#324058>%d</font>` 经 `Html.fromHtml(..., LEGACY)` | `ext.l.d(getString(mine_match_code, userId))` |
| 同意键的点击间隔是 **500ms**（原版走 `ext.r.c(view, 0L, …, 1, null)`，mask 位把默认值取成 500） | `ext/r.java` 的 `c`→`b`→`g0.x` |
| 结果回包 `msg != null` 就 toast（**不判空串**；列表屏判长度） | `k0()` vs 列表屏 `C0()` |
| 成功后：渠道开关打开→权限设置弹窗；否则→`AgreeAddActivity` + `finish()` | `k0()` |
| 详情屏 ViewModel 用 `MutableLiveData.postValue`，列表屏用 `SharedFlow`——**原版两者就是不一样** | 两个 ViewModel 的字段类型 |
| 详情屏**不**回填 `friendUserId`（列表屏回填） | 对比两处 `invokeSuspend` |

**一处架构空白这轮必须定下来**：原版 bean 全实现 `Parcelable`，而我的 DTO 在**纯 JVM 的 `:net`** 里，
`android.os.Parcelable` 根本不可用；但"把整个 bean 交给下一屏"是这个 app 的通用跨屏方式。
现按 `tools/gen_beans.py` 的判定给**字段图全为普通 Java（不含 `JsonElement`、非泛型）的 61/79 个 bean**
加 `java.io.Serializable`，跨屏 Intent 用 `putExtra("data", bean)`。剩下 18 个含原始 JSON 的 bean
**不打标记**——宁可少给能力，也不给一个运行时 `NotSerializableException` 的假承诺。
这条与 Hilt/Navigator 无关，将来若把 bean 迁回 `:app` 再换回 Parcelable。

**验证**：`:app` 35 tests / 0 failures（列表 18 + 详情 14 + 原有 3），`:net` 7 / 0，
`:app:assembleDebug` 通过且**实测 APK 内含** `FriendRequestDetailActivity`（classes13.dex）
与清单条目（含 `screenOrientation="portrait"`，与原版一致）；业务布局 inflate 仍 306/335。
详情屏的测试全程用生产路径 `start()` 造 Intent（列表屏那条用例负责验真跳转与 bean 存活），
避免测试自己拼 extra 造成"契约改了测试还绿"。

## 五点十一、第三屏：我的好友列表（2026-11-03）

`MyFriendActivity` 与前两屏**形状完全不同**：没有 ViewModel、没有分页、没有 HTTP 请求。
数据是一条活频道——原版订阅 `manager.u`（IM 推送 + 本地缓存维护的好友列表与"好友变更命令"），
屏本身只做排序、标题、点击路由和命令消费。

**还原到的事实**：

| 事实 | 出处 |
|---|---|
| 列表为空时**屏幕自己 finish()** | `h0()` 首行 `if (list == null \|\| list.isEmpty()) finish()` |
| `userId <= 0` 的行不进列表；主账号 `insert(0, …)` 排最前 | `h0()` 过滤与 `add(0, info)` |
| 标题是 `我的好友（%d）`，计数用**过滤后**的列表 | `h0()` 末段 |
| `tvAddFriend` 在过滤后为空时 GONE | 同上 |
| 行点击：`userId==0` 直接 return；`unregisterTime>0` 只 toast；否则 `FriendInfoActivity(userId, type=2, name)` | `j0()` |
| 加友按钮的 mode：本屏底部键 **6**、空态按钮 **0**、申请列表标题栏 **8** | 三处 `AddFriendActivity.a.b(...)` 的 mask 位推得 |
| 命令频道：`type ∉ {ADD(1), ADD_SELF(100)}` 才处理，处理前先把命令**置 null 消费掉** | `g0()` + `b.invokeSuspend` |
| 消费协程是 `launchWhenResumed`；命令落回 null/ADD 类时 **cancel 上一个 job** | `g0()`、`f64725p` |
| 适配器按构造参数 `styleMode` 切四种行形态，本屏用 mode 0（隐藏性别/添加时间/勾选） | `adapter/FriendListVAdapter.f0()` |
| `Info.getName()` = `remark` 非空则用 remark，否则 nickname | `bean/FriendList.java` `getName()` |
| `isExpFriend()` = `userId == 0`；角标由 `FriendListVDecoration` 画在 (左+34dp, 上−11dp) | decoration 全文 |

**顺带纠正：本屏永远不会显示"体验好友"角标**——`h0()` 把 `userId<=0` 过滤掉了，而 `isExpFriend()`
正是 `userId==0`。角标是**别的屏**（不过滤的那几个）用同一个 decoration 时的行为。这条不是 bug，
是还原出来的事实，写清楚免得后面有人"顺手补个空态角标"。

**框架缺口一处**：lifecycle 2.7 已删除 `launchWhenResumed`，这里用它的等价替代
`lifecycle.whenStateAtLeast(RESUMED) { … }`（等到 RESUMED 跑一次、掉下该状态挂起），
**没有**用 `repeatOnLifecycle`——后者每次回前台重放，会把同一条命令处理两遍。

**测试里学到的 BRVAH 一处坑**：开了 state view 之后，数据为空时 `itemCount` 是 1（那个空态占位），
断言"几个人"要看 `items.size` 而不是 `itemCount`。

## 五点十二、第四屏：好友信息（2026-11-03）

`FriendInfoActivity` 是四屏里动作最多的一屏：资料展示 + 备注 + 关系 + 权限摘要 + 聊天入口 + 删除，
外加一条 IM 推送覆盖层。它按 `id` 加载（`relation/info?queryId=`），没有分页。

**还原到的事实**：

| 事实 | 出处 |
|---|---|
| Intent 契约三个键：`id` / `source` / `nickName` | `M()` 前三行与 `a().a(context, id, source, name)` |
| `sex` 与 `relation` 在这个 bean 里是**字符串**，原版直接 `Integer.parseInt` | `z0()` |
| remark 只在非空时写入标签（空则保留原值） | `z0()` |
| 权限摘要 = 数 9 个 sens 开关的 true 个数：>0 → `%d项数据`(appPrimary)，否则 → `无可见数据`(appSecondary) | `w0(Permission)`，顺序 location/track/phoneUse/call/phoneState/sensitive/appUse/lockPhone/realtimeVoice |
| 加载失败**或 data 为 null** → `S0()`：隐藏 rl_remark / rl_relation / ll_permissions / ll_add_date / iv_delete / toolbar，显示 tv_not_friend，用户名退回 Intent 里的 nickName，匹配码用 Intent 里的 id 现算 | `S0()` + `z0()` 头两行 |
| 关系行在 `relation <= 0` 时不弹任何窗 | `K0()` |
| 删除要过确认框；确认后 `relation/del`，成功 → toast + **`LiveDataBus("friend_delete")` 广播该 id** + finish | `I0/J0/A0` |
| 更新关系/备注的响应是**客户端把自己发出去的值回显**成 data（不是服务端回包） | `FriendInfoViewModel` 里 `new ApiResponse(status, msg, relation / remark)` |
| 备注更新成功还会刷 IM 与最近会话（`u.M` + `widget.im.e.v()`） | 同上 |
| 推送 type=2 → 覆盖层可点，toast「你们已不是好友」；type=4 → 「该账号已注销」 | `E0/F0/G0` |
| `source` 只用于埋点标签：1 守护Tab-头像功能弹窗 / 2 消息中心-我的好友 / 3 im头像 / 其他 空 | `x0()` + `M()` |

**四处按调用点解出来的端点**（1.5.6 的 Retrofit 注解也被混淆成 `@o/@f/@t/@bm.a`，要先把字母还原成
GET/POST/Query/Body 再按"最近的注解"配对才拿得到路径）：

| 动作 | 端点 | 体 |
|---|---|---|
| 资料 | `GET api/relation/info?queryId=` | — |
| 删除 | `POST api/relation/del` | `{friendId}` |
| 改关系 | `POST api/relation/update` | `{friendId, relation}` |
| 改备注 | `POST api/relation/update_remark` | `{friendId, remark}` |
| 改权限（**本轮未接**） | `POST api/setting/friend_sens` + 逐类型回灌 | 见下 |

顺手把仓储层并回原版形状：`FriendRequestRepository` → **`FriendRepository`**（原版就是一个
`repository/FriendRepository` 覆盖全部 relation 端点），屏不再按屏拆接口。

**两处我明确偏离原版的决定**（都有测试钉住，不是顺手"修好"）：
1. `Integer.parseInt(sex/relation)` 在字段缺失时会抛异常。这里取 `toIntOrNull() ?: 0`，落进"未知/其他"分支。
   原版会崩，我们不还原崩溃。
2. 两条 toast 文案在原版的**调用点就是硬编码中文**（`a0.e("你们已不是好友")`），资源表里没有对应条目。
   这里保留字面量以维持一致，并把"该进 i18n"记进待验/整改清单，不假装它本来有资源。

**没有半接线的东西**：`PrivacyRepository.updatePermissions` 是两阶段流程——先 POST
`api/setting/friend_sens`，失败且原本有权限对象时，再拉一次当前权限、然后**逐类型**（track/call/appUse/
location/…）分别回灌。这一整块属于隐私权限卡片，本轮只在权限行留了"打开弹窗"这一步的真实调用与
回调空位，**没有**为了让代码看起来完整而先接上半个端点。

## 五点十三、共用弹窗层（第五步，2026-11-03）

解掉 `FriendInfoDialogs` / `showFriendNumLimitDialog` / `showPermissionSettings` 三个空位，一次把
六个动作的真实交互接上：删除确认、改关系、改备注、改权限、配额提示 ×2。

### 弹窗口库是真·子集，不是原样 1:1

原版链路：`dialog.e`（接口，~30 个链式 setter）→ `dialog.XDialog`（**2013 行**：宿主 Lifecycle、
键盘跟随、动画集、状态栏/导航栏、重复弹出守卫、tag 规则）→ `dialog.BindingDialog<V,T>`（反射 inflate
ViewBinding）→ app 侧 `BaseFrameDialog` → 具体弹窗。

本轮只重建了**这五个弹窗真正用到的那一层**：`BindingDialog<V>`（反射 inflate + `post` 弹回，
使 `setOnDismissListener` 在 `show()` 之后落地）。**明确没重建**的：XDialog 的 Lifecycle 宿主、键盘跟随、
动画集、状态栏控制、以及原版 `show()` 的三道守卫（宿主正在 finish/destroyed、已经在显示、状态位处于过渡中）。
所以本轮的弹窗是「行为对了、外壳薄」：`DESIGN` 第九号清单当场记这条，不许后面把它当成完整版复用。

原版 `e.a.a(dialog, 0)` 走 `b0(millis)` → `lifecycleScope` 协程；本轮用 `contentContainer.post{ dismiss() }`
代替（弹窗没有协程宿主），差别只在极短的排程时机，按钮点击的先后次序不变。

### 五个弹窗 + 一个选择器：当场读出来的事实

| 组件 | 事实 | 出处 |
|---|---|---|
| `pages.common.ConfirmDialog` | 默认 cancel=`cancel`、confirm=`confirm`、内容不设就隐藏；两个按钮都走「间隔点击」；任一键都收起，但**只有确认键回报决策**；遮罩默认 `color_7f000000`、可取消 | `ConfirmDialog.java` 构造 + `L1()` + `O1/P1` |
| `view.RelationButton` | 四张磁贴由 `bean.friend.RelationView` 四元组驱动（磁贴/对勾/文字/角标）；选中=宽 80→100dp、着色 `colorCardVariant`→`colorBrand`、角角淡入、对勾显示；值不变直接 return；默认回调空 | `RelationButton.setRelation/c0` |
| `friend.dialog.UpdateRelationDialog` | 磁贴点击只改内部选择，**不回报**；✕ 收起不回报；确认键**先收起再回报**（先 `e.a.a` 后 `X.invoke(W)`） | `Q1/R1` |
| `user.dialog.UpdateUsernameDialog` | `InputFilter.LengthFilter`（默认 10）+ `%d/%d` 计数；空输入禁用确认（除非 `alwaysEnabled`）；预填时光标到末尾；内容设置后 **300ms** 弹键盘 | `L1()` + 内部 `a` + `h0()` |
| `friend.dialog.FriendPermissionsSettingsDialog` | 9 个勾选按 location/track/phoneUse/call/phoneState/sensitive/appUse/lockPhone/realtimeVoice 对位；**`cb_sens_screen_shot` 在布局里有、原版从不读写** → 保持不动，新 Permission 的第 10 个保持 false；**在收起时回报**（所以 ✕ 回报 null，调用方读作「用户走开」）；传进 null 权限就自己收起；`showClose=false` 收起 ✕；按 source 埋点 1/2/3/4/5/6，其它空 | `L1()`/`h0()`/`b0()` |
| `friend.dialog.FriendNumLimitDialog` | 只有两个动作：`tv_vip` → 回报 + 收起；✕ → 只收起（原版此处还先开 VIP 购买页 `manager.s.b(context,15)`） | `O1/P1` |

### 接上线的地方

* `FriendInfoActivity.dialogs` 默认改成 `RealFriendInfoDialogs`（真弹窗）；新增一条测试**当场点删除键 → 当场量出真
  ConfirmDialog 的标题 → 当场点确认 → 请求发出 + 收起 + finish**，这条测的是生产路径，不是 mock。
* 申请流 `showPermissionSettings` 改成真弹窗（按原种子：全开、收起 ✕、source=1）；回报 null 才走
  「开 AddResult + finish」。非 null 的分支**没有**半接线（`PrivacyRepository.updatePermissions` 的两阶段
  逐类型回灌仍属隐私权限那张卡）。
* `showFriendNumLimitDialog` 改成真弹窗；VIP 购买页（`manager.s`）仍是空位。

### 本窗口库的已知限制（不当完整版用）

1. 薄外壳：XDialog 的宿主 Lifecycle、键盘跟随、动画集、状态栏、重复弹出守卫**没**搬；本轮五个弹窗用到的行为都在，
   但复用前必须先读第九号清单，别把「五个弹窗可用」读成「组件库可用」。
2. 用 `post` 代替协程排程收起，时机与原版差一帧。
3. 权限弹窗的按-source 埋点有映射、没有汇（汇本身是另一张卡），只有测试钉住映射。
4. VIP 购买页 / 会话页 / AddFriend 三个入口仍是空位。

## 五点十四、本地缓存与配置读数（#11，2026-11-04）

把前三屏留着的三个"假默认值"换成真读数。**读的那一半接上了，写的那一半还没有**——这句是本节的重点，
别让后面的人以为装了包这三个值就自动对了。

### 存储地图（按原样核出来的键与实例）

| 值 | 存在哪 | 键 | 出处 |
|---|---|---|---|
| 账号记录 | `MMKV.defaultMMKV(MULTI_PROCESS_MODE, null)` | **`user2`**（Gson JSON） | `CacheUtils.y0()` |
| 好友列表 | `MMKV.mmkvWithID(userId.toString(), MULTI_PROCESS_MODE, null)` | **`friendList`** | `CacheUtils.r()` |
| 当前用户 id | 就是 `user2` 里的 `userId`（`CacheUtils.z0()`），per-user 实例的名字由它决定 | — | `z0()` |

规则也照抄：`isVip = vipSubType > 0 || shareToMeList.any { vipSubType > 0 }`（`User.isVip()`）；
好友数缺失记 0，调用方自己 `-1` 去掉本机账号（`C0()` 里那句 `(size ?: 0) - 1`）。
JSON 坏了或缺键 → 回落到默认 `User()`，不抛。

**没接的一条**：原版在 `user2` 读不到时还会回读一个**旧的 Parcelable blob**（给很早的升级路径用）。
我们的 DTO 不是 Parcelable，这段不搬——后果写明白：那批老用户装上新包会显示成未登录，
等登录流那张卡接管写入时一并处理。

### 两个"值从哪来"的真相

1. **渠道号**不在 MMKV 里，也不在 BuildConfig 里：原版 `hc.i/hc.d` 把**自己的 APK 当 zip 打开**，
   从 **APK 签名块里一段魔数为 `1903654775` 的自定义 section** 读出 JSON，取 `channel` 键，读不到时回 `"0"`。
   我们重建出来的 APK 里现在**没有**这一段 → 当前走 `"0"`。要真跑对，需要"读写这段"两件事一起做
   （打包时写入 + 安装后读出），那是独立一张卡。
2. **`showPermissionChannel` 不来自我们自己的后端**：`ConfigUtils` 全部走三方远程配置 SDK
   （`of.b.f("android-watcher", "156")`，段名 `Parameter_Map`，fastjson 解析）。
   当场用生产抓包样本核过：我们自己的 `clientConfig` 回包**没有** `Parameter_Map`，也没有 `showPermissionChannel`
   → 这条不能靠自家路由凑。SDK 未接回，先留 `RemoteConfig` 接缝。
   规则照抄：`entries.any { it == 当前渠道 } || entries.any { it == "-1" }`（`"-1"` 是原版写死的通配，来自 `p.b()`）。

### 新增的依赖

`com.tencent:mmkv:2.2.2`（APK 里只留下 `FLAVOR = "DefaultCpp"`，**没有版本字符串**，所以按公开最新版接，
与 BRVAH 一样进待验清单：真机若出现"读不到老数据/多进程读写不一致"，先怀疑这里）。

### 接线方式与验收

`LinkUpApplication` 上三个 `open val` 不再写死：`keyValueStores` / `installChannel` / `remoteConfig` 全部可注入，
`isVip`、`cachedFriendCount`、`askPermissionsAfterAdd` 变成对它们的真实读取。
新增 `LocalCacheTest` **9 条**，其中一条专门钉住三个键名（`user2` / `friendList` / `showPermissionChannel`）——
键名是这次恢复的产物，写错不会报错，只会永远读成默认值。

全套：`:app` 92 tests / 0 failures、`:net` 7 / 0（合计 **99**），`assembleDebug` 通过，业务布局 inflate 仍 306/335。

**装包后的真实状态**（不等真机也说得清）：写入方（登录流、IM 好友缓存）还没重建，渠道段与远程配置 SDK 也还没接，
所以现在装上包这三个读数仍然是 `false / 0 / false`——**只是不再是因为代码写死了**。

## 五点十五、登录写路径接管（#16，2026-11-04）

#11 把"读"接上之后，三个数还是 `false/0/false`——因为**没人写**。这一轮补的就是写的这一半。

### 原版的写入点与顺序（当场核出来的）

| 动作 | 原版实现 | 关键点 |
|---|---|---|
| 存账号 | `CacheUtils.U2(User)` → `defaultMMKV.encode("user2", Gson().toJson(user))` | 就是默认实例的 `user2`，与 #11 的读一侧对齐 |
| 存 token | `CacheUtils.P2(String)` → `encode("token", value)` | **token 不在单独的登录回包字段里，而是 `User.token`**（`z0.g()` 里 `P2(user.getToken())`） |
| 登录收尾 `z0.g(user, isNewUser)` | 先按 `nickname 空 / isRegister` 分流 → **再 `P2(user.token)`** → IM 登录 → 埋点 sign-in | 顺序：先落身份，再连 IM |
| 刷新 `s2` 的那段 | `api/user/info` → 成功且 data 非空 → **先比较 `cache.isVip() != user.isVip()`（或强制位）做副作用** → 再 `U2(user)` → 最后 `postValue` | **VIP 判断必须在写缓存之前**，写完就拿不到旧值了；这条顺序我原样保留并有测试钉住 |
| 退出 | `P2("")` + `s2.m(User(默认值))` | **是"覆盖成空账号"，不是删键**——所以 `user2` 一旦存在就永远在 |

### 重建里落了什么

* `KeyValueStore` 补 `putString`（原来只读）；`UserCache` 补 `save / saveToken / token`（键就是 `user2`、`token`）。
* `UserRepository.userInfo()`（`GET api/user/info`，路由早就在 `:net` 里）+ `UserManager`
  （`store` / `refresh` / `signOut`，一个 `LiveData<User>`），对应原版 `manager/s2` + `manager/z0` 的这两块职责。
* `AndroidDeviceContext` 的 `authToken() / userId / mainUser` **改读同一份缓存**：一旦有账号，
  之后每个请求的 `Authorization` 与 11 个公共参数就是本机真实身份，不再是两套平行存储。
  **`channel` 故意没动**——真值在 APK 签名块里（#17），现在改等于凭空猜一个签名输入。
* `AccountSideEffects.onVipStateChanged` 是接缝：条件（变化或强制）已实现且有测试，
  四个具体副作用（IM 会话刷新、桌面 widget、掉 VIP 时地图模式复位 + 关权限提醒 + 总线广播）都还没有实现方。
* 探针屏加了一行**真机能验**的检查：`GET api/user/info` 之后打印
  `cached userId / mainUser / isVip / tokenStored`。这样 #16 的闭环不依赖我也不能碰的 `/api/sms/send`。

### 不在这一轮里的事（别误会成已完成）

短信/一键登录那几个屏**没重建**：`pages/login/LoginActivity` 及其 `manager/z0`（1969 行）还牵着
号码认证 SDK（`GYManager.preLogin/ePreLogin`）、`/api/sms/send`、个推 bindAlias、Umeng sign-in。
所以现在的状态是：**写入通道与身份打通了，但真机上要让它真的有值，要么等登录屏，要么先用探针屏灌一个 token。**
探针屏那条 `token` 只能靠 `adb` 写 MMKV 或等 `#18 登录屏`（见 #15/登录流后续）。

包体从 60.6MB 涨到 61.3MB，是 MMKV 带进来的 native 库（不是代码量）。

## 五点十六、第六屏：添加好友（#15，2026-11-04）

这一屏是**整个好友流程的入口**：App 里每一处"加好友"的按钮都带着自己的 `source` 跳进来。它也是第一屏
出现"两版行为不等价、必须按 1.6.6 重建"的情况，所以先把版本落差写清楚。

### 为什么这屏按 1.6.6 而不是 1.5.6

先前定的规则是"资源取 1.6.6、行为取 1.5.6"，理由是 1.6.6 被 R8 压扁。这屏推翻了它对行为的一半适用性：
1.6.6 里 **ViewBinding 的字段名全部保留**（`ActivityAddFriendBinding` 是可读的），于是一屏的逻辑几乎逐行
可读 —— 而 1.6.6 的这屏**比 1.5.6 多了一条完整链路**：

| 能力 | 1.5.6 | 1.6.6 |
|---|---|---|
| 搜索 | 点输入框 → 跳 `SearchFriendActivity` 另开一屏 | **屏内搜索**：输入框 + TextWatcher 控制清空键/搜索键、IME 搜索键、focus/键盘遮罩 |
| 搜索结果消费 | 只消费 `pre_confirm`（`m`） | 还消费 `match_by_phone`（`q`），从 `SearchFriend.list` 现建一个 `Friend` |
| 申请成功提示 | 只在权限弹窗收起时 toast | 权限门不开时**立刻** toast（`else if` 分支） |
| source 标签 | 19 个 | 21 个（新增 20 实时截屏 / 21 隐私设置） |
| 分享 URL 的 `av` | `1.5.6` | `1.6.6` |

所以：**资源 1.6.6 + 行为 1.6.6**，1.5.6 只用来给被压扁的方法名对号（例如确认 `f63863w` 就是权限弹窗的
`source`）。规则本身已改进第六节第 12 条。

### 布局差集：三处不是"我没搬"，是 1.6.6 真删了

1.5.6 的 `M()` 会碰 `binding.llHuaweiTips` / `binding.ivDownload` / `binding.sclScan`，1.6.6 的 binding 里
一个都没有。逐个定性：

- `iv_download` + `ShareMatchCodeView`：**下载二维码卡片整条入口被删**。连带 `saveBitmap`/`p()` 这条 toast
  通道在本屏无可触达调用点 → 没有重建（不是遗漏，见下）。
- `ll_huawei_tips`：**不是删除，是换宿主**。1.5.6 把 `tv_tips` 包在一个默认隐藏的 LinearLayout 里；1.6.6
  直接让页底那一条 `tv_tips` 显示。渠道判定式与文案分支完全一致（字母被重排：`i/k/g/f` → `i/l/g/f`）。
- `scl_scan` → `tvScanAdd`：扫一扫从"整行卡片"变成一个胶囊按钮。

### 匹配码显示与复制（一处容易做错）

```kotlin
binding.tvMatchCode.text = userId.toString().chunked(4).joinToString(" ")   // 1234 5678 90
platform.copy(userIdText)                                                    // 复制的是裸 id
```

1.5.6 是 `"匹配码:" + " " + userId`（不分组）；1.6.6 改成四位一组。点击复制传的是
`String.valueOf(user.userId)`，**不是显示串** —— 界面带空格、剪贴板不带。测试两条都钉了。

### 21 个入口标签（`x0(source)`）

1 守护Tab-好友切换弹窗 / 2 守护Tab-添加好友banner / 3 守护Tab-体验好友时卡片点击 / 4 守护Tab-右上角添加好友 /
5 我的Tab / 6 我的好友 / 7 H5 / 8 好友申请 / 9 一键锁机 / 10 实时语音 / 11 守护Tab-新用户体验模式弹窗立即添加按钮 /
12 守护Tab右上角会员入口 / 13 我的Tab会员入口 / 14 小组件 / 15 应用锁 / 16 水印拍照-发送给好友 / 17 添加好友推送 /
18 卫星地图 / 19 显示路况 / **20 实时截屏 / 21 隐私设置（1.6.6 新增）** / 其他。
`openAddFriend(mode)` 从此是**真跳转**：申请列表头部 8、好友列表按钮 6、空视图 0 三条线闭环。

### 四条找人路径与两套"来源编号"

这是本屏最容易混淆的地方，原版用了**两个不同的数**：

| 路径 | 核对信息弹窗 `type` | 权限弹窗 `source`（字段 `A`/`f63863w`） |
|---|---|---|
| 手输 8 位 → `pre_confirm` | 2 | 3 |
| 手输其他 → `match_by_phone` | 3 | 4 |
| 扫一扫 | 1 | 2 |
| H5（`source==7` 且 extra `match_code_from_h5`≠0） | 5 | 6 |
| 通讯录（另一屏自己发） | 4 | 5 |

判定树（`f1()`）：输入非空才发；**8 位**当匹配码走 `pre_confirm`，其余走封套上传；`add_type` 埋点串同步取
"搜索匹配码"/"搜索手机号"。ViewModel 里还有一条 6 位也算码的分支（`size==1 && (len==8||len==6)`），原版
**先封套、后分流**，所以 6/8 位也会白算一次封套 —— 原样保留。

扫码（`e1()`）：结果串长 8 → 直接当匹配码；否则解 `Uri.fragment` 里 `userid=` 到 `&`，必须纯数字；都不成
→ `invalid_qr_code`。两条成功路都 `dialogSource=2`，并**先把 `lastSearch` 置 null**（这决定了 type 落 1 还是 2）。

### 通讯录搜索的封套协议（本轮最硬的一块）

`net/src/main/kotlin/.../ContactSearchEnvelope.kt`，逐层还原：

1. `GET api/tool/rsa/public_key` → 剥 PEM。**结束标记是四个横杠** `----END PUBLIC KEY-----`（原版就是这么
   写的），去所有空白，Base64(NO_WRAP) 解出 DER，X509 → RSA 公钥。
2. 内层 `{"phoneList":[…]}` 只做 **Base64**（不加密！）放进 `ciphertext` 字段。
3. 外层 `{"keyID":<我的 userId>,"ciphertext":…,"timestamp":<秒>}` 用**随机 16 字节**做 AES/CBC/PKCS7，
   **IV 就是这同一串随机 key**。
4. 随机 key 用 `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` 加密。
5. `POST /api/relation/match_by_phone` body `{"encrypted":<b64>,"keyEncrypted":<b64>}`。
6. **公钥拿不到时的约定**：不发请求、不报网络错，而是自己 post 一个 `status=10086` 的空壳响应
   （8 位 → `Friend(sslCertificateError=true)`，否则 `SearchFriend(sslCertificateError=true)`）。界面读到
   10086 就**不弹**"匹配码无效"，只清空输入框并把光标放回去。

测试里抓到一个真 bug：Gson 默认把 `=` 做 HTML 转义成 `\u003d`，会**毁掉内层 Base64**；原版用 org.json 不受
影响，所以必须 `disableHtmlEscaping()`。另外标准五横杠 PEM 页脚按原版字面量剥会残留一个 `-`，Base64 解不出 →
读作"无公钥"。这条**是集成风险**（服务端到底发哪种页脚未知），已进待验清单。

### 权限门换人了：从远程配置改成硬编码渠道

同一个动作（加完好友后弹不弹权限设置）在两版里**判据不同**，且这是 #23 那条外部依赖的一半答案：

- 1.5.6：`ConfigUtils.n()` = 远程配置 `showPermissionChannel` 列表里含本渠道，或列表含通配 `"-1"`。
- 1.6.6：**四个调用点**（`AddFriendActivity:409`、`ContactInfoListActivity:245`、
  `FriendRequestDetailActivity:174`、`FriendRequestListActivity:387`）全改成 `utils.p.i()`，即
  `渠道号 == "3301"`，与远程配置无关。

所以 `askPermissionsAfterAdd` 现在走 1.6.6 规则；1.5.6 规则留在
`PermissionChannelGate.asksAfterAddFriendByConfig()`（有测试），因为那是真实历史行为，也解释了那个配置键
当初是干什么的。渠道常量表（`utils.p`，两版一致）：3801 定位互授权文案 / 3301 走 `add_friend_bottom_tip` /
3300 与 3302 走另一条硬编码 / 其余渠道不显示。

### 核对信息弹窗（本轮顺带重建）

`VerifyInfoDialog` 两版逐行一致（1.5.6 284 行 / 1.6.6 290 行）。三条值得写下来的事实：

- 预选关系按**性别配对**：异性 → 恋人(1)，两个男生 → 挚友(2)，性别未知 → 其他(4)，并把对方性别画成
  `tv_name` 的右侧 compound drawable。
- 对方到好友上限时：`tv_confirm` **GONE**，换成会员角标 `iv_label_vip`，点它 → `ext.s.a(ctx, 16, "多个好友")`
  （会员页入口，source 16）。
- `onConfirm(relation, dismissParent)` 的第二参数是**给调用方**用来关本弹窗的；本屏不用它（弹窗自己已收起）。

### 本轮落在哪、绿在哪

- 新文件：`pages/friend/AddFriendActivity.kt`、`AddFriendViewModel.kt`、`AddFriendDialogs.kt`、
  `AddFriendPlatform.kt`、`pages/friend/dialog/VerifyInfoDialog.kt`、`view/QrCodeRenderer.kt`、
  `fastkit/.../utils/KeyboardUtils.kt`、`net/.../ContactSearchEnvelope.kt`；两个布局
  （`activity_add_friend`、`dialog_verify_info`）按惯例复制进 `:app`；清单注册了 Activity，并补了
  `<queries>`（Android 11+ 包可见性，否则"装没装微信"永远假）。
- 测试：`:app` **140 条全绿**（本轮 +32 屏 +6 弹窗），`:net` **10 条全绿**（+3 封套），合计 150。
  业务布局 inflate **仍 306/335**（`tools/summarize_inflate.py` 重跑：仍失败 29 个，缺的还是那 5 个自有 View
  类）—— 这两个布局本就在银行里能吹开，复制进 `:app` 只为接 ViewBinding，不动这个数。
- fastkit 顺带补的 `KeyboardUtils` 只搬了这屏要的三件事（hide / isShown / 全局布局监听），阈值 250 与 200
  是原样数字。**发现一个既有缺口**：`:fastkit:test` 的单测资源合并本来就跑不过（合并出的 values 引用
  material 属性而 fastkit 不依赖 material），与本轮无关，未修。

### 本轮**没有**重建的（别误会成已完成）

1. ~~**二维码绘制**~~ / ~~**LoadingDialog 画面**~~：**已由五点十七（#19）重建并接线**。
   顺带把上一轮写错的参数改正：那条调用用的是 `ErrorCorrectionLevel.Q`（不是 H，H 是另一条简易生成器
   `g()` 用的）、字符集 `"utf-8"`（小写，来自个推常量类）、**方模块**（填充系数 1.0，不是圆角点阵）、
   **环形定位眼**（点半径 1.5 模块 + 环半径 3 模块、线宽 1 模块）、透明底；1.5.6 会把头像嵌在中心，
   1.6.6 传的是 `null`。
2. **微信/QQ 分享本体**（`utils/h1`、`utils/i0` 缩略图上传）：装没装用包查询等价复现了（原版本来就是包查询），
   分享调用是空实现。
3. **QR png 上传 H5**（`api/tool/uploadLog` + 上传 helper）：`uploadQrImage` 空实现。
4. **`ContactInfoListActivity` / `QRCodeScanActivity` / `SearchFriendActivity`(仅 1.5.6)**：只留
   `openContactList()`/`openQrScanner()` 意图；扫码结果由 `onQrResult(raw)` 收，真启动器待那屏落地。
5. **埋点汇 `mf.b`**：21 条 source 标签、`add_type`/`relationship`/`add_result` 事件形状都由测试钉住，无处可发。
6. **同意后 IM 侧效应**：IM 角标 `widget.im.e.w(userId,1)`、`AgreeAddActivity` 拉起、会话提示气泡 —— 属 IM 卡。
7. **权限库**：XXPermissions 未接回，用 androidx `RequestPermission` 复刻；"永久拒绝跳系统设置"用
   `shouldShowRequestPermissionRationale` 等价判定（原版第二个布尔 `neverAskAgain` 语义相近但不完全相同）。

## 五点十七、二维码绘制与加载遮罩（#19，2026-11-04）

这是把上一轮我自己列进"没重建"的头两项做成真东西，顺带**改掉一条已经发布出去的错话**。

### 先纠错：那条 z0.d 调用的参数我上轮记错了

读完 `utils/z0.java`（1.5.6，328 行；1.6.6 是同体量的 `utils/y0.java`）后逐条核：

| 参数 | 我上轮写的 | 实际（已核） |
|---|---|---|
| 纠错等级 | H | **Q** —— H 是另一条简易生成器 `g()` 用的 |
| 字符集 | UTF-8 | **`"utf-8"`（小写**，值取自个推常量 `com.igexin.push.f.r.f48119b`） |
| 模块形状 | 圆角点阵 | **正方形**（`moduleShape=0`，填充系数 1.0） |
| 定位眼 | 未提 | **样式 2 = 环形**：中心实心点半径 1.5 模块 + 环半径 3 模块、线宽 1 模块；7×7 区域内的模块一律不画 |
| logo | 可选中心 logo | 1.5.6 确实传头像，**1.6.6 恒传 null**（随头像层一起删） |

`z0.d(...)` 在两版里都**只有一个调用点**（添加好友屏），参数每次都一样，所以重建类只钉这一套配置；
原版另有的圆形/多边形模块与其他眼样式在 1.6.6 已无调用点，不做（做了就是死代码）。

### 绘制器分成"版面 + 刷图"两步，是因为本机测不了像素

`app/.../view/MatchCodeQrPainter.kt`：`plan(content, sizePx)` 返回纯数据版面（画布尺寸、模块边长、
居中偏移、要画的暗模块清单、三只眼的圆心与半径），`draw(plan)` 只是照着版面往 Canvas 上刷。

分开的理由不是洁癖：本机 **Robolectric 的光栅化不可信**。探针实验（`probeRng`）画一个 6×6 红方块到
10×10 白底位图，读回来的中心像素仍是白、全场只有 24 个红点；同一张标准 zxing 码用 Canvas 画出来解不
出，而用 `int[]` 直接喂 `RGBLuminanceSource` 就秒解。所以像素断言这条路废掉，改成：

1. 版面算术逐条断言（`qrLayout` 就是原版那条
   `reserve = max(extra, min(w/(n+2), h/(n+2))) * 2` → `module = (canvas-reserve)/n` → 居中）；
2. **把版面在这里重栅格化成 `int[]`，交给真 zxing 解码**，要求解回原 URL。

第 2 条是本卡最有价值的产出：**它证明"环形定位眼"是真的可扫的**——环+点+间隙在扫描线上正好给出
1:1:3:1:1 的 finder profile，所以 zxing 自己就能读；这条 URL（79 字符 @ Q）是版本 7 = 45×45 模块。
另一个副作用被写进测试：`iv_code` 是**透明底**，白卡片 `scl_code` 才是静区的一部分，所以码不能贴边铺满。

### LoadingDialog 的三条事实

`app/.../pages/common/LoadingDialog.kt`，照 1.5.6 `pages/common/LoadingDialog.java`（76 行）：

* 窗口 **`FLAG_NOT_FOCUSABLE`**（原版 `setFlags(8, 8)`），所以遮罩从不抢输入法焦点；
* 动画在 show 时 `startAnimation()`、dismiss 时 `pauseAnimation()`（原版 `E0/F0` 里的 `v()`/`z()`，
  已用 `javap` 对过 `SVGAImageView` 的方法名），播 `assets/loading.svga`；
* **最短显示时间**规则：被要求立刻收起时，如果还不到 `minShownMillis` 就推迟剩下的时间再收
  （原版 `b0(long)`）。默认 250ms，**但 `BaseFrameActivity.showLoading()` 是用 `0L` 构造的**，
  所以好友屏这条下限其实是关的——规则照搬，因为别的调用点用得到。

平台缝 `RealAddFriendPlatform.showLoading/hideLoading` 现在按原版那样**每屏复用一个实例**
（原版把它缓存在 LiveData 里），并沿用"activity 已死就不显示"的守卫。

### 数字与验收

新增 **13 条测试**（绘制器 9 + 加载遮罩 3，另 1 条接线检查），`:app` **153 / 0 failures**、`:net` **10 / 0**，
合计 **163 全绿**；`assembleDebug` 通过，APK **61,848,174 B**（≈61.8MB，比上轮 59.06MB 大 2.8MB，就是 zxing
与重新打包所致），已核 `MatchCodeQrPainter`、`LoadingDialog`、`zxing/qrcode/encoder/Encoder`、
`dialog_loading` 资源与 `assets/loading.svga` 都在包里。

依赖多了一个 **`com.google.zxing:core:3.5.3`**。APK 里只留类名、没有版本串，和 MMKV/BRVAH 一样按公开最新接
（已进待验）。另外踩回来一个老坑：`gradle :app:dependencies` **只解析 POM**，zxing 的 jar 要跑
`tools/gradle.sh :app:fetchDebugArtifacts` 才真下载——这条 helper 任务本来就是为这事存在的。

### 不在本卡里的（别误会）

1. **真人扫码验收**：本机证明的是"版面能被 zxing 读"，不等于手机相机对着这块屏也读得快——抗畸变、
   透明底在深色主题上的对比度，都要装包扫一次（待验第 35 条）。
2. **`QRCodeScanActivity` 的解码侧**（zxing `MultiFormatReader` + 相机）：属 #20 那张卡。
3. **1.5.6 的头像嵌码合图**（`z0.a(qr, logo)`，把 logo 缩到 1/3 宽居中）：1.6.6 无调用点，没搬。
4. **SVGA 播放本身**：动画资源与视图已接，`Robolectric` 里只验时序与窗口标志，观感要装包看。

## 六、架构决定（写死，避免重写时反复）

1. **包名保持 `com.youloft.watcher`**。三方后台配置（微信/QQ/环信/百度/各推送）都绑包名，换包名等于全部重申请。
2. **minSdk 26 / compileSdk 34 / targetSdk 34**，与原包一致。
3. **`:net` 是纯 JVM 模块**，Android 与单元测试都依赖它。设备状态通过 `DeviceContext` 接口注入，所以签名逻辑可以在没有手机的情况下回归。
4. Retrofit + OkHttp + Gson（原 1.6.6 即如此），不引 Room/Hilt 之前先把 `:net` 站稳。原包用了 Hilt 2.51.1、Room 2.6.1、MMKV，重建时在 `:app` 引入即可。
5. **响应 bean 暂用 `JsonElement`**，先把 97 条路由全部接上；字段结构按 1.5.6 的 `bean`（113 个可读文件）逐个补成 data class。这样不会出现"为了编译通过而瞎猜字段"。
6. 签名输入用 `encodedPath + "?" + encodedQuery`（原实现走 `java.net.URL.getPath()/getQuery()`，即已编码形态）。**重写时别改成解码形态**，否则签名静默失效。
7. **baseUrl 走配置，默认指向生产 `https://link.tttick.com/`**。1.6.6 把测试服地址 `ag.a.f1732c` 硬编码进了正式包，等于整个线上客户端在打测试环境——这是一处独立的发布事故，重建时不要再复制。三个候选地址已固化在 `net/.../LinkUpClient.kt` 的 `LinkUpEndpoints`：`PRODUCTION` / `API`(测试) / `TRACKER_LEGACY`(定位上报)。
8. **ViewBinding 只在 `:app` 开，`:res166` 永远关**。银行里的布局引用了本模块 classpath 上没有的厂商 View 类，codegen 出来的 Java 必挂（本轮实测 100 个错误）。规则：**重建哪一屏，就把那一屏的 layout 从银行复制进 `app/src/main/res/layout/`**，同名资源由应用模块覆盖库；一屏一屏推进，绑定类留在 `com.youloft.watcher.databinding`，与原版一致。
9. **列表屏按原版用 BRVAH `adapter4`**（`BaseQuickAdapter` + `QuickViewHolder` + `addOnItemChildClickListener(viewId)`），不要自造 RecyclerView.Adapter：90 多个业务屏都是这个形状，模板对齐一次，后面每屏都省一次翻译。
10. **跨屏传整个 bean，用 `java.io.Serializable`，不用 Parcel 数字/JSON 字符串凑**。判定在 `tools/gen_beans.py`：字段图里出现 `JsonElement` 或 bean 自身带泛型 ⇒ 不加标记。理由见五点十。
11. **屏间跳转先进接口**（本流程是 `FriendRequestNavigation`），目标屏没重建就留空实现，测试用 Recorder 断言"跳转意图"而不是断言空界面；目标屏重建后把空实现换成真 `startActivity`，上一屏的测试不用改。
12. **"资源取 1.6.6、行为取 1.5.6"只对 1.6.6 名字被压扁的地方成立**。1.6.6 的 ViewBinding 字段名、资源 ID、字符串引用都活着，所以只要那一屏的 binding 存在，**行为以 1.6.6 为准**，1.5.6 退居"给压扁的方法名对号"的旁证（添加好友屏实测：1.6.6 比 1.5.6 多整条屏内搜索链路，照 1.5.6 重建就会做出一屏旧 UI）。附带判定：1.5.6 代码里碰过、而 1.6.6 binding 里没有的 View，是**那版真删了的 UI**，不许复活；换了宿主但文案仍在的（`llHuaweiTips` → 页底 `tv_tips`）不算删除。

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

**2026-11-03 两个必须记下的生成器缺陷（都是"能编译、结果错"型）**：

1. **嵌套 bean 被摊平**。1.6.6 的 bean 里 35 个文件带 `public static final class`，生成器只读顶层类，
   于是 `FriendList` 既吞了 `Info` 的 23 个字段、又把自己的 `list` 退化成 `List<JsonElement?>?`——
   `FriendList.Info` 这个类型在重建里**根本不存在**，任何用它的屏都写不出来。
   现按花括号范围把每个类体自己的字段分干净，嵌套类生成成 `data class FriendList { … data class Info(…) }`，
   外层字段类型回到 `List<FriendList.Info?>?`；跨文件引用一律写限定名 `Parent.Inner`，
   简单名与顶层类撞车时退回 `JsonElement`（宁可退让也不猜父类）。9 个嵌套 bean 恢复，
   `List<JsonElement?>?` 从 6 处降到 3 处（剩下的是真的取不到类型的）。
2. **jadx 改名污染了 JSON key**。字段与包名段冲突时 jadx 把它写成 `f63406id` 并留一行
   `renamed from: id` 注释；由于两个 APK 的 `@SerializedName` 都被抹掉，**JSON key 就等于 Java 字段名**，
   照抄改名结果就是"字段永远解析不成默认值"。现在从那条注释取回真名（并要求残名确实以真名结尾），
   11 处 `id`/`rh` 恢复。

这两处的共同特征是**不会报错**：编译过、测试也可能过（因为 fake 数据是照着错字段名造的），
只有真打线上才会发现一片 0 和 null。生成器改动后必须重跑 `:net:test`（含线上抓包留证那条）。

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
| 7 | BRVAH 的确切小版本 | APK 内只留下 `com.chad.library.adapter4` 类结构，版本字符串没提取出来；现按 4.1.7 编，若线上列表行为（动画/复用/加载更多）与真机对不上，先怀疑这里 |
| 8 | 好友申请屏在真机上的滚动分页与 28dp 圆角效果 | Robolectric 里是我手动 measure/layout 的；真实手指滚动、末行圆角与白卡阴影必须装包看屏 |
| 9 | ~~三个读数是写死的默认值~~ → 现在还剩什么 | 读已按原样接上（五点十四：MMKV 键 `user2`/`friendList`、isVip 规则、渠道通配规则，9 条测试钉住）；仍待的是**写入方**：登录流没重建 → `user2` 没人写；IM 缓存没重建 → `friendList` 没人写；渠道段与远程配置 SDK 没接 → 装包后这三个值今日仍是 false/0/false，但不再因为代码写死 |
| 10 | 详情屏同意键的"手感"（500ms 间隔 + 点击反馈） | 原版走 fastkit 的 `g0.x` 与 `ext.o.b`，ext 点击层没重建；间隔用活动内时间戳复现了，反馈（震动或音效）没有
| 11 | 头像加载的占位/圆角/淡入 | 原版是 `fastkit.widget.i.f(view, url, null)`，参数在混淆体里取不出来，本轮用 Glide 直连，只保证"显示出来" |
| 12 | `normalButtonStyle` 在 `isEnabled=false` 时的实际观感 | 逻辑已按原版还原（非 0 态禁用并改文案），效果要装包看屏 |
| 13 | 「我的好友」的真实数据、被删好友后的刷新、空列表自关屏这条路径 | 数据源是 IM manager（卡片未做），当前只能由测试喂列表与命令；真机上这三条都要走一遍 |
| 14 | 体验好友角标 | 本屏把 `userId<=0` 过滤掉了，这条分支只有用同一 decoration 但不过滤的那些屏才会出现，需连那些屏一起验 |
| 15 | 推送命令"等 RESUMED 消费一次"的真实行为 | 原版 `launchWhenResumed` 在 lifecycle 2.7 已删除，我用 `whenStateAtLeast(RESUMED)` 等价替换；退后台再回前台是否会重复处理同一条命令，只能真机确认 |
| 16 | 四个弹窗的真实交互 | 弹窗本体已落地并用真视图树测过（点哪键、回报什么、✕ 传什么）；剩下的**观感**——300ms 动画、键盘 300ms 延迟、遮罩色、圆角卡片——要装包看 |
| 17 | 权限更新的两阶段流程（先 POST friend_sens，失败再逐类型回灌） | 有意**没有**半接线，见五点十二末段 |
| 18 | 聊天入口 | `ChatActivity` 属 IM 卡片，只留了 `openChat` 意图 |
| 19 | "friend_delete" 广播的订阅方 | 已按原版主题名发出，但接收方（会话列表等）还没重建，端到端要等那些屏 |
| 20 | 两条硬编码中文 toast 要不要进资源表 | 原版调用点就是字面量（"你们已不是好友" / "该账号已注销"），改不改是 i18n 决定 |
| 21 | MMKV 的确切版本 | APK 里只留 `FLAVOR = "DefaultCpp"`、没有版本字符串，现接公开最新 2.2.2；真机若出现"读不到旧数据 / 多进程读写不一致"先怀疑这里 |
| 22 | 渠道号 | 原版从 APK 签名块中魔数 `1903654775` 的自定义 section 读；我们现在的包没这段 → 走默认 `"0"`。要配套做"打包写入 + 安装读出"，属发布链那张卡 |
| 23 | `showPermissionChannel` 这类远程配置 | 走三方配置 SDK（`of.b.f("android-watcher","156")` + fastjson），**不是**我们自己的 `clientConfig`（已用生产回包样本核过没有这个键）；SDK 未接回前恒 false |
| 24 | 老用户升级时 `user2` 读不到之后的旧 Parcelable 回读 | 我们的 DTO 不是 Parcelable，这段没搬；那批老用户装新包会显示成未登录，等登录流接管写入时一并定 |
| 25 | 真机上让 `user2` 真的有值 | 写入通道已通（#16），但**登录屏没重建**：短信/一键登录还牵着号码认证 SDK（`GYManager.preLogin`）与 `/api/sms/send`——后者我不会去碰（会发真短信）。过渡办法是探针屏那条 `GET api/user/info`，前提是先灌进一个有效 token |
| 26 | 服务端公钥 PEM 的**页脚到底是四个还是五个横杠** | 原版按 `----END PUBLIC KEY-----`（四横杠）字面剥离；标准五横杠会残留 `-` → Base64 解不出 → 走 10086"匹配码无效都不弹"的静默分支。装包后打一次通讯录搜索即可判定 |
| 27 | VIP 状态变化的四个副作用 | `AccountSideEffects.onVipStateChanged` 只有条件被测试钉住；IM 会话刷新、桌面 widget、掉 VIP 时地图模式复位 + 关权限提醒 + 总线广播，都要等 IM/widget 那两张卡
| 28 | 加完好友后弹不弹权限设置，真机上按哪条判据 | 已按 1.6.6 改成"渠道号==3301"；我们的包没有渠道段（第 22 条）会恒 false。要么先补渠道段，要么临时按 1.5.6 配置规则验 |
| 29 | 匹配码"四位一组"的显示与"裸 id"的复制 | 逻辑与文案已由测试钉住；实际观感（等宽字体 `sf_pro_rounded_bold_subset`、超长 id 换行、复制后系统剪贴板提示）要装包看 |
| 30 | ~~二维码卡片是真空的~~ → **已由 #19 接上真绘制器**；剩下的是**真人拿手机扫这块屏** | 本机已用真 zxing 解码证明版面可读（含环形定位眼的 1:1:3:1:1），但相机抗畸变、透明底在不同主题下的对比度、130dp 显示尺寸够不够，只能装包扫一次 |
| 31 | 键盘遮罩（`view_input_mask`）与输入框 focus 的联动 | 判定式已测（focus ∧ keyboardVisible）；fastkit 的 `KeyboardUtils` 只搬了全局布局监听 + 250/200 阈值，真实 IME 下的高度/时机要装包验，`adjustResize` 也没在清单里（原版就没有） |
| 32 | 权限请求的两处等价替换 | XXPermissions 未接回：①"永久拒绝跳系统设置"用 `shouldShowRequestPermissionRationale` 判定，与原版第二个布尔不完全相同；②原版 `onGranted(allGranted=false)` 也会 toast，单权限 contracts 无此分支 |
| 33 | 微信/QQ 分享本体 | 装没装已用包查询等价复现（原版本来就是包查询），分享调用是空实现；`<queries>` 只写了两个包名，真机上若还有 SDK 声明需合并 |
| 34 | 加载遮罩的观感 | 本体已落地（非聚焦窗口、SVGA 起停、最短显示规则都有测试）；`loading.svga` 的实际动画、遮罩透明度、100dp 卡片圆角要装包看。**另注**：本机 Robolectric 光栅化不可信（画红方块读回是白的），所以任何"像素对不对"的判断都只能上人眼 |
| 35 | zxing 的确切版本 | APK 里只有类名、没有版本串，现接公开 `com.google.zxing:core:3.5.3`；`Encoder`/`ByteMatrix` 是 zxing 内部 API（不是 `MultiFormatWriter` 那条公共面），若将来对齐不上老版本的模块排布，先怀疑这里 |

## 十、下一步顺序（建议）

1. **今天**：内网找 `desyb_always_guard.api` 这个 Jenkins job 与它的 git 仓库地址。后端**不用重写**（第七节），但服务端源码决定了字段与业务规则的还原精度，也决定了能不能继续给老用户发版。
2. 把 baseUrl 从硬编码改成分环境配置，**默认指向生产 `link.tttick.com`**（1.6.6 把测试服地址打进了正式包，这本身就是一处发布事故）。
3. 把 1.6.6 的 936 个布局（业务命名 327 个）+ 2169 条文案迁入 `:app`（要先补齐 appcompat/material/lottie 等依赖，否则会引用不到）。
4. 按第五节文件数从大到小补 bean → repository → pages；**bean 的字段现在可以在生产服逐条打出来**，不必靠 1.5.6 猜；每补一个模块加一条 `:net:liveVerify` 的真实请求验证。
5. 找回 keystore；找不回就新签名 + 老用户重装方案。
6. 权限从 73 条砍到最小必要（`QUERY_ALL_PACKAGES`、`REQUEST_INSTALL_PACKAGES`、后台精确定位是上架审核与监管重点）。
7. 与安全/运维定：生产网关要不要补齐 Signature 校验（第七节末）。
