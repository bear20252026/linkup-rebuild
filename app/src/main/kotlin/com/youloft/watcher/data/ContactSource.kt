package com.youloft.watcher.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.text.TextUtils
import android.util.Patterns

/**
 * One row of the recovered `datasource/c.a`: a contact id is looked up once, then its first e-mail and
 * its first phone number are read. Note it is really *one* number per contact — the original never
 * iterates the phone rows past the first.
 */
data class ContactEntry(
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
)

/** Where the device's contacts come from. Production is the contacts provider; tests hand over a list. */
interface ContactSource {
    suspend fun entries(): List<ContactEntry>

    companion object {
        fun of(vararg entries: ContactEntry): ContactSource = object : ContactSource {
            override suspend fun entries(): List<ContactEntry> = entries.toList()
        }
    }
}

/**
 * `com.youloft.watcher.datasource.c` — the recovered query, reproduced clause by clause:
 *
 *  projection `_id, display_name, has_phone_number` · selection `display_name NOT LIKE '%@%'` ·
 *  order `display_name COLLATE NOCASE` · per contact: first e-mail row, then (only when
 *  `has_phone_number > 0`) the first phone row · the row is kept when the e-mail is a real address
 *  that differs from the display name, **or** when a phone number exists.
 */
class ResolverContactSource(private val context: Context) : ContactSource {

    override suspend fun entries(): List<ContactEntry> {
        val resolver = context.contentResolver
        val rows = ArrayList<ContactEntry>()
        query(
            resolver,
            ContactsContract.Contacts.CONTENT_URI,
            PROJECTION,
            SELECTION,
            null,
            SORT_ORDER,
        )?.use { contacts ->
            if (!contacts.moveToFirst()) return rows
            do {
                val contactId = contacts.getString(contacts.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val name = contacts.getString(contacts.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                val hasPhoneNumber = contacts.getInt(contacts.getColumnIndexOrThrow("has_phone_number"))

                val email = query(
                    resolver,
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    null,
                    "contact_id = ?",
                    arrayOf(contactId),
                    null,
                )?.firstString(EMAIL_COLUMN)

                val phone = if (hasPhoneNumber > 0) {
                    query(
                        resolver,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        "contact_id = ?",
                        arrayOf(contactId),
                        null,
                    )?.firstString(PHONE_COLUMN)
                } else {
                    null
                }

                if (keepEntry(name = name, email = email, phone = phone)) {
                    rows += ContactEntry(name = name, email = email, phone = phone)
                }
            } while (contacts.moveToNext())
        }
        return rows
    }

    /**
     * The recovered keep-rule, on its own so it can be asserted without a device's address book:
     * a phone number is enough on its own; otherwise the e-mail has to be a real address that is not
     * just the display name again.
     */
    internal fun keepEntry(name: String?, email: String?, phone: String?): Boolean {
        val emailIsReal = !TextUtils.isEmpty(email) &&
            Patterns.EMAIL_ADDRESS.matcher(email).matches() &&
            !email.equals(name, ignoreCase = true)
        return emailIsReal || !TextUtils.isEmpty(phone)
    }

    private fun query(
        resolver: android.content.ContentResolver,
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        args: Array<String>?,
        order: String?,
    ): Cursor? = runCatching { resolver.query(uri, projection, selection, args, order) }.getOrNull()

    /** `moveToFirst()` then one column, as the original did — it never walks past the first row. */
    private fun Cursor.firstString(column: String): String? = use { cursor ->
        if (!cursor.moveToFirst()) return null
        val index = cursor.getColumnIndex(column)
        if (index < 0) null else cursor.getString(index)
    }

    companion object {
        const val SELECTION = "display_name NOT LIKE '%@%'"
        const val SORT_ORDER = "display_name COLLATE NOCASE"
        const val EMAIL_COLUMN = "data1"
        const val PHONE_COLUMN = "data1"
        val PROJECTION = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            "has_phone_number",
        )
    }
}
