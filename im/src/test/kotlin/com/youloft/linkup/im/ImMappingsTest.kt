package com.youloft.linkup.im

import io.openim.android.sdk.models.ConversationInfo
import io.openim.android.sdk.models.FriendInfo
import io.openim.android.sdk.models.Message
import io.openim.android.sdk.models.TextElem
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The SDK→neutral mappings, running for real on the JVM — the SDK's models are plain POJOs, so the one
 * part of the IM card that pure logic can verify does get verified: identity direction, the seconds
 * conversion, the latestMsg JSON tolerance, and the CmdFriendBean kind mapping.
 */
class ImMappingsTest {

    @Test
    fun `an incoming message takes the sender as peer, an outgoing one the receiver`() {
        val incoming = Message().apply {
            sendID = "10000001"
            recvID = "10000002"
            textElem = TextElem().apply { content = "妈，我到家了" }
            sendTime = 1_700_000_000_123
        }
        val outgoing = Message().apply {
            sendID = "10000002"
            recvID = "10000001"
            textElem = TextElem().apply { content = "好" }
            createTime = 1_700_000_100_000
        }

        val inFix = ImMappings.toImMessage(incoming, loginUserId = "10000002")
        assertEquals(10000001L, inFix.peerUserId, "the peer is whoever sent it to me")
        assertTrue(inFix.incoming)
        assertEquals("妈，我到家了", inFix.text)
        assertEquals(1_700_000_000L, inFix.epochSeconds, "milliseconds collapse to epoch seconds")

        val outFix = ImMappings.toImMessage(outgoing, loginUserId = "10000002")
        assertEquals(10000001L, outFix.peerUserId, "the peer is whoever I sent to")
        assertFalse(outFix.incoming)
        assertEquals(1_700_000_100L, outFix.epochSeconds, "no send stamp yet: the create time answers")
    }

    @Test
    fun `non-text content maps to an empty body and a non-numeric id degrades to zero`() {
        val voice = Message().apply {
            sendID = "10000001"
            recvID = "10000002"
            sendTime = 1_700_000_000_000
        }
        val fix = ImMappings.toImMessage(voice, loginUserId = "10000002")
        assertEquals("", fix.text, "the chat card ships text-only; other content types are later cards")
        assertNull(fix.clientId, "an SDK POJO without an id maps to null, not to a fake key")

        val weird = FriendInfo().apply { userID = "not-a-number" }
        assertEquals(0L, ImMappings.toImFriendEvent(weird, ImFriendEvent.ADD).userId)
    }

    @Test
    fun `friend events map onto the recovered CmdFriendBean kinds`() {
        val info = FriendInfo().apply {
            userID = "10000001"
            remark = "女儿"
        }

        val added = ImMappings.toImFriendEvent(info, ImFriendEvent.ADD)
        assertEquals(1, added.kind, "CmdFriendBean.ADD")
        assertEquals(10000001L, added.userId)
        assertEquals("女儿", added.remark)

        assertEquals(2, ImMappings.toImFriendEvent(info, ImFriendEvent.DELETE).kind, "CmdFriendBean.DELETE")
        assertEquals(3, ImMappings.toImFriendEvent(info, ImFriendEvent.UPDATE).kind, "CmdFriendBean.UPDATE")
    }

    @Test
    fun `conversation rows read the peer, badge and time straight off`() {
        val info = ConversationInfo().apply {
            userID = "10000001"
            unreadCount = 2
            latestMsgSendTime = 1_700_000_500_000
            latestMsg = """{"sendID":"10000001","content":"在吗"}"""
        }

        val row = ImMappings.toImConversation(info)
        assertEquals(10000001L, row.peerUserId)
        assertEquals(2, row.unread)
        assertEquals(1_700_000_500L, row.updatedAtSeconds)
        assertEquals("在吗", row.lastText, "this release shape carries the text in `content`")
    }

    @Test
    fun `latestMsg tolerates both shapes, blanks and garbage`() {
        assertEquals("在吗", ImMappings.latestText("""{"textElem":{"content":"在吗"}}"""), "the textElem shape")
        assertEquals("在吗", ImMappings.latestText("""{"content":"在吗"}"""), "the flat shape")
        assertNull(ImMappings.latestText(null))
        assertNull(ImMappings.latestText(""))
        assertNull(ImMappings.latestText("not json at all"), "garbage becomes no preview, not a crash")
    }

    @Test
    fun `a picture message carries its served url and an empty body`() {
        val message = Message().apply {
            sendID = "10000001"
            recvID = "10000002"
            sendTime = 1_700_000_000_000
            contentType = io.openim.android.sdk.enums.MessageType.PICTURE
            pictureElem = io.openim.android.sdk.models.PictureElem().apply {
                sourcePicture = io.openim.android.sdk.models.PictureInfo().apply { url = "http://minio/1.jpg" }
            }
        }

        val fix = ImMappings.toImMessage(message, loginUserId = "10000002")
        assertTrue(fix.isImage, "content type PICTURE marks the row as media")
        assertEquals("http://minio/1.jpg", fix.mediaUrl, "the served URL rides the neutral model")
        assertEquals("", fix.text)
    }
}
