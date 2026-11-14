package com.youloft.watcher.pages.todo

import com.youloft.linkup.api.dto.TodoModel
import com.youloft.linkup.api.dto.TodoRsp
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.LinkUpApi
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 四象限待办的 VM 规则（DESIGN 五点四十二）：写成功后重拉全量（屏是台账的最新投影，无乐观插行）、
 * 空内容/未选象限的本地拒绝不发起请求、toggle 原位翻转。fake api 用一个真实台账顶在 Proxy 后面，
 * 让三条路由的语义一起转。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TodoViewModelTest {

    @Test
    fun `start loads the four quadrants from the ledger`() = runBlocking {
        val ledger = mutableListOf(
            TodoModel(id = 1L, listType = 1, content = "交作业"),
            TodoModel(id = 2L, listType = 4, content = "玩"),
        )
        val vm = TodoViewModel(fakeApi(ledger))
        vm.start()

        val rsp = vm.state.value.rsp
        assertEquals("交作业", rsp?.importantAndUrgent?.single()?.content)
        assertEquals("玩", rsp?.notImportantNotUrgent?.single()?.content)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `add posts an edit with a fresh id space and reloads`() = runBlocking {
        val ledger = mutableListOf(TodoModel(id = 1L, listType = 1, content = "交作业"))
        val vm = TodoViewModel(fakeApi(ledger))

        vm.add("背单词", listType = 3)

        assertEquals(2, ledger.size, "the edit landed on the ledger")
        assertEquals("背单词", ledger.last().content)
        assertEquals(3, ledger.last().listType)
        assertEquals("背单词", vm.state.value.rsp?.notImportantButUrgent?.single()?.content, "the reload carried the new row into its quadrant")
        assertNull(vm.state.value.error)
    }

    @Test
    fun `toggle flips the finish flag in place`() = runBlocking {
        val ledger = mutableListOf(TodoModel(id = 1L, listType = 1, content = "交作业", isFinish = false))
        val vm = TodoViewModel(fakeApi(ledger))
        vm.start()

        vm.toggle(ledger.single())

        assertTrue(ledger.single().isFinish, "the flip rode the edit payload")
        assertEquals(true, vm.state.value.rsp?.importantAndUrgent?.single()?.isFinish)
    }

    @Test
    fun `delete removes the row and the reload drops it`() = runBlocking {
        val ledger = mutableListOf(TodoModel(id = 1L, listType = 1, content = "交作业"))
        val vm = TodoViewModel(fakeApi(ledger))
        vm.start()

        vm.delete(1L)

        assertTrue(ledger.isEmpty(), "the del landed on the ledger")
        assertNull(vm.state.value.rsp?.importantAndUrgent, "an emptied quadrant comes back absent")
    }

    @Test
    fun `a blank content is refused locally without a call`() = runBlocking {
        val ledger = mutableListOf<TodoModel>()
        val vm = TodoViewModel(fakeApi(ledger))

        vm.add("   ", listType = 1)

        assertEquals(0, ledger.size, "the local validation fired before any request")
        assertEquals(TodoViewModel.ERROR_EMPTY, vm.state.value.error)
    }

    // ------------------------------------------------------------------ fixtures

    /** 台账顶在 Proxy 后面：三条路由都走真实语义（edit 的总量 replace、del 按 id）。 */
    private fun fakeApi(ledger: MutableList<TodoModel>): LinkUpApi =
        Proxy.newProxyInstance(
            LinkUpApi::class.java.classLoader,
            arrayOf(LinkUpApi::class.java),
        ) { _, method, args ->
            when (method.name) {
                "fourQuadrantListGetList" -> {
                    val byType = ledger.groupBy { it.listType }
                    ApiResponse(
                        200, "success",
                        data = TodoRsp(
                            importantAndUrgent = byType[1]?.map { it },
                            importantButNutAndUrgent = byType[2]?.map { it },
                            notImportantButUrgent = byType[3]?.map { it },
                            notImportantNotUrgent = byType[4]?.map { it },
                        ),
                    )
                }
                "fourQuadrantListEdit" -> {
                    val payload = args[0] as Map<*, *>
                    val id = (payload["id"] as? Long) ?: 0L
                    val listType = (payload["listType"] as? Int) ?: 0
                    val content = payload["content"] as? String ?: ""
                    val isFinish = payload["isFinish"] as? Boolean ?: false
                    val existing = ledger.firstOrNull { it.id == id }
                    if (existing == null) {
                        ledger += TodoModel(
                            id = ledger.maxOfOrNull { it.id }?.plus(1) ?: 1L,
                            listType = listType,
                            content = content,
                        )
                    } else {
                        val at = ledger.indexOf(existing)
                        ledger[at] = existing.copy(listType = listType, content = content, isFinish = isFinish)
                    }
                    ApiResponse<Unit>(200, "success")
                }
                "apiFourQuadrantListDel" -> {
                    val payload = args[0] as Map<*, *>
                    val id = (payload["id"] as? Long) ?: 0L
                    ledger.removeAll { it.id == id }
                    ApiResponse<Unit>(200, "success")
                }
                else -> error("unexpected call: " + method.name)
            }
        } as LinkUpApi
}
