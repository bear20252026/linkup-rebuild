# LinkUp 重建工作区

源码丢失后，从 1.5.6 / 1.6.6 两个安装包恢复并重建客户端的工作区。

## 现在能跑的命令

```bash
# 离线回归：5 个单元测试锁住签名协议，不需要手机也不需要网络
sh tools/gradle.sh :net:test

# 实机验证：用重建的网络层向真实测试服发一条签名请求
sh tools/gradle.sh :net:liveVerify      # 期望打印 PROTOCOL_RECOVERED=1

# 出 APK
sh tools/gradle.sh :app:assembleDebug
```

`tools/gradle.sh` 直接用本机 `~/.gradle` 里已缓存的 Gradle 9.3.1，所以不用装 Gradle；需要 JDK 17+。

## 目录

| 路径 | 内容 |
|---|---|
| `net/` | 纯 JVM 网络层模块：AES 签名、公共参数拦截器、97 条路由的 Retrofit 接口 |
| `app/` | Android 壳，包名沿用 `com.youloft.watcher`，已带入 1.6.6 的 25MB assets |
| `recovered/` | 分析与验收产物：路由表、API 契约、模块清单、设计文档 |
| `tools/` | 反编译与还原脚本（可重复执行） |
| `_raw/` | 两个 APK、反编译输出、apktool 输出（**体积大且含内部代码，不入库**） |

## 恢复状态

已验证：GET + 签名被真实服务端接受（`status=200`）。
未验证：POST 体签名、97 条路由逐条字段——都需要真实登录态，见 `recovered/DESIGN_CN.md` 第八节。

## 红线

`_raw/` 与 `recovered/` 含公司产品内部结构与三方密钥。**不要把这个目录推到公开仓库。**
