package com.youloft.watcher.data.im

import com.youloft.linkup.im.CmdType
import com.youloft.linkup.im.ImClient
import com.youloft.linkup.net.LinkUpApi
import com.youloft.watcher.bean.lock.LockScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 锁机命令桥：发起端把 [LockScreen] 经 IM `lockMachine` 命令发给对方（原版 CmdMsgManager.A/K/L 的
 * 重建），收端订阅 [ImClient.commands] 交给 [onLock]——悬浮窗宿主（WindowHelper 的重建位）决定
 * 是全屏锁住还是降级通知。台账（`api/relation/lock`/`unlock`）由发起方调 API，命令与台账同时发。
 */
class LockCommandBridge(
    private val imClient: ImClient,
    private val api: LinkUpApi,
    private val scope: CoroutineScope,
    private val onLock: (LockScreen) -> Unit,
) {
    fun start() {
        imClient.commands
            .onEach { command ->
                if (command.action != CmdType.LOCK_MACHINE) return@onEach
                val payload = LockScreen.fromJson(command.data) ?: return@onEach
                if (payload.lockType == LOCK_TYPE_REMOTE_UNLOCK) {
                    // lockType 3 在原版是"解锁到问题确认"变体；本 round 的解锁只走台账，收端不弹窗。
                    return@onEach
                }
                onLock(payload)
            }
            .launchIn(scope)
    }

    /** 发起锁机：台账先行，命令随后（任一失败都向上抛，由调用方决定提示）。 */
    suspend fun sendLock(peerUserId: Long, screen: LockScreen) {
        api.relationLock(
            payload = mutableMapOf(
                "friendId" to peerUserId,
                "lockDuration" to screen.lockDuration,
                "lockTitle" to (screen.lockTitle ?: ""),
                "lockBackPic" to (screen.lockBackPic ?: ""),
                "question" to (screen.question?.content ?: ""),
            ),
        )
        imClient.sendCommand(peerUserId, CmdType.LOCK_MACHINE, screen.toJson())
    }

    suspend fun sendUnlock(peerUserId: Long) {
        api.relationUnlock(payload = mutableMapOf("friendId" to peerUserId))
        imClient.sendCommand(
            peerUserId,
            CmdType.LOCK_MACHINE,
            LockScreen(userid = peerUserId, lockType = LOCK_TYPE_REMOTE_UNLOCK).toJson(),
        )
    }

    companion object {
        /** `widget/im/e.java` 的 sendLocked：设置页发起。 */
        const val LOCK_TYPE_FROM_SETTINGS = 1

        /** `K(LockInfo)`：列表行远程锁。 */
        const val LOCK_TYPE_REMOTE_LOCK = 2

        /** `L(...)`：解锁到对方（带问题确认）。 */
        const val LOCK_TYPE_REMOTE_UNLOCK = 3
    }
}
