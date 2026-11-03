package com.youloft.watcher.pages.friend

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.youloft.linkup.api.dto.ConfirmAdd
import com.youloft.linkup.api.dto.FriendRequest
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.data.FriendRequestRepository
import kotlinx.coroutines.launch

/**
 * Recovered shape of `FriendRequestDetailViewModel` from 1.5.6. This screen used plain LiveData with
 * `postValue`, while the list screen used a `SharedFlow` — the difference is in the original, so it
 * is kept instead of being tidied into one shape.
 *
 * Unlike the list screen, the detail ViewModel publishes the response untouched: no `friendUserId`
 * backfill, because the detail screen never reads that field back.
 */
class FriendRequestDetailViewModel(
    private val repository: FriendRequestRepository,
) : ViewModel() {

    private val _confirmAdd = MutableLiveData<ApiResponse<ConfirmAdd>>()
    val confirmAdd: LiveData<ApiResponse<ConfirmAdd>> = _confirmAdd

    fun confirmAdd(friendRequest: FriendRequest, sensSetting: Permission? = null) {
        viewModelScope.launch {
            // A success also pushed the new friend into the IM layer (`manager.u.H`); that
            // subsystem is its own card and is not wired here yet.
            _confirmAdd.postValue(
                repository.confirmAdd(friendRequest.userId, friendRequest.relation, sensSetting),
            )
        }
    }

    companion object {
        fun factory(repository: FriendRequestRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FriendRequestDetailViewModel(repository) as T
        }
    }
}
