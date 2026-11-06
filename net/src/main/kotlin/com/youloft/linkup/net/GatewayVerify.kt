package com.youloft.linkup.net

import com.youloft.linkup.api.dto.Permission

/**
 * 让**重建出来的客户端**（真 Retrofit 接口 + 真签名拦截器 + 真 Gson 模型）直接打**重建出来的网关**。
 *
 * 为什么值得单独存在：`:net:test` 的假仓库证明"我按我认为的形状发/收"，`server` 的 Go 测试证明
 * "服务端按我认为的形状发/收"，两边各自都能绿——只有这一条把两个"我认为"接到一起，
 * 让客户端的 bean 去解析服务端真实的字节。字段名不一致、类型不一致（`createAt` vs `createTime`、
 * `relation` 是数字还是字符串）在这种检查里会当场炸出来，而在任何一侧的单测里都不会。
 *
 * 跑法（网关要先起来，见 server/README.md）：
 *
 *	gradle :net:gatewayVerify --args=http://127.0.0.1:8090/ <dev-sms-code>
 *
 * 这里的 `/api/sms/send` 打的是**重建网关**，它没有任何短信通道（只把码写进 store 并打日志），
 * 所以这条不会发真短信；线上那台一律不碰。
 */
private class Verdict {
    var passed = 0
    var failed = 0

    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) {
            passed++
            println("  ok    $name")
        } else {
            failed++
            println("  FAIL  $name  ${if (detail.isNotEmpty()) "→ $detail" else ""}")
        }
    }
}

private class Session(val baseUrl: String, val phone: String, val devCode: String) {
    private var token = ""
    private var userId = 0L

    val api: LinkUpApi
        get() = LinkUpClient(
            baseUrl = baseUrl,
            context = StaticDeviceContext(
                userId = userId,
                mainUser = userId,
                deviceId = "gateway-verify-$phone",
                distinctId = "gateway-verify-$phone",
                shushuDeviceId = "gateway-verify-$phone",
                token = token,
            ),
        ).api

    suspend fun logIn(): ApiResponse<com.youloft.linkup.api.dto.User> {
        val sent = api.smsSend(phone = phone, region = "+86")
        check(sent.isSuccess) { "sms/send 没通过：status=${sent.status} msg=${sent.msg}" }
        val answer = api.smsClickToLogin(
            mutableMapOf("typeId" to "2", "phone" to phone, "smsCode" to devCode, "region" to "+86"),
        )
        if (answer.isSuccess && answer.data != null) {
            answer.data?.let { d ->
                token = d.token.orEmpty()
                userId = d.userId
            }
        }
        return answer
    }

    fun require(condition: Boolean, label: () -> String) {
        if (!condition) throw AssertionError(label())
    }
}

private inline fun check(condition: Boolean, lazyMessage: () -> Any) {
    if (!condition) throw AssertionError(lazyMessage().toString())
}

suspend fun main(args: Array<String>) {
    val base = args.firstOrNull() ?: "http://127.0.0.1:8090/"
    val devCode = args.getOrNull(1) ?: "000000"
    val v = Verdict()

    println("== 客户端 ↔ 重建网关：$base（短信码 = $devCode，本网关不发真短信）")

    val anon = LinkUpClient(
        baseUrl = base,
        context = StaticDeviceContext(deviceId = "gateway-verify-anon", distinctId = "gva", shushuDeviceId = "gva"),
    ).api

    val config = runCatching { anon.clientConfig() }.getOrElse {
        println("网关打不通：$it")
        println("先跑 server：go run ./cmd/server -jwt-secret x -openim-secret y …")
        kotlin.system.exitProcess(2)
    }
    v.check("clientConfig 被客户端模型解析", config.isSuccess && config.data != null, "status=${config.status}")
    config.data?.let {
        v.check("autoReportAddressShow 是数字", it.autoReportAddressShow == 0, "=${it.autoReportAddressShow}")
    }

    val a = Session(base, phone = "136${System.currentTimeMillis() % 100000000}", devCode = devCode)
    val b = Session(base, phone = "137${System.currentTimeMillis() % 100000000}", devCode = devCode)

    runCatching {
        val loginA = a.logIn()
        v.check("A 短信登录成功", loginA.isSuccess && !loginA.data?.token.isNullOrBlank(), "msg=${loginA.msg}")
        val userA = loginA.data!!
        v.check(
            "userId 是 8 位（否则客户端把它当匹配码时直接拒）",
            userA.userId.toString().length == 8,
            "= ${userA.userId}",
        )
        v.check("isRegister=true", userA.isRegister)
        v.check("phone 原样回来", userA.phone == a.phone, "= ${userA.phone}")

        val infoA = a.api.userInfo()
        v.check("userInfo 认出同一个账号", infoA.data?.userId == userA.userId, "= ${infoA.data?.userId}")
        v.check("userInfo 不再发新 token（客户端会覆盖会话）", infoA.data?.token.isNullOrBlank(), "= ${infoA.data?.token}")

        val updated = a.api.userUpdateInfo(
            mutableMapOf("avatar" to "https://x/a.png", "nickname" to "妈妈", "sex" to 2, "stage" to 3),
        )
        v.check("update/info 把 stage 存住（丢了就把人打回向导第一步）", updated.data?.stage == 3, "= ${updated.data?.stage}")
        v.check("nickname 改掉并回显", updated.data?.nickname == "妈妈", "= ${updated.data?.nickname}")

        val key = a.api.toolRsaPublicKey()
        val envelope = ContactSearchEnvelope().build(
            phones = listOf("13600000000"),
            keyId = userA.userId,
            serverPublicKeyPem = key.data?.publicKey,
        )
        v.check(
            "客户端的真剥皮逻辑能吃下服务端的公钥（四横杠页脚）",
            envelope != null,
            "publicKey=${key.data?.publicKey?.take(28)}…",
        )

        val loginB = b.logIn()
        v.check("B 登录成功", loginB.isSuccess, "msg=${loginB.msg}")
        val userB = loginB.data!!

        val pre = a.api.relationPreConfirm(code = userB.userId)
        v.check("pre_confirm 用 userId 当匹配码解出人", pre.data?.userId == userB.userId, "status=${pre.status} msg=${pre.msg}")
        v.check("pre_confirm 的 sex 是数字字符串（bean 声明 String，别发成真数字）", pre.data?.sex?.toIntOrNull() != null, "= ${pre.data?.sex}")

        val applied = a.api.apiRelationApply(
            mutableMapOf(
                "code" to userB.userId,
                "relation" to 1,
                "sensSetting" to mutableMapOf("sensLocation" to true, "sensAppUse" to false),
            ),
        )
        v.check("apply 成功", applied.isSuccess, "status=${applied.status} msg=${applied.msg}")
        v.check("applyStatus=1 即客户端渲染的\"已发送\"", applied.data?.applyStatus == 1, "= ${applied.data?.applyStatus}")

        val asks = b.api.apiRelationApplyList(page = 1, pageSize = 20)
        val row = asks.data?.list?.firstOrNull { it?.userId == userA.userId }
        v.check("B 的申请列表里读到 A", row != null, "status=${asks.status} size=${asks.data?.list?.size}")
        v.check("未处理行的 status=0", row?.status == 0, "= ${row?.status}")
        v.check("unreadCount 有值（客户端按它显示角标）", asks.data?.unreadCount != null)

        val confirmed = b.api.relationConfirm(
            code = userA.userId,
            payload = mutableMapOf(
                "code" to userA.userId,
                "relation" to 2,
                "sensSetting" to Permission(sensLocation = true, sensCall = true, sensAppUse = true),
            ),
        )
        v.check("confirm 成功", confirmed.isSuccess && confirmed.data?.friendLimit == 0, "status=${confirmed.status}")
        v.check("confirm 认得出请求人", confirmed.data?.friendUserId == userA.userId, "= ${confirmed.data?.friendUserId}")

        val friends = b.api.relationList(page = 1, pageSize = 50, key = "")
        val friend = friends.data?.list?.firstOrNull { it?.userId == userA.userId }
        v.check("B 的好友列表里有 A（真 IM 回来的）", friend != null, "status=${friends.status}")
        v.check("好友行的 relation 是数字并等于确认时选的 2", friend?.relation == 2, "= ${friend?.relation}")
        v.check(
            "好友行的 createAt 是秒（客户端会 ×1000）",
            friend != null && friend.createAt in 1_700_000_000L..9_999_999_999L,
            "= ${friend?.createAt}",
        )

        b.api.relationUpdateRemark(mutableMapOf("friendId" to userA.userId, "remark" to "姐姐"))
        val remarked = b.api.relationList(page = 1, pageSize = 50, key = "").data?.list
            ?.firstOrNull { it?.userId == userA.userId }
        v.check("备注写进真 IM 又读回来", remarked?.remark == "姐姐", "= ${remarked?.remark}")

        val detail = b.api.relationInfo(queryId = userA.userId)
        v.check("好友详情能打开", detail.isSuccess && detail.data?.userId == userA.userId, "status=${detail.status}")
        v.check("详情里的 relation 是字符串数字（与列表的 Int 不对称，bean 就这么写）", detail.data?.relation?.toIntOrNull() == 2, "= ${detail.data?.relation}")

        val del = b.api.relationDel(mutableMapOf("friendId" to userA.userId))
        v.check("删除成功", del.isSuccess, "status=${del.status} msg=${del.msg}")
        val afterDel = b.api.relationList(page = 1, pageSize = 50, key = "").data?.list
            ?.firstOrNull { it?.userId == userA.userId }
        v.check("删除后列表不再出现 A", afterDel == null, "= ${afterDel?.userId}")

        val otherSide = a.api.relationList(page = 1, pageSize = 50, key = "").data?.list
            ?.firstOrNull { it?.userId == userB.userId }
        v.check("删除是双向的：A 那边也不再有 B（真 IM 的单向删除陷阱）", otherSide == null, "= ${otherSide?.userId}")
    }.onFailure { e ->
        v.check("流程没有中途异常", false, "${e::class.simpleName}: ${e.message}")
    }

    println()
    println("CHECKS_PASSED=${v.passed} CHECKS_FAILED=${v.failed}")
    if (v.failed > 0) error("客户端与重建网关的契约有 $v.failed 处不一致")
    println("CLIENT_MEETS_OWN_SERVER=1")
}
