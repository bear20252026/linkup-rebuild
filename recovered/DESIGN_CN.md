# LinkUp 客户端重建 —— 设计文档与工作量清单

> 编制日期：2026-11-02
> 依据：`com.youloft.watcher` 1.5.6 与 1.6.6 两个安装包的本机反编译产物 + 对活着的测试服做实机协议验证
> 工作区：`D:\linkup-rebuild`（原料在 `_raw/`，重建工程在 `net/` 与 `app/`）

---

## 一、一句话结论

**协议已经真的找回来了，而且是被重建出来的代码在真实服务器上跑通的**——`D:\linkup-rebuild` 里的新 Kotlin 网络层向 `linkup-test.51wnl-cq.com` 发出签名请求，返回 `status=200 msg=success` 和真实业务配置。剩下的活是把 981 个可读业务文件重写成工程，这是排期问题，不是能不能的问题。

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
| 未签名被拒（`参数av必须` / `1001`） | ✅ 实机复现 |
| 401 JWT 拦截说明签名关已过 | ✅ 实机复现 |
| AES 加解密自逆 | ✅ 单元测试 |
| 拦截器 11 参数顺序 / 两条豁免 / POST 体 md5 | ✅ 5 个 MockWebServer 单元测试，离线可跑 |
| **POST + 体 md5 被真服务端接受** | ⚠️ **未实机验证**——需要真实账号，见第八节 |
| 登录/好友/IM 等 90 余条路由的实际行为 | ⚠️ 未验证（同样需要登录态） |

`gradle :net:test` 5 tests / 0 failures；`gradle :net:liveVerify` 打印 `PROTOCOL_RECOVERED=1`。

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

## 六、架构决定（写死，避免重写时反复）

1. **包名保持 `com.youloft.watcher`**。三方后台配置（微信/QQ/环信/百度/各推送）都绑包名，换包名等于全部重申请。
2. **minSdk 26 / compileSdk 34 / targetSdk 34**，与原包一致。
3. **`:net` 是纯 JVM 模块**，Android 与单元测试都依赖它。设备状态通过 `DeviceContext` 接口注入，所以签名逻辑可以在没有手机的情况下回归。
4. Retrofit + OkHttp + Gson（原 1.6.6 即如此），不引 Room/Hilt 之前先把 `:net` 站稳。原包用了 Hilt 2.51.1、Room 2.6.1、MMKV，重建时在 `:app` 引入即可。
5. **响应 bean 暂用 `JsonElement`**，先把 97 条路由全部接上；字段结构按 1.5.6 的 `bean`（113 个可读文件）逐个补成 data class。这样不会出现"为了编译通过而瞎猜字段"。
6. 签名输入用 `encodedPath + "?" + encodedQuery`（原实现走 `java.net.URL.getPath()/getQuery()`，即已编码形态）。**重写时别改成解码形态**，否则签名静默失效。

## 七、APK 里根本没有、只能人去要

1. **后端服务端源码**。测试服泄露的信息很具体：服务名 `always_guard.api`，CI 路径 `/var/jenkins_home/workspace/desyb_always_guard.api`，Go 中间件 `internal/middleware/signature.go` / `jwt.go`。**Jenkins 那台机器上大概率还留着服务端源码**，这是第一优先。
2. **签名 keystore**。丢了就只能换签名重新上架，老用户覆盖安装会失败。
3. **三方后台账号**：微信开放平台、QQ 互联、环信、百度地图、小米/华为/荣耀/OPPO/vivo/魅族/个推/Mob、穿山甲/广点通/百度联盟/快手/AnyThink、数数 ThinkingData、阿里云 SLS、友盟/Bugly。
   AppID 已从 APK 提取（`report/04` 第五节），可凭 AppID 直接向平台发起主体认领，不必等找到密码。
4. 原注释、Gradle 打包配置、CI 脚本。

## 八、我在这台机器上物理做不了的验收（交给你/团队）

| # | 待验项 | 为什么这里做不了 |
|---|---|---|
| 1 | POST 带体请求被真服务端接受 | 需要真实登录态与合法手机号；未登录时业务关在 JWT 之前还是之后无法区分 |
| 2 | 探针页在真机上显示 200 与配置键 | `app-debug.apk` 已编出，但这台机器没有连可装的设备；装包与看屏必须人来 |
| 3 | 定位上报旧链路（`dmp.51wnl-cq.com` + 旧密钥）仍可用 | 属另一套协议，尚未接入重建工程 |
| 4 | 97 条路由逐条字段对齐 | 需要登录态逐条打 |
| 5 | 生产后端是否还活着、归属谁 | 只有公司运维/Jenkins 能回答 |

## 九、下一步顺序（建议）

1. **今天**：确认 Jenkins 与服务端源码归属（决定要不要重写后端，量级差别极大）
2. 把 1.6.6 的 936 个布局 + strings.xml 按业务依赖迁入 `:app`（要先补齐 appcompat/material/lottie 等依赖，否则会引用不到）
3. 按第五节文件数从大到小补 bean → repository → pages；每补一个模块，用 `:net:liveVerify` 加一条该模块的真实请求验证
4. 找回 keystore；找不回就新签名 + 老用户重装方案
5. 权限从 73 条砍到最小必要（`QUERY_ALL_PACKAGES`、`REQUEST_INSTALL_PACKAGES`、后台精确定位是上架审核与监管重点）
