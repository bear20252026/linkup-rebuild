package com.youloft.watcher.pages.friend

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.youloft.linkup.api.dto.Friend
import com.youloft.linkup.api.dto.FriendApply
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.api.dto.SearchFriend
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.ContactSearchEnvelope
import com.youloft.watcher.data.FriendRepository
import com.youloft.watcher.view.QrCodeRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recovered from `pages.friend.viewmodel.AddFriendViewModel`. Five result channels, one per action,
 * all posted (not set) because the original ran every request off the main thread.
 *
 * The `saved` channel and `saveBitmap()` of the original are left out: their only trigger was the
 * download button under the QR card, and that button is gone from the 1.6.6 layout.
 */
class AddFriendViewModel(
    private val repository: FriendRepository,
    private val envelope: ContactSearchEnvelope = ContactSearchEnvelope(),
    private val qrRenderer: QrCodeRenderer = QrCodeRenderer.Pending,
) : ViewModel() {

    private val _preConfirm = MutableLiveData<ApiResponse<Friend>>()
    val preConfirm: LiveData<ApiResponse<Friend>> = _preConfirm

    private val _apply = MutableLiveData<ApiResponse<FriendApply>>()
    val apply: LiveData<ApiResponse<FriendApply>> = _apply

    private val _search = MutableLiveData<ApiResponse<SearchFriend>>()
    val search: LiveData<ApiResponse<SearchFriend>> = _search

    private val _qrCode = MutableLiveData<Bitmap>()
    val qrCode: LiveData<Bitmap> = _qrCode

    /** `r(code)` — a match code, a scanned userid or the H5 deep link all funnel through here. */
    fun preConfirmAdd(code: Long) {
        viewModelScope.launch { _preConfirm.postValue(repository.preConfirm(code)) }
    }

    fun applyAdd(friend: Friend, relation: Int, sensSetting: Permission? = null) {
        viewModelScope.launch { _apply.postValue(repository.applyAdd(friend.userId, relation, sensSetting)) }
    }

    /**
     * `t(phoneList)` — recovered branch order matters: the envelope is sealed first, and only then
     * does the list decide whether this is a match-code lookup (8 or 6 digits, one entry) or a real
     * contact-match upload.
     */
    fun searchPhones(phones: List<String>, keyId: Long) {
        viewModelScope.launch {
            val serverKey = repository.serverPublicKey()
            val payload = envelope.build(phones, keyId, serverKey.data?.publicKey)
            if (payload == null) {
                // The original logged "#searchFriendInfo->getPublicKey() is empty" and answered itself
                // with status 10086 plus a flag-only body, which the screen reads as "no key, don't
                // toast 'invalid code'".
                if (phones.size == 1 && phones[0].length == MATCH_CODE_LENGTH) {
                    _preConfirm.postValue(ApiResponse(NO_SERVER_KEY, data = Friend(sslCertificateError = true)))
                } else {
                    _search.postValue(ApiResponse(NO_SERVER_KEY, data = SearchFriend(sslCertificateError = true)))
                }
                return@launch
            }
            val single = phones.size == 1 && (phones[0].length == MATCH_CODE_LENGTH || phones[0].length == SHORT_CODE_LENGTH)
            if (single) {
                _preConfirm.postValue(repository.preConfirm(phones[0].toLong()))
            } else {
                _search.postValue(repository.matchByPhone(payload.encrypted, payload.keyEncrypted))
            }
        }
    }

    /** `o(shareUrl)` — the QR is drawn off the main thread, as in the original. */
    fun matchQrCode(shareUrl: String, sizePx: Int) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { qrRenderer.render(shareUrl, sizePx) }
            if (bitmap != null) _qrCode.postValue(bitmap)
        }
    }

    companion object {
        const val MATCH_CODE_LENGTH = 8
        const val SHORT_CODE_LENGTH = 6
        const val NO_SERVER_KEY = 10086

        fun factory(
            repository: FriendRepository,
            qrRenderer: QrCodeRenderer = QrCodeRenderer.Pending,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AddFriendViewModel(repository, ContactSearchEnvelope(), qrRenderer) as T
        }
    }
}
