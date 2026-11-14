package com.youloft.watcher.data.im

import com.youloft.linkup.api.dto.FriendList
import com.youloft.linkup.net.LinkUpApi

/**
 * IM 命令发送方守门（审计 P0-3/P0-4 的修复）：锁机、截屏这类命令通道只信任确认过的好友——
 * 否则任何能发 OpenIM 自定义消息的对端都能锁死设备、拉走整屏截图。
 *
 * 判定口径是 `api/relation/list` 的确认好友名单（与 [ImFriendListSource] 同源）：先查本地缓存
 * （每次好友事件后重写，零网络），未命中再权威拉一次名单，让冷缓存（重装、换机后收到的
 * 第一条命令）不误拒。缓存与远端都不认的人 **fail-closed**：命令丢弃，不回执、不触发采集。
 */
class PeerGuard(
    private val api: LinkUpApi,
    private val cachedFriends: () -> List<FriendList.Info>,
) {
    /** 发送方是否确认好友。挂起因为未命中缓存时要拉一次权威名单。 */
    suspend fun trusted(peerUserId: Long): Boolean {
        if (cachedFriends().any { it.userId == peerUserId }) return true
        val response = runCatching {
            api.relationList(page = PAGE, pageSize = PAGE_SIZE, key = "")
        }.getOrNull()
        if (response?.isSuccess != true) return false
        return response.data?.list.orEmpty().filterNotNull().any { it.userId == peerUserId }
    }

    private companion object {
        const val PAGE = 1

        /** 与 `ImFriendListSource` 的刷新口径一致：名单一页拉全。 */
        const val PAGE_SIZE = 200
    }
}
