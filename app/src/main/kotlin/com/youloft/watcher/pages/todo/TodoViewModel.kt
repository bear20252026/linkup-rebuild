package com.youloft.watcher.pages.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.youloft.linkup.api.dto.TodoModel
import com.youloft.linkup.api.dto.TodoRsp
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.LinkUpApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 四象限待办屏的状态（DESIGN 五点四十二）：`api/four_quadrant_list` 三路由的装配层——
 * get_list 一次回答全部四组（listType 参数服务端忽略），edit 是"总量口径"的新增/更新
 * （id=0 新增、已知 id 原位更新），del 删除。每个写操作成功后重拉一次，屏上永远是台账的
 * 最新投影，不做乐观插行。
 *
 * 象限口径（艾森豪威尔，bean 字段名的顺序即 1..4）：1 重要紧急 / 2 重要不紧急 / 3 紧急不重要 /
 * 4 不紧急不重要。
 */
class TodoViewModel(private val api: LinkUpApi) : ViewModel() {

    data class TodoUiState(
        val rsp: TodoRsp? = null,
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val mutableState = MutableStateFlow(TodoUiState())
    val state: StateFlow<TodoUiState> = mutableState

    /** 进屏即拉一次全量。 */
    fun start() {
        load()
    }

    /** 新增一条到指定象限。 */
    fun add(content: String, listType: Int) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            mutableState.update { it.copy(error = ERROR_EMPTY) }
            return
        }
        if (listType !in 1..4) {
            mutableState.update { it.copy(error = ERROR_QUADRANT) }
            return
        }
        mutate { api.fourQuadrantListEdit(editPayload(id = 0L, listType = listType, content = trimmed)) }
    }

    /** 点击条目：完成状态原位切换（内容与象限不变）。 */
    fun toggle(entry: TodoModel) {
        mutate {
            api.fourQuadrantListEdit(
                editPayload(
                    id = entry.id,
                    listType = entry.listType,
                    content = entry.content.orEmpty(),
                    isFinish = !entry.isFinish,
                ),
            )
        }
    }

    fun delete(id: Long) {
        mutate { api.apiFourQuadrantListDel(mutableMapOf("id" to id)) }
    }

    /** 消费一次性错误（屏的 collect 里 toast 后调用），避免重复弹。 */
    fun consumeError() {
        mutableState.update { it.copy(error = null) }
    }

    private fun mutate(action: suspend () -> ApiResponse<*>) {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val response = action()
                if (!response.isSuccess) {
                    mutableState.update { it.copy(loading = false, error = response.msg ?: "操作失败") }
                    return@launch
                }
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, error = "网络错误：${error.message}") }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val response = runCatching { api.fourQuadrantListGetList(listType = 1) }.getOrNull()
            mutableState.update {
                if (response?.isSuccess == true) {
                    it.copy(rsp = response.data, loading = false, error = null)
                } else {
                    it.copy(loading = false, error = response?.msg?.takeIf { m -> m.isNotBlank() } ?: "加载失败")
                }
            }
        }
    }

    private fun editPayload(id: Long, listType: Int, content: String, isFinish: Boolean = false) =
        mutableMapOf<String, Any?>(
            "id" to id,
            "listType" to listType,
            "content" to content,
            "isFinish" to isFinish,
        )

    companion object {
        const val ERROR_EMPTY = "内容不能为空"
        const val ERROR_QUADRANT = "请选择一个象限"
        const val QUADRANT_COUNT = 4

        fun factory(api: LinkUpApi) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TodoViewModel(api) as T
        }
    }
}
