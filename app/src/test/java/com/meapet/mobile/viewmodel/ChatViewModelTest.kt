package com.meapet.mobile.viewmodel

import android.app.Application
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.chat.ChatService
import com.meapet.mobile.app.AppContainer
import com.meapet.mobile.app.MeaPetApplication
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryManager
import com.meapet.mobile.memory.MemoryStats
import com.meapet.mobile.tts.TtsManager
import com.meapet.mobile.update.UpdateCheckResult
import com.meapet.mobile.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking

/**
 * [ChatViewModel] 交互测试。
 *
 * 通过 mockStatic 注入 `MeaPetApplication.from` → 假 [AppContainer]，验证
 * UI 事件 → 服务调用 → 状态更新的分发链路。测试协程与 ViewModel 共享
 * [dispatcher]（Main dispatcher），用 `advance()` 推进。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    private lateinit var application: MeaPetApplication
    private lateinit var container: AppContainer
    private lateinit var chatService: ChatService
    private lateinit var memoryManager: MemoryManager
    private lateinit var updateChecker: UpdateChecker
    private lateinit var ttsManager: TtsManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = mock()
        container = mock()
        chatService = mock()
        memoryManager = mock()
        updateChecker = mock()
        ttsManager = mock()
        // AppContainer 属性为 by lazy，whenever(getter) 会拿到 null；用 doReturn 直接设定
        Mockito.doReturn(chatService).`when`(container).chatService
        Mockito.doReturn(memoryManager).`when`(container).memoryManager
        Mockito.doReturn(updateChecker).`when`(container).updateChecker
        Mockito.doReturn(ttsManager).`when`(container).ttsManager
        Mockito.doReturn(Job().apply { complete() }).`when`(container).warmUpJob
        wheneverBlocking { updateChecker.check() }.thenReturn(UpdateCheckResult.UpToDate("1.0.0"))
        wheneverBlocking { memoryManager.getStats() }.thenReturn(MemoryStats())
        wheneverBlocking { memoryManager.getAllMemories() }.thenReturn(emptyList())

        // from(application) 真实执行：application 是 MeaPetApplication 的 mock，container 经 doReturn 注入
        Mockito.doReturn(container).`when`(application).container
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 推进 ViewModel 的主线程协程（共享 scheduler）。 */
    private fun ChatViewModel.advance() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun reply(user: ChatMessage): ChatMessage =
        ChatMessage(role = ChatRole.assistant, content = "收到")

    @Test
    fun `初始化时加载已有会话历史`() = runTest(dispatcher.scheduler) {
        val msg = ChatMessage(role = ChatRole.user, content = "你好")
        whenever(chatService.getHistory()).thenReturn(listOf(msg))

        val vm = ChatViewModel(application)
        vm.advance()

        assertEquals(listOf(msg), vm.state.value.messages)
    }

    @Test
    fun `发送消息会调用 ChatService`() = runTest(dispatcher.scheduler) {
        wheneverBlocking { chatService.sendMessage("hi") }
            .thenReturn(Result.success(ChatMessage(ChatRole.user, "hi") to reply(ChatMessage(ChatRole.user, "hi"))))

        val vm = ChatViewModel(application)
        vm.onEvent(ChatEvent.SendMessage("hi"))
        vm.advance()

        verify(chatService).sendMessage("hi")
    }

    @Test
    fun `清空会话调用服务并清空消息`() = runTest(dispatcher.scheduler) {
        whenever(chatService.getHistory())
            .thenReturn(listOf(ChatMessage(role = ChatRole.user, content = "旧消息")))
        val vm = ChatViewModel(application)
        vm.advance()

        vm.onEvent(ChatEvent.ClearConversation)
        vm.advance()

        verify(chatService).clearHistory()
        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test
    fun `reloadHistory 丢弃被窗口裁剪的旧消息避免拼到末尾`() = runTest(dispatcher.scheduler) {
        // 模拟长对话：当前 UI 内存里已有 6 条（5 条旧 + 1 条最新）
        val old1 = ChatMessage(role = ChatRole.user, content = "旧1")
        val old2 = ChatMessage(role = ChatRole.assistant, content = "旧2")
        val old3 = ChatMessage(role = ChatRole.user, content = "旧3")
        val old4 = ChatMessage(role = ChatRole.assistant, content = "旧4")
        val latestUser = ChatMessage(role = ChatRole.user, content = "最新")
        val latestReply = ChatMessage(role = ChatRole.assistant, content = "回复")

        // 启动时加载全量
        whenever(chatService.getHistory()).thenReturn(
            listOf(old1, old2, old3, old4, latestUser, latestReply)
        )
        val vm = ChatViewModel(application)
        vm.advance()

        // 悬浮窗期间会话被 ConversationManager 裁剪：只剩最近的 3 条（old3/old4 起）
        whenever(chatService.getHistory()).thenReturn(
            listOf(old3, old4, latestUser, latestReply)
        )

        vm.reloadHistory()

        // 被裁剪的 old1/old2 不应被拼到末尾；结果 = 新历史（有序）+ 无多余尾巴
        assertEquals(
            listOf(old3, old4, latestUser, latestReply),
            vm.state.value.messages
        )
    }

    @Test
    fun `reloadHistory 保留历史之后新追加的尾部消息`() = runTest(dispatcher.scheduler) {
        // 历史：msg1/msg2 已落库；当前 UI 里在它们之后有一条未落库的系统气泡
        val msg1 = ChatMessage(role = ChatRole.user, content = "a")
        val msg2 = ChatMessage(role = ChatRole.assistant, content = "b")
        val bubble = ChatMessage(role = ChatRole.system, content = "触摸提示")
        whenever(chatService.getHistory()).thenReturn(listOf(msg1, msg2))
        val vm = ChatViewModel(application)
        vm.advance()

        // 构造 current = [msg1, msg2, bubble]（模拟触摸气泡 append 到末尾）
        val current = listOf(msg1, msg2, bubble)

        // merge：history 尾部 msg2 之后的新消息（bubble）应保留，旧历史以 history 为准
        val merged = vm.mergeWithHistory(listOf(msg1, msg2), current)
        assertEquals(listOf(msg1, msg2, bubble), merged)
    }

    @Test
    fun `有用户消息时重试调用服务`() = runTest(dispatcher.scheduler) {
        val user = ChatMessage(role = ChatRole.user, content = "hi")
        whenever(chatService.getHistory()).thenReturn(listOf(user))
        wheneverBlocking { chatService.retryLastMessage() }
            .thenReturn(Result.success(user to reply(user)))

        val vm = ChatViewModel(application)
        vm.advance()

        vm.onEvent(ChatEvent.RetryLastMessage)
        vm.advance()

        verify(chatService).retryLastMessage()
    }

    @Test
    fun `打开记忆对话框拉取列表与统计`() = runTest(dispatcher.scheduler) {
        wheneverBlocking { memoryManager.getAllMemories() }
            .thenReturn(listOf(mock<MemoryItem>()))

        val vm = ChatViewModel(application)
        vm.onEvent(ChatEvent.ShowMemories)
        vm.advance()

        val dialog = vm.state.value.memoryDialog
        assertNotNull(dialog)
        assertEquals(1, dialog?.memories?.size)
    }

    @Test
    fun `无用户消息时重试不调用服务`() = runTest(dispatcher.scheduler) {
        val vm = ChatViewModel(application)

        vm.onEvent(ChatEvent.RetryLastMessage)
        vm.advance()

        org.mockito.kotlin.verify(chatService, org.mockito.kotlin.never()).retryLastMessage()
    }
}
