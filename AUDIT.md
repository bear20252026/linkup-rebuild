# LinkUp(linkup-rebuild)全面审计报告

- **审计日期**:2026-11-12
- **基线**:master @ 70f135b(2026-11-10),工作区干净
- **范围**:安卓客户端 6 模块(app/location/net/map/im/fastkit,约 33.9k 行 Kotlin)、Go 服务端(21 文件 / 6.3k 行)、git 全历史、工程配置与文档
- **实测**:`go vet` 零输出;`go test ./...` 全绿(contract/gateway/location/openim/store)。安卓侧未实际执行(静态统计 523 个 `@Test`,README 声称的 418 已过期)

---

## 一、总览

| 维度 | 结论 |
|---|---|
| 服务端工程素质 | **良好**:JWT 强制 HS256 白名单、互斥锁全覆盖、1MiB 请求体上限、启动自拒默认密钥、无 SQL/路径遍历/SSRF 面 |
| 客户端工程素质 | **良好**:全站 HTTPS 无 trustAll、`allowBackup=false`、导出组件仅 Splash 一个、无障碍最小化配置、通讯录 RSA+AES 封装 |
| 安全漏洞 | **P0 × 4、P1 × 5、P2 × 15 左右**——集中在"信任了不该信的输入" |
| 仓库卫生 | git 全历史**无真实凭据泄露**;但有部署 IP 入库、文档滞后 3-6 个提交 |
| 最大短板 | **服务端授权模型**:好友关系/隐私开关几乎不设防 |

---

## 二、P0(4 项)

### P0-1(服务端)`POST /api/user/login`(loginByThird)无凭据账号接管 + 免费建号

**位置**:`server/internal/gateway/handlers.go:172-197`、`server/internal/store/store.go:329-360`、`gateway.go:249`(免鉴权)

第三方登录的 `unionId` 与 `phone` 均由客户端自报,服务端**未与微信/QQ 做任何服务端校验**(无 OAuth code 换取),`ThirdAccount` 只要 phone 命中即返回该账号并颁发 JWT:

```go
// store.go:349-353
case byPhone != nil:
    if unionID != "" && byPhone.UnionID == "" {
        byPhone.UnionID, byPhone.Platform = unionID, platform   // 任意 unionId 绑到受害者账号
    }
    byUnion = byPhone   // ← 返回受害者账号
```

**两条利用链**:
1. **接管已有手机号账号**:`{"unionId":"任意串","platform":"4","phone":"<受害者手机号>"}` → 直接拿到受害者 JWT(含短信登录账号);byUnion 分支知道 unionId 亦可登录。
2. **免费发放账号**:`{"unionId":"x","platform":"4"}` → 无需邀请码、无需验证码即建号领 token——邀请码防线(P2-5)被整体旁路,并使 P0-2 门槛归零。另有预占号风险:先抢注 unionId,真实用户登录时落入攻击者控制的账号。

**修复**:重建客户端无微信/QQ SDK 入口(`LoginViewModel.loginByThird` 无 UI 调用方),按仓库既有范式(一键登录 handlers.go:111-116)**拒绝出声**;删除 `Store.ThirdAccount` 死代码。将来接真 OAuth(code→openid 服务端换取)时重写。
**【2026-11-12 修订】**按所有者拍板改为**开关暂停**方案:两侧同名开关(客户端 `ENABLE_THIRD_PARTY_LOGIN=false` 隐藏入口,服务端 `-enable-third-login=false` 时路由回 status=403),`ThirdAccount` 代码恢复保留、绑定测试以 mutator 形式存续,恢复前置条件=厂商 OAuth code 服务端换取(DESIGN 五点三十六、待验 83)。

### P0-2(服务端)伪造关系行 → 读取任意用户的完整位置历史

**位置**:`handlers.go:530-540`(updateRelation)、`handlers_settings.go:74-100`(privacyUpdate)、`location.go:114-116`(hasRelation)

`POST /api/relation/update` 与 `POST /api/setting/update` 对 `friendId` **没有任何好友/关系校验**,却直接写 Extra 行(`PutExtra` 无条件刷新 `Since`),而位置读路径的授权只看关系行是否存在:

```go
// location.go:114-116
func hasRelation(s *store.Store, a, b int64) bool {
    return s.ExtraFor(a, b).Since != 0 || s.ExtraFor(b, a).Since != 0
}
```

**攻击链**:认证用户 `POST /api/relation/update {"friendId": <任意受害者id>, "relation": 1}` → `GET /api/location/v2/tracks?queryId=<受害者id>` → 读取该用户全部天数、完整轨迹、实时位置。`/api/screenshot/done` 的 friendId 同样无校验(P2-2,只写台账)。

**修复**:两条写路由加 `IM.IsFriend` + 确认对(`Extra.Since != 0`,即 `relation/confirm` 是唯一授权路径)双重校验;privacyUpdate 的"无关系行按好友兜底"随校验移除。

### P0-3(客户端)远程静默截屏无发送方身份校验

**位置**:`app/src/main/kotlin/com/youloft/watcher/data/im/ScreenshotCommandBridge.kt:36-71`

收到 `SCREEN_SHOT` 命令即:回 step1(无障碍就绪)→ 采整屏 → PNG 落 cache → 上传 MinIO → 把图 URL 回给发起方。**全程无确认弹窗、不校验发起方身份**。缓解:bridge 仅在 `ScreenshotActivity` 打开期间存活(`ScreenshotActivity.kt:44-50`)。截图含通知栏、聊天记录、密码输入瞬间的完整屏幕内容。

**修复**:bridge 加发送方守门(`PeerGuard`:本地好友缓存命中即信任,未命中权威拉一次 `api/relation/list`),非确认好友的命令 fail-closed 直接丢弃。

### P0-4(客户端)远程锁机无发送方身份校验

**位置**:`app/src/main/kotlin/com/youloft/watcher/data/im/LockCommandBridge.kt:23-35`、`pages/main/MainActivity.kt:61-81`

收到 `LOCK_MACHINE` 命令直接解析 `LockScreen` 回调 `onLock`,无发送方与守护关系校验;`MainActivity` 存活期间锁机桥常驻在线。任意 IM 好友可发起勒索式锁机(`TYPE_APPLICATION_OVERLAY` 全屏盖板 / 降级 `LockedActivity`,答错题不解锁)。

**修复**:同 P0-3,bridge 层校验发送方 ∈ 确认好友,非好友命令丢弃。锁机本是产品功能,但"任意好友可触发"而不是"已确认关系"才是缺陷。

---

## 三、P1(5 项)

1. **密码哈希 = 无盐 SHA-256**(`server/internal/gateway/handlers_auth.go:17-20`):静态前缀 `"linkup:"` 等效无盐,单轮快哈希,GPU 约 10⁹-10¹⁰ 次/秒离线爆破;下限仅 6 位;`password_login` 无失败锁定/限速,且区分"账号不存在(1002)/密码错误(403)"可在线穷举。另:错误区分 + 非常量时间比较(`!=`)记录在案。→ 换 bcrypt/argon2id(per-user 盐)+ 登录失败限速。**附带可用性 bug**:`PasswordHash` 带 `json:"-"`(`store.go:36`)从不落盘,服务重启后所有用户名账号恒"密码错误"。
2. **好友隐私开关(sens 旗标)服务端读路径不强制**(`location.go:102-116`、`handlers_device.go:75-104`):`locationTarget` 只看 `hasRelation`,SensLoc/SensCall/SensAll 任何方向都不参与判定;widget 域(`guardPeers`)只看 `Relation > 0`。对方关闭位置共享后好友仍可读其全部历史轨迹——隐私承诺只靠客户端自觉。
3. **短信验证码 4 位 + 重发重置尝试计数**(`handlers.go:91-106`、`store.go:602-646`):`/api/sms/send` 免鉴权免签无速率限制;每码错 5 次作废但重发生成新码且 Attempts 归零——循环"发码→猜5次→再发码"等效无限爆破(期望约 10⁴ 次接管手机号账号)。当前不接短信通道,码只进日志(handlers.go:104,**验证码明文入日志**);一旦接回真实 SMS 即成远程接管。→ 6 位码、IP+号码限频、重发不重置计数。
4. **WebView token 外泄链**(`app/src/main/kotlin/com/youloft/watcher/web/CommonInterceptor.kt:83-92`、`com/youloft/webview/CommonWebView.kt:54-59`):容器内**任意** http(s) 导航被追加 `userid=<id>&token=<JWT>&av=1.6.6&client=android`,无域名白名单;H5 action 的 `h5Activity`/`jumpPage`(`H5Commands.kt:86,96`)允许容器内任意页面打开任意 URL 新容器 → 链式组合可把 JWT 送到第三方服务器,`http://` 则明文过网。→ 域名白名单 + 只对自家域追加凭据。
5. **请求签名密钥硬编码且 KEY==IV**(`net/src/main/kotlin/com/youloft/linkup/net/Crypto.kt:13`):`6R4aXTSK3OghHIgA` 随 APK 分发,AES-128-CBC、IV=key、无时间戳/nonce → 任何人可对任意请求伪造合法 `Signature` 头。属"拍板已接受"的协议约束(README:101-103),但应文档明示**签名不是安全边界**,真正的边界是 JWT。

---

## 四、P2 摘要(按影响排序)

| # | 发现 | 证据 |
|---|---|---|
| P2-1 | `Locations` 无限追加:无上限/无淘汰/无去重;store 单 JSON 全量重写(`MarshalIndent`),恶意高频上报吃满内存磁盘;`Fixes`/`DaysFor` 等全表线性扫,二次 DoS | `server/internal/store/store.go:689-697,235-251,700-811` |
| P2-2 | 免鉴权路径驱动的无界 map:`Codes` 无 TTL 清理(`/api/sms/send` 任意号码各留一行);`Screens` 只写不读、friendId 无好友校验 | `store.go:615,749-757`、`handlers_device.go:182-196` |
| P2-3 | 静态数据 0644 明文落盘:手机号、unionId、全员完整轨迹、验证码明文(`Token`/`PasswordHash` 因 `json:"-"` 不落盘,正确) | `store.go:235-251` |
| P2-4 | JWT 无吊销:30 天内泄露不可作废;`SaveToken` 死代码;不校验 issuer/audience | `contract/auth.go:42-57`、`store.go:517-527` |
| P2-5 | 默认邀请码 `LINKUP2026` 随公开仓库泄露且为旗标默认值;`InviteCode == ""` 时完全跳过校验 | `cmd/server/main.go:42`、`handlers_auth.go:41`。**【2026-11-12 已修】**:邀请码模型落地(存在/未过期/未用尽,单锁原子注册,扣减落盘),`-invite-codes-file` 支持多码,校验恒开;LINKUP2026 仍是默认 seed——该码本身是否换值属运维决定 |
| P2-6 | 传输安全依赖外层 Caddy/Cloudflare;git 历史永久披露生产 IP `101.133.235.110`(曾作为明文 http baseUrl)与域名 `gateway.cloudbear.cloud` | 提交 4ab3264、352d0c7;`net/.../LinkUpClient.kt:9-10` |
| P2-7 | dev 万能后门:`-dev-sms-code` 任意手机号登录任意账号;dev JWT secret 固定可自签任意 userId——缓解已有(非 dev 拒绝默认值 main.go:50-61) | `handlers.go:131-133`、`main.go:65-69` |
| P2-8 | RSA 私钥 `linkup-rsa.key` Windows 下 0600 失效(实测 `-rw-r--r--`);未入 git(`.gitignore:11`,全历史无) | `main.go:164-176`、`README.md:192-193` |
| P2-9 | MMKV `cryptKey=null` 明文存 JWT + 账户 JSON;SharedPreferences 回退层同明文 | `app/.../data/local/KeyValueStore.kt:71-79`、`UserCache.kt:28-31`、`AndroidDeviceContext.kt:25-50` |
| P2-10 | logcat 输出实时坐标且 release 未混淆不剥离(`isMinifyEnabled=false`) | `TrackCollectService.kt:79,83`、`LocationCollector.kt:124-174`、`app/build.gradle.kts:18-20` |
| P2-11 | H5 可读剪贴板全文(任意加载页面,无域名校验) | `H5Commands.kt:15`、`CommonInterceptor.kt:100-105` |
| P2-12 | `AndroidJsInterface.getContext()` 把 Context 交给 JS(经典 JS→Java 反射跳板;注释自述"原版也暴露,保留") | `AndroidJsInterface.kt:26-27` |
| P2-13 | `saveMediaFile`:H5 驱动 GET 任意 URL 写公共媒体库 + 回调名拼接执行(`intent.callback + "(…)"` 未白名单) | `CommonInterceptor.kt:128-131,182-226` |
| P2-14 | `selectPictures` 把本地照片绝对路径回给页面;缓存目录照片副本无清理 | `CommonInterceptor.kt:229-245` |
| P2-15 | 密码明文字段进 JSON body(与通讯录 RSA+AES 封装的安全水准倒挂) | `app/.../data/LoginRepository.kt:63,66` |
| P2-16 | 锁机悬浮窗用了 `SYSTEM_ALERT_WINDOW` 但合并清单未声明该权限 → `addView` 必然失败被 `runCatching` 吞掉,盖板路径永不生效(功能缺陷 + 权限欺骗面) | `LockOverlayWindow.kt:57-72`;三模块 manifest 均无该权限 |

P3 十余项:无证书锁定、query 串带 `deviceId(=ANDROID_ID)`/`distinctId`、百度 AK/签名 key 随库公开、chunked 请求丢 body、锁机路由不校验 SensLockPhone、`unlock` 无校验、`settingIMUpdate` 原样存 1MiB body、`pre_confirm`/`relation/info` 资料枚举面、验证码明文入日志、持锁同步写盘、调试探针 Activity(`RecoveryProbeActivity`)、`JsBridge` 注入 `window._eval`、签名层按路径段粗放免签(`login`/`sms`)、供应链反射绑定无完整性校验、OpenIM SDK patch 线版本等。

---

## 五、服务端路由清单(39 条;`签名` = 是否要求 Signature 头)

鉴权依据:`gateway.go:249` skip 列表;签名依据:`gateway.go:224-231`(路径段含 `login`/`sms` 免签)。

| 方法 | 路径 | 鉴权 | 签名 | 评估 |
|---|---|---|---|---|
| GET | /api/client/config | 无 | 需 | 公开静态开关 ✔ |
| GET | /api/sms/login/cut | 无 | 免 | 公开 |
| GET | /api/sms/send | 无 | 免 | ⚠ 无速率限制(P1-3/P2-2) |
| POST | /api/sms/click_to_login | 无 | 免 | 拒绝出声 ✔(需厂商 SDK) |
| POST | /api/user/login | 无 | 免 | **P0-1 已修:拒绝出声** |
| POST | /api/user/register | 无 | 免 | ⚠ 邀请码默认公开(P2-5) |
| POST | /api/user/password_login | 无 | 免 | ⚠ 无爆破限速(P1-1) |
| GET | /api/user/info · /api/user/im_token | JWT | 需 | 仅自己 ✔ |
| POST | /api/user/update/info | JWT | 需 | 仅自己 ✔ |
| GET | /api/tool/rsa/public_key | JWT | 需 | 公钥 ✔ |
| GET | /api/relation/pre_confirm · /api/relation/info | JWT | 需 | 任意 id 资料枚举(P3,产品语义) |
| POST | /api/relation/apply · confirm · del | JWT | 需 | OpenIM 侧校验 ✔ |
| GET | /api/relation/apply_list · list · lock_list | JWT | 需 | 仅自己 ✔ |
| POST | /api/relation/update | JWT | 需 | **P0-2 已修:IsFriend+确认对** |
| POST | /api/relation/update_remark | JWT | 需 | OpenIM 校验 owner ✔ |
| GET | /api/relation/lock/question_list · cover/list | JWT | 需 | 静态 ✔ |
| POST | /api/relation/lock | JWT | 需 | IsFriend ✔(⚠ 不校验 SensLockPhone,P3) |
| POST | /api/relation/unlock · lock/cover/del | JWT | 需 | 仅清自己台账,影响低 |
| POST | /api/phone/state/report | JWT | 需 | 仅自己 ✔ |
| GET | /api/widget/location | JWT | 需 | IM 好友+Relation>0(⚠ sens 不强制,P1-2) |
| GET | /api/widget/track | JWT | 需 | 仅自己 ✔ |
| GET | /api/screenshot/list | JWT | 需 | 仅自己 ✔ |
| GET | /api/screenshot/done | JWT | 需 | ⚠ friendId 无好友校验(P2-2) |
| GET/POST | /api/setting/im · user_list · update | JWT | 需 | 仅自己;update **P0-2 已修** |
| GET | /api/index | JWT | 需 | IM 好友 ✔ |
| GET | /api/version/check | JWT | 需 | 恒答最新 ✔ |
| POST | /api/location/report | JWT | 需 | 仅自己(⚠ 无上限,P2-1) |
| GET | /api/location/days · v2/tracks | JWT | 需 | ⚠ hasRelation 即放行(P0-2 修后依赖写路由收紧) |

**做得好的**:JWT 解析强制 `WithValidMethods([HS256])`;互斥锁全覆盖;1MiB 请求体上限;启动拒绝公开默认密钥;验证码一次性/5 分钟/错5次作废;`api/user/im_token` 只为自己签发。

---

## 六、安卓客户端组件与权限

**exported 组件**:仅 `SplashActivity`(LAUNCHER,只判 extras 判空,无深链 scheme);其余 27 Activity/Service/Receiver/Provider 均 false。`AppAccessibilityService` 由系统绑定(`BIND_ACCESSIBILITY_SERVICE`),`canRetrieveWindowContent` 未开启、`canTakeScreenshot=true`(P0-3 触发面)。`FileProvider` 作用域最小(`cache-path camera/`)。

**权限(合并后 13 项)**:INTERNET、READ_CONTACTS、CAMERA、READ_MEDIA_IMAGES、READ_EXTERNAL_STORAGE(maxSdk 32)、VIBRATE、POST_NOTIFICATIONS、ACCESS_BACKGROUND_LOCATION、FOREGROUND_SERVICE(_LOCATION)、ACCESS_COARSE/FINE_LOCATION(location 模块)。**未声明**:SYSTEM_ALERT_WINDOW(但代码在用,见 P2-16)、QUERY_ALL_PACKAGES(仅 `<queries>` 白名单微信/QQ,良好)、WRITE_SETTINGS、READ_PHONE_STATE、RECEIVE_BOOT_COMPLETED。`allowBackup=false`;未声明 dataExtractionRules(API 31+ 默认禁)。

---

## 七、仓库卫生与文档

**通过的**:git 全历史 grep 无 password/secret/token/PEM/AKIA/ghp_/sk- 真实命中;`.key`/`.jks`/`linkup-store.json`/`local.properties` 从未入库;服务端秘密全走 flag/env 注入且非 dev 启动自拒默认值;"仓库公开拍板记录"(README:100-105)与提交 42798fa 一致;依赖链无已知 CVE 版本(okhttp 4.12/gin 1.11/jwt v5.2.2/x/crypto 0.40);settings.gradle.kts 7 模块与目录一致;compileSdk/minSdk/targetSdk = 34/26/34 统一;DESIGN 待验清单抽查 4 个"已闭环"项(77/78/47-49/22)代码证据全部属实。

**需要处理的**:

| # | 发现 | 严重度 | 证据 |
|---|---|---|---|
| 7.1 | 部署 IP 在 HEAD 代码注释 | 中 | `net/.../LinkUpClient.kt:9`:`101.133.235.110`(历史曾为明文 http baseUrl) |
| 7.2 | README 大面积过期:测试数 418(实际 ~523)、路由 97(实际 104)、server 路由 20(实际 39)、gatewayVerify 30/46 两口径打架(实际 47)、`Splash 尚未重建`自相矛盾 | 中 | README.md:8,15,44,50,62,66-68,87,93 |
| 7.3 | `tools/jadx` 6 文件仍被跟踪(提交 7055c63 只加了 ignore 规则未真出库) | 低 | `git ls-files tools/jadx/` |
| 7.4 | DESIGN 待验 41/79 表行未同步(代码已按拍板执行/真跳转) | 低 | DESIGN_CN.md:2032,1806 |
| 7.5 | release `isMinifyEnabled=false`(与原版 R8 不一致,出正式包前需拍板) | 低 | `app/build.gradle.kts:18-20` |
| 7.6 | 历史最大 blob 字体 9.4MB;广告 SDK jar(bdxadsdk 1.69MB/gdtadv2 2.08MB)不可审计二进制随库随包;res166 占跟踪文件 82%(拍板允许) | 低 | git 历史、`app/src/main/assets/` |
| 7.7 | `.gitignore` 缺 `.kotlin/`、`*.jks`/`*.keystore`/`.idea/`/`captures/`/`*.hprof` 防御性规则 | 低 | `.gitignore` |
| 7.8 | viewpager2 用 beta 版(正式版 1.1.0 已发布) | 低 | `app/build.gradle.kts` |

---

## 八、修复优先级建议

1. **已完成(本审计随附)**:P0-1/P0-2(服务端)、P0-3/P0-4(客户端 bridge)。
2. **尽快**:bcrypt + 登录限速 + 重启密码失效 bug;短信码 6 位 + 限频;sens 旗标服务端强制(P1-2)。
3. **随后**:store 0600 + Locations 上限/TTL + Codes/Screens 清理;WebView 域名白名单;MMKV 加密;release 开混淆;screenshot/done 加好友校验。
4. **收尾**:README/DESIGN 同步;`tools/jadx` 真出库;IP 从代码注释摘除;`.gitignore` 防御性规则补齐。
