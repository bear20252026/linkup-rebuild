package com.youloft.watcher.pages.friend

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.youloft.linkup.api.dto.FriendInfo
import com.youloft.linkup.net.ApiResponse
import com.youloft.watcher.data.FriendRepository
import kotlinx.coroutines.launch

/**
 * Recovered shape of `FriendInfoViewModel`: four result channels, one per action. The relation and
 * remark channels do not carry the server payload — the original echoes the value it just sent, so
 * the screen can repaint without refetching. That is reproduced here.
 */
class FriendInfoViewModel(
    private val repository: FriendRepository,
) : ViewModel() {

    private val _friendInfo = MutableLiveData<ApiResponse<FriendInfo>>()
    val friendInfo: LiveData<ApiResponse<FriendInfo>> = _friendInfo

    private val _deleted = MutableLiveData<ApiResponse<Unit>>()
    val deleted: LiveData<ApiResponse<Unit>> = _deleted

    private val _relation = MutableLiveData<ApiResponse<Int>>()
    val relation: LiveData<ApiResponse<Int>> = _relation

    private val _remark = MutableLiveData<ApiResponse<String>>()
    val remark: LiveData<ApiResponse<String>> = _remark

    fun loadFriendInfo(userId: Long) {
        viewModelScope.launch { _friendInfo.postValue(repository.friendInfo(userId)) }
    }

    fun deleteFriend(userId: Long) {
        viewModelScope.launch { _deleted.postValue(repository.deleteFriend(userId)) }
    }

    fun updateRelation(userId: Long, relation: Int) {
        viewModelScope.launch {
            val response = repository.updateRelation(userId, relation)
            _relation.postValue(ApiResponse(response.status, response.msg, data = relation))
        }
    }

    fun updateRemark(userId: Long, remark: String) {
        viewModelScope.launch {
            val response = repository.updateRemark(userId, remark)
            // A success also refreshed the IM chat state (`manager.u.M` plus the conversation list);
            // the IM layer is its own card and is not wired here yet.
            _remark.postValue(ApiResponse(response.status, response.msg, data = remark))
        }
    }

    companion object {
        fun factory(repository: FriendRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FriendInfoViewModel(repository) as T
        }
    }
}
