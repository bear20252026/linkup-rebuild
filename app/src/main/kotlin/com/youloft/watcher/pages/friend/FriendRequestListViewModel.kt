package com.youloft.watcher.pages.friend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.youloft.linkup.api.dto.ConfirmAdd
import com.youloft.linkup.api.dto.FriendRequest
import com.youloft.linkup.api.dto.Permission
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.data.FriendRequestRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Recovered shape of `FriendRequestListViewModel` from 1.5.6: one-shot confirm-add exposed as a
 * flow of responses. The server does not always return `friendUserId`, so the request's own id is
 * written into the response before it is emitted — the original did the same.
 */
class FriendRequestListViewModel(
    private val repository: FriendRequestRepository,
) : ViewModel() {

    private val _confirmAdd = MutableSharedFlow<ApiResponse<ConfirmAdd>>(extraBufferCapacity = 1)
    val confirmAdd: SharedFlow<ApiResponse<ConfirmAdd>> = _confirmAdd

    fun confirmAdd(friendRequest: FriendRequest, sensSetting: Permission? = null) {
        viewModelScope.launch {
            val response = repository.confirmAdd(friendRequest.userId, friendRequest.relation, sensSetting)
            // After a success the original also pushed the new friend into the IM layer
            // (`manager.u`); that subsystem is its own card and is not wired here yet.
            _confirmAdd.tryEmit(response.copy(data = response.data?.copy(friendUserId = friendRequest.userId)))
        }
    }

    companion object {
        fun factory(repository: FriendRequestRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FriendRequestListViewModel(repository) as T
        }
    }
}
