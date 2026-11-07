package com.youloft.linkup.im

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.openim.android.sdk.models.ConversationInfo
import io.openim.android.sdk.models.FriendInfo
import io.openim.android.sdk.models.Message

/**
 * SDK model → neutral model, extracted from [OpenImImClient] into pure functions because the SDK's
 * models are plain POJOs: these mappings really run on the JVM in tests, which the glue around them
 * (native library, socket) never can.
 *
 * ## The identity rule, stated once
 *
 * OpenIM speaks string user ids; LinkUp's are numeric and OpenIM carries them verbatim
 * (`store.IMID` is just the decimal form). A send is "incoming" when its sender is not the logged-in
 * account, and the peer is whoever is on the other side of that comparison — the same answer both the
 * chat screen and the conversation list need, from the two different field pairs the SDK exposes
 * ([Message.sendID]/[Message.recvID] on messages, [ConversationInfo.userID] on conversations).
 *
 * ## Timestamps
 *
 * The SDK stamps milliseconds; the LinkUp layer speaks epoch **seconds** everywhere (`timePeriod`), so
 * every conversion here divides. [ImMessage.epochSeconds] prefers the send time and falls back to the
 * create time, because a locally-echoed send may not carry a server stamp yet.
 */
object ImMappings {

    /** [Message] → [ImMessage]. Text keeps its body; a picture carries its served URL in [ImMessage.mediaUrl]. */
    fun toImMessage(message: Message, loginUserId: String): ImMessage {
        val incoming = message.sendID != loginUserId
        val isPicture = message.contentType == io.openim.android.sdk.enums.MessageType.PICTURE
        return ImMessage(
            peerUserId = (if (incoming) message.sendID else message.recvID).toLongOrNull() ?: 0L,
            text = message.textElem?.content ?: "",
            incoming = incoming,
            epochSeconds = (message.sendTime.takeIf { it > 0 } ?: message.createTime) / 1000,
            clientId = message.clientMsgID,
            mediaUrl = if (isPicture) message.pictureElem?.sourcePicture?.url else null,
        )
    }

    /** [FriendInfo] → [ImFriendEvent]. A non-numeric id (should not happen) degrades to 0 rather than throwing mid-push. */
    fun toImFriendEvent(info: FriendInfo, kind: Int): ImFriendEvent = ImFriendEvent(
        kind = kind,
        userId = info.userID.toLongOrNull() ?: 0L,
        remark = info.remark,
    )

    /** [ConversationInfo] → [ImConversation]. */
    fun toImConversation(info: ConversationInfo): ImConversation = ImConversation(
        peerUserId = info.userID.toLongOrNull() ?: 0L,
        lastText = latestText(info.latestMsg),
        unread = info.unreadCount,
        updatedAtSeconds = info.latestMsgSendTime / 1000,
    )

    /**
     * The text a conversation row shows. `latestMsg` is a JSON string whose exact shape moved between
     * OpenIM versions — the text lived in `content` in some releases and inside `textElem` in others —
     * so both are tried before giving up, and anything unparseable becomes null, not a crash on the
     * conversation list.
     */
    fun latestText(latestMsgJson: String?): String? {
        if (latestMsgJson.isNullOrBlank()) return null
        return runCatching {
            val obj = JsonParser.parseString(latestMsgJson).asJsonObject
            obj.getAsJsonObject("textElem")?.get("content")?.takeIf { !it.isJsonNull }?.asString
                ?: obj.get("content")?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
    }
}

/** Where [ImMappings] sits: the gson instance the conversation parser uses, shared with the client. */
internal fun defaultGson(): Gson = Gson()
