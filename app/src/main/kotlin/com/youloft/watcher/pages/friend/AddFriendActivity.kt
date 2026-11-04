package com.youloft.watcher.pages.friend

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.youloft.linkup.api.dto.Friend
import com.youloft.linkup.api.dto.FriendApply
import com.youloft.linkup.api.dto.FriendInfo
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.api.dto.SearchFriend
import com.youloft.linkup.api.dto.User
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.data.local.AddFriendTips
import com.youloft.watcher.databinding.ActivityAddFriendBinding
import com.youloft.watcher.ext.onIntervalClick
import java.io.ByteArrayOutputStream

/**
 * Rebuilt from the 1.6.6 `AddFriendActivity`. It is the entry screen of the friend flow: every
 * "add somebody" affordance in the app lands here with its own `source`, and the screen can find a
 * person four ways — a typed match code, a typed phone number, a scanned QR, or an H5 deep link.
 *
 * Contacts, the camera scanner and the WeChat/QQ shares are seams: their screens and SDKs are separate
 * cards, so this class keeps the recovered decision tree around them and delegates the action.
 */
class AddFriendActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddFriendBinding

    private val viewModel: AddFriendViewModel by viewModels {
        AddFriendViewModel.factory(appGraph(this).friends, appGraph(this).qrRenderer)
    }

    var navigation: FriendFlowNavigation = DefaultFriendFlowNavigation(this)

    var dialogs: AddFriendDialogs = RealAddFriendDialogs(this)

    var platform: AddFriendPlatform = RealAddFriendPlatform(this)

    /** The consent set picked after applying goes to the privacy repository — its own card. */
    var permissionUpdater: (Permission, Long) -> Unit = { _, _ -> }

    private var source = 0

    private var dialogSource = 0

    private var pickedRelation = 0

    /** What the `add_friend` event reports as `add_type`; the recovered field default, not a guess. */
    private var addType = "扫一扫"

    /** The text the user searched for, or null when the lookup came from the QR / H5 paths. */
    private var lastSearch: String? = null

    private var inputFocused = false

    private var myUserId = 0L

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) navigation.openQrScanner()
    }

    private val contactsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            navigation.openContactList()
            return@registerForActivityResult
        }
        toast(getString(R.string.contact_info_read_permission_tip))
        if (!shouldShowRequestPermissionRationale(android.Manifest.permission.READ_CONTACTS)) {
            // Denied and "don't ask again": the original also took the user to this app's system page.
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null)),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddFriendBinding.inflate(layoutInflater)
        setContentView(binding.root)
        overridePendingTransition(R.anim.activity_bottom_in, R.anim.activity_bottom_silent)

        source = intent.getIntExtra(EXTRA_SOURCE, 0)
        if (source == SOURCE_H5 && intent.hasExtra(EXTRA_MATCH_CODE_FROM_H5)) {
            val code = intent.getLongExtra(EXTRA_MATCH_CODE_FROM_H5, 0L)
            if (code != 0L) {
                platform.showLoading()
                viewModel.preConfirmAdd(code)
                dialogSource = DIALOG_SOURCE_H5
            } else {
                toast(getString(R.string.please_enter_the_correct_matching_code))
            }
        }

        val account = appGraph(this).accountCache.user()
        myUserId = account.userId
        val userIdText = myUserId.toString()
        binding.tvMatchCode.text = userIdText.chunked(MATCH_CODE_CHUNK).joinToString(" ")
        renderTips()

        binding.ivClose.onIntervalClick { finish() }
        binding.tvSearchFriend.addTextChangedListener {
            val filled = trimmedInput().isNotEmpty()
            binding.ivSearchClear.visibility = if (filled) View.VISIBLE else View.GONE
            binding.tvSearchAction.visibility = if (filled) View.VISIBLE else View.GONE
        }
        binding.ivSearchClear.onIntervalClick { binding.tvSearchFriend.text?.clear() }
        binding.tvSearchAction.onIntervalClick { searchNow() }
        binding.tvSearchFriend.setOnEditorActionListener { _, actionId, _ -> onEditorAction(actionId) }
        binding.tvSearchFriend.setOnFocusChangeListener { _, hasFocus -> onFocusChanged(hasFocus) }
        binding.viewInputMask.onIntervalClick { dismissKeyboard() }
        platform.watchKeyboard { visible, _ -> onKeyboardChanged(visible) }

        binding.sclContact.onIntervalClick { requestContacts() }
        binding.tvMatchCode.onIntervalClick { copyMatchCode(userIdText) }
        binding.sclWechat.onIntervalClick { shareToWechat(account) }
        binding.sclQq.onIntervalClick { shareToQq() }
        binding.tvScanAdd.onIntervalClick { requestScan() }

        viewModel.preConfirm.observe(this) { onPreConfirm(it) }
        viewModel.search.observe(this) { onSearch(it) }
        viewModel.apply.observe(this) { onApply(it) }
        viewModel.qrCode.observe(this) { onQrCode(it) }

        viewModel.matchQrCode(platform.qrShareUrl(myUserId), qrSizePx())
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_bottom_silent, R.anim.activity_bottom_out)
    }

    /** `f1()` — one typed string is both a possible match code and a possible phone number. */
    internal fun searchNow() {
        val text = trimmedInput()
        if (text.isEmpty()) return
        lastSearch = text
        val isMatchCode = text.length == AddFriendViewModel.MATCH_CODE_LENGTH
        addType = if (isMatchCode) "搜索匹配码" else "搜索手机号"
        dialogSource = if (isMatchCode) DIALOG_SOURCE_MATCH_CODE else DIALOG_SOURCE_PHONE
        platform.showLoading()
        viewModel.searchPhones(listOf(text), keyId = myUserId)
    }

    internal fun trimmedInput(): String = binding.tvSearchFriend.text?.toString()?.trim().orEmpty()

    /** `W0()` — the search key on the IME is the only action this box answers, and it answers it always. */
    internal fun onEditorAction(actionId: Int): Boolean {
        if (actionId != EditorInfo.IME_ACTION_SEARCH) return false
        if (trimmedInput().isNotEmpty()) searchNow() else toast(getString(R.string.please_enter_the_correct_matching_code))
        return true
    }

    /** `d1()` — a failed lookup puts the caret back in the box instead of leaving a dead screen. */
    internal fun resetSearchInput() {
        binding.tvSearchFriend.text?.clear()
        binding.tvSearchFriend.requestFocus()
    }

    internal fun onPreConfirm(response: ApiResponse<Friend>) {
        platform.hideLoading()
        val friend = response.data
        if (response.isSuccess && friend != null) {
            showVerify(friend, verifyType())
            return
        }
        if (response.status != AddFriendViewModel.NO_SERVER_KEY) toast(getString(R.string.invalid_matching_code))
        if (lastSearch != null) resetSearchInput()
    }

    internal fun onSearch(response: ApiResponse<SearchFriend>) {
        platform.hideLoading()
        val found = response.data
        val info = found?.list?.firstOrNull()
        if (response.isSuccess && info != null && found != null) {
            showVerify(foundFrom(info, found.isFriendLimit), verifyType())
            return
        }
        if (response.status != AddFriendViewModel.NO_SERVER_KEY) toast(getString(R.string.invalid_matching_code))
        resetSearchInput()
    }

    internal fun onApply(response: ApiResponse<FriendApply>) {
        platform.hideLoading()
        val applied = response.data
        if (!response.isSuccess || applied == null) {
            toast(response.msg.orEmpty())
            return
        }
        if (applied.applyStatus == APPLY_STATUS_ALREADY_FRIEND) {
            // The original also bumped the IM tab badge here; that is the IM card's work.
            toast(getString(R.string.added_friend))
        }
        if (appGraph(this).askPermissionsAfterAdd) {
            dialogs.permissionSettings(
                source = dialogSource,
                onResult = { permission -> if (permission != null) permissionUpdater(permission, applied.friendId) },
                onDismissed = { announceApplication(applied) },
            )
        } else {
            announceApplication(applied)
        }
    }

    private fun announceApplication(applied: FriendApply) {
        if (applied.applyStatus == APPLY_STATUS_ALREADY_FRIEND || isFinishing || isDestroyed) return
        toast(getString(R.string.friend_application_sent_successfully))
    }

    /** `e1()` — eight characters is a match code; anything else has to carry a `userid=` fragment. */
    internal fun onQrResult(raw: String?) {
        lastSearch = null
        addType = "扫一扫"
        val scanned = raw
            ?.let { Uri.parse(it).fragment }
            ?.substringAfter("userid=")
            ?.substringBefore("&")
            .orEmpty()
        if (raw != null && raw.length == AddFriendViewModel.MATCH_CODE_LENGTH) {
            platform.showLoading()
            viewModel.preConfirmAdd(raw.toLongOrNull() ?: 0L)
            dialogSource = DIALOG_SOURCE_SCAN
            return
        }
        if (scanned.isEmpty() || !scanned.all(Char::isDigit)) {
            toast(getString(R.string.invalid_qr_code))
            return
        }
        platform.showLoading()
        viewModel.preConfirmAdd(scanned.toLongOrNull() ?: 0L)
        dialogSource = DIALOG_SOURCE_SCAN
    }

    internal fun onKeyboardChanged(visible: Boolean) {
        if (!visible) binding.tvSearchFriend.clearFocus()
        updateInputMask(visible)
    }

    internal fun onFocusChanged(hasFocus: Boolean) {
        inputFocused = hasFocus
        updateInputMask(platform.isKeyboardShown())
    }

    internal fun dismissKeyboard() {
        platform.hideKeyboard(binding.tvSearchFriend)
        binding.tvSearchFriend.clearFocus()
        updateInputMask(false)
    }

    private fun updateInputMask(keyboardVisible: Boolean) {
        binding.viewInputMask.visibility = if (inputFocused && keyboardVisible) View.VISIBLE else View.GONE
    }

    /**
     * Which entry opened the verify sheet: 5 the H5 link, 2 an eight-character match code, 3 a phone
     * number, 1 a QR scan (the only path that clears the search text before asking).
     */
    internal fun verifyType(): Int = when {
        source == SOURCE_H5 -> 5
        lastSearch == null -> 1
        lastSearch?.length == AddFriendViewModel.MATCH_CODE_LENGTH -> 2
        else -> 3
    }

    private fun requestScan() {
        lastSearch = null
        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    private fun requestContacts() {
        contactsPermissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
    }

    private fun showVerify(friend: Friend, type: Int) {
        dialogs.verifyInfo(
            friend = friend,
            type = type,
            mySex = appGraph(this).accountCache.user().sex,
            onVipLabelClick = { vipSource, label -> navigation.openVip(vipSource, label) },
            // `dismissParent` is unused here: the sheet closes itself once it has reported the choice.
            onConfirm = { relation, _ -> onRelationPicked(friend, relation) },
        )
    }

    private fun onRelationPicked(friend: Friend, relation: Int) {
        pickedRelation = relation
        if (friend.otherFriendLimit) {
            toast(getString(R.string.ta_friend_has_reached_the_limit))
            return
        }
        platform.showLoading()
        viewModel.applyAdd(friend, relation, sensSetting = null)
    }

    private fun copyMatchCode(userIdText: String) {
        platform.copy(userIdText)
        toast(getString(R.string.copy_success))
    }

    private fun shareToWechat(account: User) {
        if (!platform.isWechatInstalled()) {
            toast(getString(R.string.please_install_wx_first))
            return
        }
        platform.shareToWechat(
            platform.inviteUrl(account.userId, account.avatar, account.nickname),
            getString(R.string.add_friend_share_url_title),
            getString(R.string.add_friend_share_url_content),
        )
    }

    private fun shareToQq() {
        if (!platform.isQqInstalled()) {
            toast(getString(R.string.please_install_qq_first))
            return
        }
        val account = appGraph(this).accountCache.user()
        platform.shareToQq(
            platform.inviteUrl(account.userId, account.avatar, account.nickname),
            getString(R.string.add_friend_share_url_title),
            getString(R.string.add_friend_share_url_content),
        )
    }

    private fun onQrCode(bitmap: Bitmap) {
        binding.ivCode.setImageBitmap(bitmap)
        platform.uploadQrImage(bitmap.pngBytes(), QR_FILE_NAME)
    }

    private fun renderTips() {
        val tips = appGraph(this).addFriendTips ?: return
        binding.tvTips.visibility = View.VISIBLE
        binding.tvTips.text = when (tips) {
            // Two of the three wordings have no string resource — they are literals in the original.
            AddFriendTips.LocationMutualConsent -> "定位轨迹等共享功能均需双方相互授权同意才可使用"
            AddFriendTips.BothInstallOrVip -> getString(R.string.add_friend_bottom_tip)
            AddFriendTips.BothInstallApp -> "需用户双方安装APP并授权才可实现定位功能"
        }
    }

    internal fun analyticsLabel(): String = analyticsLabel(source)

    /**
     * The `add_friend` event the original sent through `mf.b`: `add_type` is how the person was found,
     * `add_result` whether they were already a friend. The analytics sink is its own card, so the
     * shape is built and pinned by tests but not dispatched yet.
     */
    internal fun addFriendEvent(applyStatus: Int): List<Pair<String, String>> = listOf(
        "add_type" to addType,
        "relationship" to relationLabel(pickedRelation),
        "add_result" to if (applyStatus == APPLY_STATUS_ALREADY_FRIEND) "已是好友" else "申请成功",
    )

    /** What the screen currently reports as `add_type`; readable so tests can pin the branch order. */
    internal fun addTypeLabel(): String = addType

    private fun qrSizePx(): Int = (QR_SIZE_DP * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_MATCH_CODE_FROM_H5 = "match_code_from_h5"

        /** `QRCodeScanActivity.f64728i` — the key the scanner puts its payload under. */
        const val EXTRA_SCAN_RESULT = "SCAN_RESULT"
        const val SOURCE_H5 = 7
        const val DIALOG_SOURCE_SCAN = 2
        const val DIALOG_SOURCE_MATCH_CODE = 3
        const val DIALOG_SOURCE_PHONE = 4
        const val DIALOG_SOURCE_H5 = 6
        const val APPLY_STATUS_ALREADY_FRIEND = 1
        const val MATCH_CODE_CHUNK = 4
        const val QR_FILE_NAME = "match_qr_code.png"
        private const val QR_SIZE_DP = 115f

        fun start(context: Context, source: Int, matchCodeFromH5: Long = 0L) {
            val intent = Intent(context, AddFriendActivity::class.java)
            intent.putExtra(EXTRA_SOURCE, source)
            intent.putExtra(EXTRA_MATCH_CODE_FROM_H5, matchCodeFromH5)
            context.startActivity(intent)
        }

        /** `x0(source)` of the original — the label each of the 21 entry points reports. */
        internal fun analyticsLabel(source: Int): String = when (source) {
            1 -> "守护Tab-好友切换弹窗"
            2 -> "守护Tab-添加好友banner"
            3 -> "守护Tab-体验好友时卡片点击"
            4 -> "守护Tab-右上角添加好友"
            5 -> "我的Tab"
            6 -> "我的好友"
            7 -> "H5"
            8 -> "好友申请"
            9 -> "一键锁机"
            10 -> "实时语音"
            11 -> "守护Tab-新用户体验模式弹窗立即添加按钮"
            12 -> "守护Tab右上角会员入口"
            13 -> "我的Tab会员入口"
            14 -> "小组件"
            15 -> "应用锁"
            16 -> "水印拍照-发送给好友"
            17 -> "添加好友推送"
            18 -> "卫星地图"
            19 -> "显示路况"
            20 -> "实时截屏"
            21 -> "隐私设置"
            else -> "其他"
        }

        /** `R0()` / `a.c()` — the relation name the analytics event carried. */
        internal fun relationLabel(relation: Int): String = when (relation) {
            1 -> "恋人"
            2 -> "挚友"
            3 -> "亲人"
            else -> "其他"
        }

        /** `FriendInfo.getName()`: the remark the user wrote wins over the stranger's own nickname. */
        internal fun displayName(info: FriendInfo): String =
            info.remark?.takeIf { it.isNotEmpty() } ?: info.nickname.orEmpty()

        internal fun foundFrom(info: FriendInfo, searchFriendLimit: Boolean): Friend = Friend(
            nickname = displayName(info),
            sex = info.sex,
            avatar = info.avatar,
            userId = info.userId,
            // The quota flag comes from the search result, not from the person record.
            isFriendLimit = searchFriendLimit,
            otherFriendLimit = info.otherFriendLimit,
            permission = info.permission,
        )

        /** `ext.d.x(bitmap)` — the original compressed to PNG at quality 100. */
        internal fun Bitmap.pngBytes(): ByteArray {
            val out = ByteArrayOutputStream()
            compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        }
    }
}
