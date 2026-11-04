package com.youloft.watcher.pages.friend

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.youloft.linkup.api.dto.Friend
import com.youloft.linkup.api.dto.FriendApply
import com.youloft.linkup.api.dto.FriendInfo
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.api.dto.SearchFriend
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.R
import com.youloft.watcher.adapter.ContactInfoAdapter
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.ActivityPhoneContactInfoBinding
import com.youloft.watcher.ext.onIntervalClick
import com.youloft.watcher.pages.friend.dialog.VerifyInfoDialog

/**
 * Rebuilt from `ContactInfoListActivity` — the "从通讯录添加" sheet. Both versions are line-for-line the
 * same except the consent gate (`report/21` §六), so this is one of the few screens where 1.5.6 and
 * 1.6.6 agree.
 *
 * Two things worth keeping in mind when reading it:
 *  - it reuses [AddFriendViewModel] rather than owning one, exactly as the original did — the three
 *    channels it watches (`search`, `preConfirm`, `apply`) are the same ones the add-friend screen uses;
 *  - its list is **not** the address book. The phone numbers only go to the server; what comes back is
 *    the set of contacts that have LinkUp accounts.
 */
class ContactInfoListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhoneContactInfoBinding

    private val viewModel: AddFriendViewModel by viewModels {
        AddFriendViewModel.factory(appGraph(this).friends, appGraph(this).qrRenderer)
    }

    private val contacts by lazy { ContactInfoAdapter() }

    var dialogs: AddFriendDialogs = RealAddFriendDialogs(this)

    var navigation: FriendFlowNavigation = DefaultFriendFlowNavigation(this)

    var overlay: LoadingOverlay = ActivityLoadingOverlay(this)

    /** Same as the add-friend screen: the chosen consent set belongs to the privacy card. */
    var permissionUpdater: (Permission, Long) -> Unit = { _, _ -> }

    private var pickedRelation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhoneContactInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        overridePendingTransition(R.anim.activity_bottom_in, R.anim.activity_bottom_silent)

        // The original showed the sheet *before* reading the address book, which can take a while.
        overlay.showLoading()
        viewModel.searchDeviceContacts(appGraph(this).contacts, keyId = appGraph(this).accountCache.userId())

        contacts.onAddFriend = { info -> onRowClick(info) }
        binding.ivBack.onIntervalClick { finish() }
        binding.rlContactList.apply {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(context)
            adapter = contacts
        }

        viewModel.search.observe(this) { onSearch(it) }
        viewModel.preConfirm.observe(this) { onPreConfirm(it) }
        viewModel.apply.observe(this) { onApply(it) }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_bottom_silent, R.anim.activity_bottom_out)
    }

    /** `m0()` — a failed lookup always shows the empty state, whether or not it also toasted. */
    internal fun onSearch(response: ApiResponse<SearchFriend>) {
        overlay.hideLoading()
        if (response.isSuccess) {
            val found = response.data ?: return
            val matched = found.list
            if (matched == null || matched.isEmpty()) {
                showEmpty()
            } else {
                binding.llEmpty.visibility = View.GONE
                binding.rlContactList.visibility = View.VISIBLE
                binding.rlContactList.adapter = contacts
                // A null element in the server's list would render a blank row; it is dropped instead.
                contacts.submitList(matched.filterNotNull())
                contacts.notifyDataSetChanged()
            }
            return
        }
        if (response.status != AddFriendViewModel.NO_SERVER_KEY) toastText(response.msg.orEmpty())
        showEmpty()
    }

    /** `n0()` — the sheet is opened with type 4, which is what the contacts entry reported. */
    internal fun onPreConfirm(response: ApiResponse<Friend>) {
        overlay.hideLoading()
        val friend = response.data
        if (response.isSuccess && friend != null) {
            showVerify(friend)
            return
        }
        if (response.status != AddFriendViewModel.NO_SERVER_KEY) toastString(R.string.invalid_matching_code)
    }

    /** `p0()` — note the order differs from the add-friend screen: the toast comes *before* the sheet. */
    internal fun onApply(response: ApiResponse<FriendApply>) {
        overlay.hideLoading()
        val applied = response.data
        if (!response.isSuccess || applied == null) {
            toastText(response.msg.orEmpty())
            return
        }
        if (applied.applyStatus == APPLY_STATUS_ALREADY_FRIEND) {
            toastString(R.string.added_friend)
        } else {
            toastString(R.string.friend_application_sent_successfully)
            if (appGraph(this).askPermissionsAfterAdd) {
                dialogs.permissionSettings(
                    source = PERMISSION_SOURCE_CONTACTS,
                    onResult = { permission -> if (permission != null) permissionUpdater(permission, applied.friendId) },
                )
            }
        }
    }

    internal fun onRowClick(info: FriendInfo) {
        overlay.showLoading()
        viewModel.preConfirmAdd(info.userId)
    }

    private fun showVerify(friend: Friend) {
        dialogs.verifyInfo(
            friend = friend,
            type = VerifyInfoDialog.SOURCE_CONTACTS,
            mySex = appGraph(this).accountCache.user().sex,
            onVipLabelClick = { vipSource, label -> navigation.openVip(vipSource, label) },
            onConfirm = { relation, _ ->
                pickedRelation = relation
                if (friend.otherFriendLimit) {
                    toastString(R.string.ta_friend_has_reached_the_limit)
                    return@verifyInfo
                }
                overlay.showLoading()
                viewModel.applyAdd(friend, relation, sensSetting = null)
            },
        )
    }

    /** The event the original reported from this screen: `add_type` is fixed to the contacts entry. */
    internal fun addFriendEvent(applyStatus: Int): List<Pair<String, String>> = listOf(
        "add_type" to VerifyInfoDialog.sourceLabel(VerifyInfoDialog.SOURCE_CONTACTS),
        "relationship" to AddFriendActivity.relationLabel(pickedRelation),
        "add_result" to if (applyStatus == APPLY_STATUS_ALREADY_FRIEND) "已是好友" else "申请成功",
    )

    private fun showEmpty() {
        binding.llEmpty.visibility = View.VISIBLE
        binding.rlContactList.visibility = View.GONE
    }

    private fun toastString(resId: Int) = toastText(getString(resId))

    private fun toastText(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val APPLY_STATUS_ALREADY_FRIEND = 1

        /** The `source` this screen passed to the consent sheet — it reports as 手机通讯录. */
        const val PERMISSION_SOURCE_CONTACTS = 5

        fun start(context: Context) {
            context.startActivity(Intent(context, ContactInfoListActivity::class.java))
        }
    }
}
