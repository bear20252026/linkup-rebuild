package com.youloft.watcher.pages.friend

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.api.dto.Friend
import com.youloft.linkup.api.dto.FriendApply
import com.youloft.linkup.api.dto.FriendInfo
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.api.dto.SearchFriend
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.R
import com.youloft.watcher.data.ContactEntry
import com.youloft.watcher.data.ContactSource
import com.youloft.watcher.pages.friend.FriendFlowFixtures.TestApplication
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Regression net for the contact-match screen: what goes to the server, what the list does with the
 * answer, and the two places its ordering differs from the add-friend screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class ContactInfoListActivityTest {

    private val repository get() = FriendFlowFixtures.repository
    private val flags get() = FriendFlowFixtures.flags

    class FakeDialogs : AddFriendDialogs {
        val opened = mutableListOf<String>()
        var pendingConfirm: ((Int) -> Unit)? = null
        var permissionChoice: Permission? = null

        override fun verifyInfo(
            friend: Friend,
            type: Int,
            mySex: Int,
            onVipLabelClick: (source: Int, label: String) -> Unit,
            onConfirm: (Int, () -> Unit) -> Unit,
        ) {
            opened += "verify:${friend.userId}:$type:mySex=$mySex"
            pendingConfirm = { relation -> onConfirm(relation) {} }
        }

        override fun permissionSettings(
            source: Int,
            onResult: (Permission?) -> Unit,
            onDismissed: () -> Unit,
        ) {
            opened += "permissions:$source"
            onResult(permissionChoice)
            onDismissed()
        }
    }

    /** Records the sheet the screen puts over the list. */
    class FakeOverlay : LoadingOverlay {
        val calls = mutableListOf<String>()
        override fun showLoading() { calls += "show" }
        override fun hideLoading() { calls += "hide" }
    }

    private lateinit var dialogs: FakeDialogs
    private lateinit var overlay: FakeOverlay
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FriendFlowFixtures.reset()
        FriendFlowFixtures.seedAccount(userId = 4242L, nickname = "我", sex = 1)
        dialogs = FakeDialogs()
        overlay = FakeOverlay()
    }

    private fun launch(contacts: ContactSource = ContactSource.of()): ContactInfoListActivity {
        FriendFlowFixtures.contacts = contacts
        val controller = Robolectric.buildActivity(ContactInfoListActivity::class.java)
        controller.get().dialogs = dialogs
        controller.get().overlay = overlay
        controller.setup()
        idle()
        return controller.get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * The apply path is three hops deep (address book → matched list → row → person record), and the
     * search happens on entry, so everything the flow needs has to be staged before the screen starts.
     */
    private fun launchToSheet(person: Friend = Friend(userId = 900L, nickname = "小明")): ContactInfoListActivity {
        repository.publicKey = pem(serverKeyPair().public)
        repository.contactResponse = ApiResponse(
            200,
            "success",
            data = SearchFriend(list = listOf(FriendInfo(userId = person.userId, nickname = "小明"))),
        )
        repository.preConfirmResponse = ApiResponse(200, "success", data = person)
        val activity = launch()
        measure(activity)
        rowAt(activity, 0).performClick()
        idle()
        assertNotNull(dialogs.pendingConfirm, "the verify sheet should be up by now")
        return activity
    }

    private fun <T : View> view(activity: ContactInfoListActivity, id: Int): T = activity.window.decorView.findViewById(id)

    private fun list(activity: ContactInfoListActivity) = activity.window.decorView.findViewById<RecyclerView>(R.id.rlContactList)

    /** Robolectric needs an explicit pass before the RecyclerView hands out view holders. */
    private fun measure(activity: ContactInfoListActivity) {
        val rv = list(activity)
        rv.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
        )
        rv.layout(0, 0, 1080, 2400)
    }

    private fun rowAt(activity: ContactInfoListActivity, position: Int): View =
        list(activity).findViewHolderForAdapterPosition(position)!!.itemView

    private fun toast(): String? = ShadowToast.getTextOfLatestToast()

    private fun serverKeyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun pem(publicKey: java.security.PublicKey): String {
        val body = Base64.getEncoder().withoutPadding().encodeToString(publicKey.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$body\n----END PUBLIC KEY-----\n"
    }

    /** Reads the sealed body back, which is the only way to prove the normalization reached the server. */
    private fun phonesInTheEnvelope(encrypted: String, keyEncrypted: String, key: java.security.PrivateKey): List<String> {
        val aesKey = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").let {
            it.init(Cipher.DECRYPT_MODE, key)
            it.doFinal(Base64.getDecoder().decode(keyEncrypted))
        }
        val payload = String(
            Cipher.getInstance("AES/CBC/PKCS5Padding").let {
                it.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesKey))
                it.doFinal(Base64.getDecoder().decode(encrypted))
            },
            Charsets.UTF_8,
        )
        val inner = String(
            Base64.getDecoder().decode(payload.substringAfter("\"ciphertext\":\"").substringBefore("\"")),
            Charsets.UTF_8,
        )
        val array = com.google.gson.JsonParser.parseString(inner).asJsonObject.getAsJsonArray("phoneList")
        return array.map { it.asString }
    }

    @Test
    fun `the address book is stripped and deduplicated before it is sealed and uploaded`() {
        val pair = serverKeyPair()
        repository.publicKey = pem(pair.public)
        repository.contactResponse = ApiResponse(200, "success", data = SearchFriend(list = emptyList()))

        launch(
            ContactSource.of(
                ContactEntry(name = "张三", phone = "+86 13800138000"),
                ContactEntry(name = "李四", phone = " 138 0013 8000 "),
                ContactEntry(name = "王五", phone = "13800138000"),
                ContactEntry(name = "只有邮箱", phone = null, email = "w@example.com"),
            ),
        )

        val (encrypted, keyEncrypted) = repository.contactSearches.single()
        assertEquals(
            listOf("13800138000", ""),
            phonesInTheEnvelope(encrypted, keyEncrypted, pair.private),
            "the two spellings of the same number collapse, and a phone-less contact still contributes an empty string",
        )
    }

    @Test
    fun `the sheet goes up before the address book is read and comes down on the answer`() {
        repository.publicKey = pem(serverKeyPair().public)
        repository.contactResponse = ApiResponse(200, "success", data = SearchFriend(list = emptyList()))

        launch()

        assertEquals(listOf("show", "hide"), overlay.calls)
    }

    @Test
    fun `an empty answer shows the empty state`() {
        repository.publicKey = pem(serverKeyPair().public)
        repository.contactResponse = ApiResponse(200, "success", data = SearchFriend(list = emptyList()))
        val activity = launch()

        assertEquals(View.VISIBLE, view<View>(activity, R.id.llEmpty).visibility)
        assertEquals(View.GONE, view<View>(activity, R.id.rlContactList).visibility)
    }

    @Test
    fun `a null list is treated as empty too`() {
        repository.publicKey = pem(serverKeyPair().public)
        repository.contactResponse = ApiResponse(200, "success", data = SearchFriend(list = null))
        val activity = launch()

        assertEquals(View.VISIBLE, view<View>(activity, R.id.llEmpty).visibility)
    }

    @Test
    fun `matched people fill the list`() {
        repository.publicKey = pem(serverKeyPair().public)
        repository.contactResponse = ApiResponse(
            200,
            "success",
            data = SearchFriend(
                list = listOf(
                    FriendInfo(userId = 900L, nickname = "小明", sex = "1", remark = ""),
                    FriendInfo(userId = 901L, nickname = "小红", sex = "2", remark = "邻居"),
                ),
            ),
        )
        val activity = launch()

        assertEquals(View.GONE, view<View>(activity, R.id.llEmpty).visibility)
        assertEquals(View.VISIBLE, view<View>(activity, R.id.rlContactList).visibility)
        measure(activity)
        assertEquals(2, list(activity).adapter!!.itemCount)

        val first = rowAt(activity, 0)
        assertEquals("小明", first.findViewById<TextView>(R.id.tv_name).text.toString())
        assertEquals("匹配码:900", first.findViewById<TextView>(R.id.tv_match_code).text.toString())
        assertNotNull(first.findViewById<ImageView>(R.id.iv_gender).drawable)
        // The remark the user wrote wins over the stranger's own nickname.
        assertEquals("邻居", rowAt(activity, 1).findViewById<TextView>(R.id.tv_name).text.toString())
        // The recovered adapter hides the divider under the last row only.
        assertEquals(View.VISIBLE, first.findViewById<View>(R.id.lineBottom).visibility)
        assertEquals(View.GONE, rowAt(activity, 1).findViewById<View>(R.id.lineBottom).visibility)
    }

    @Test
    fun `a failed lookup toasts the server message and still empties the list`() {
        repository.publicKey = pem(serverKeyPair().public)
        repository.contactResponse = ApiResponse(500, "通讯录匹配失败")
        val activity = launch()

        assertEquals("通讯录匹配失败", toast())
        assertEquals(View.VISIBLE, view<View>(activity, R.id.llEmpty).visibility)
    }

    @Test
    fun `the no-server-key status stays silent but still empties the list`() {
        repository.publicKey = null
        val activity = launch()

        assertNull(toast())
        assertEquals(View.VISIBLE, view<View>(activity, R.id.llEmpty).visibility)
        assertTrue(repository.contactSearches.isEmpty())
    }

    @Test
    fun `tapping a row looks that person up by id`() {
        repository.publicKey = pem(serverKeyPair().public)
        repository.contactResponse = ApiResponse(
            200,
            "success",
            data = SearchFriend(list = listOf(FriendInfo(userId = 900L, nickname = "小明"))),
        )
        repository.preConfirmResponse = ApiResponse(200, "success", data = Friend(userId = 900L))
        val activity = launch()

        measure(activity)
        rowAt(activity, 0).performClick()
        idle()

        assertEquals(listOf(900L), repository.preConfirmRequests)
        assertEquals(listOf("verify:900:4:mySex=1"), dialogs.opened, "the contacts entry reports type 4")
    }

    @Test
    fun `confirming sends the application with no sens settings`() {
        repository.applyResponse = ApiResponse(200, "success", data = FriendApply(applyStatus = 0, friendId = 900L))
        launchToSheet()

        dialogs.pendingConfirm?.invoke(3)
        idle()

        assertEquals(listOf(Triple(900L, 3, null as Permission?)), repository.applications)
    }

    @Test
    fun `a stranger at their own limit is never sent a request`() {
        launchToSheet(Friend(userId = 900L, otherFriendLimit = true))
        dialogs.pendingConfirm?.invoke(2)
        idle()

        assertTrue(repository.applications.isEmpty())
        assertEquals(context.getString(R.string.ta_friend_has_reached_the_limit), toast())
    }

    @Test
    fun `the success toast comes before the consent sheet on this screen`() {
        flags.askPermissions = true
        repository.applyResponse = ApiResponse(200, "success", data = FriendApply(applyStatus = 0, friendId = 900L))
        launchToSheet()
        dialogs.pendingConfirm?.invoke(1)
        idle()

        assertEquals(
            context.getString(R.string.friend_application_sent_successfully),
            toast(),
            "both builds toast here first, unlike the add-friend sheet",
        )
        assertEquals(listOf("permissions:5"), dialogs.opened.filter { it.startsWith("permissions:") })
    }

    @Test
    fun `without the consent gate only the toast happens`() {
        flags.askPermissions = false
        repository.applyResponse = ApiResponse(200, "success", data = FriendApply(applyStatus = 0, friendId = 900L))
        launchToSheet()
        dialogs.pendingConfirm?.invoke(1)
        idle()

        assertTrue(dialogs.opened.none { it.startsWith("permissions:") })
        assertEquals(context.getString(R.string.friend_application_sent_successfully), toast())
    }

    @Test
    fun `already being friends skips the consent sheet entirely`() {
        flags.askPermissions = true
        repository.applyResponse = ApiResponse(200, "success", data = FriendApply(applyStatus = 1, friendId = 900L))
        val activity = launchToSheet()
        dialogs.pendingConfirm?.invoke(1)
        idle()

        assertEquals(context.getString(R.string.added_friend), toast())
        assertTrue(dialogs.opened.none { it.startsWith("permissions:") })
        assertEquals(
            listOf("add_type" to "手机通讯录", "relationship" to "恋人", "add_result" to "已是好友"),
            activity.addFriendEvent(1),
        )
    }

    @Test
    fun `the chosen consent set is handed to the privacy updater`() {
        flags.askPermissions = true
        dialogs.permissionChoice = Permission(sensTrack = true)
        repository.applyResponse = ApiResponse(200, "success", data = FriendApply(applyStatus = 0, friendId = 900L))

        val updates = mutableListOf<Pair<Permission, Long>>()
        val activity = launchToSheet()
        activity.permissionUpdater = { permission, friendId -> updates += permission to friendId }
        dialogs.pendingConfirm?.invoke(1)
        idle()

        assertEquals(1, updates.size)
        assertEquals(900L, updates.single().second)
        assertEquals(true, updates.single().first.sensTrack)
    }

    @Test
    fun `a rejected application shows the server message`() {
        repository.applyResponse = ApiResponse(500, "对方已关闭添加")
        launchToSheet()
        dialogs.pendingConfirm?.invoke(1)
        idle()

        assertEquals("对方已关闭添加", toast())
    }

    @Test
    fun `a lookup that fails says the code is invalid`() {
        repository.publicKey = pem(serverKeyPair().public)
        repository.contactResponse = ApiResponse(
            200,
            "success",
            data = SearchFriend(list = listOf(FriendInfo(userId = 900L, nickname = "小明"))),
        )
        repository.preConfirmResponse = ApiResponse(404, "gone")
        val activity = launch()

        measure(activity)
        rowAt(activity, 0).performClick()
        idle()

        assertEquals(context.getString(R.string.invalid_matching_code), toast())
    }

    @Test
    fun `back closes the sheet the way it opened`() {
        repository.publicKey = pem(serverKeyPair().public)
        val activity = launch()

        view<View>(activity, R.id.iv_back).performClick()
        idle()

        assertTrue(activity.isFinishing)
    }

}
