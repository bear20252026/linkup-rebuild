package com.youloft.watcher.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.youloft.watcher.pages.friend.AddFriendViewModel
import com.youloft.watcher.pages.friend.FriendFlowFixtures
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The address-book layer has two rules that are easy to get wrong and invisible until a device is
 * involved: which contacts are kept at all, and how a number is spelled before it goes to the server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FriendFlowFixtures.TestApplication::class)
class ContactSourceTest {

    private lateinit var reader: ResolverContactSource

    @Before
    fun setUp() {
        FriendFlowFixtures.reset()
        reader = ResolverContactSource(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun `the recovered query clauses are the ones we send`() {
        assertEquals("display_name NOT LIKE '%@%'", ResolverContactSource.SELECTION)
        assertEquals("display_name COLLATE NOCASE", ResolverContactSource.SORT_ORDER)
        assertEquals(3, ResolverContactSource.PROJECTION.size)
        assertEquals("_id", ResolverContactSource.PROJECTION[0])
        assertEquals("display_name", ResolverContactSource.PROJECTION[1])
        assertEquals("has_phone_number", ResolverContactSource.PROJECTION[2])
    }

    @Test
    fun `a phone number keeps a contact on its own`() {
        assertTrue(reader.keepEntry(name = "张三", email = null, phone = "13800138000"))
    }

    @Test
    fun `a real e-mail keeps a contact that has no number`() {
        assertTrue(reader.keepEntry(name = "张三", email = "z@example.com", phone = null))
    }

    @Test
    fun `an e-mail that is just the display name again does not count`() {
        assertFalse(reader.keepEntry(name = "z@example.com", email = "Z@EXAMPLE.COM", phone = null))
    }

    @Test
    fun `junk in the e-mail column is not an address`() {
        assertFalse(reader.keepEntry(name = "张三", email = "not-an-address", phone = null))
        assertFalse(reader.keepEntry(name = "张三", email = null, phone = null))
    }

    @Test
    fun `the number cleanup is the recovered regex plus distinct`() {
        val viewModel = AddFriendViewModel(FriendFlowFixtures.repository)

        assertEquals(
            listOf("13800138000", "110", ""),
            viewModel.normalizePhoneNumbers(
                listOf(
                    "+86 138 0013 8000",
                    "  110  ",
                    "+1 110",
                    null,
                ),
            ),
            "a contact without a number becomes an empty string, and distinct keeps exactly one of those",
        )
    }
}
