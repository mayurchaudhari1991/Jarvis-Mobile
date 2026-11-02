package com.veg.hud

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.veg.data.VegDatabase
import com.veg.data.Message
import com.veg.device.DeviceReadings
import com.veg.device.RuntimeTuner
import com.veg.inference.FakeEngine
import com.veg.inference.InferenceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: VegDatabase

    private fun readings() = DeviceReadings(
        deviceName = "Test Device",
        totalRamBytes = 12L * 1024 * 1024 * 1024,
        availableRamBytes = 7L * 1024 * 1024 * 1024,
        perAppMemoryLimitBytes = 8L * 1024 * 1024 * 1024,
        freeStorageBytes = 60L * 1024 * 1024 * 1024,
        cpuCores = 8,
        performanceCores = 6,
        socModel = "SM8650",
        thermalHeadroom = 0.2f,
        batteryPercent = 80,
        isCharging = false
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            VegDatabase::class.java
        )
            .allowMainThreadQueries()
            // Room's suspend DAOs hop onto its own query/transaction executor and
            // resume the caller from that real background thread. Against a
            // StandardTestDispatcher that resumption races advanceUntilIdle(),
            // which only drains work already visible to it and does not block on
            // a genuinely concurrent thread. Running both executors inline keeps
            // every DB operation on the test thread so advanceUntilIdle() sees
            // the whole chain deterministically.
            .setQueryExecutor(Executor { it.run() })
            .setTransactionExecutor(Executor { it.run() })
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(sqlDb: SupportSQLiteDatabase) {
                    sqlDb.execSQL("PRAGMA foreign_keys=ON;")
                }
            })
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun newViewModel(reply: String = "one two three"): ChatViewModel {
        val engine = FakeEngine(scriptedReply = reply, tokenDelayMillis = 0)
        engine.load(File("ignored"), InferenceConfig(4, 2048, 256))
        return ChatViewModel(engine, db, RuntimeTuner(), ::readings)
    }

    @Test
    fun `starts idle with no messages`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.uiState.test {
            val first = awaitItem()
            assertEquals(ReactorMode.IDLE, first.reactor.mode)
            assertTrue(first.messages.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sending persists the user message`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.send("hello")
        dispatcher.scheduler.advanceUntilIdle()

        val stored = db.conversations().observeAllOnce()
        assertEquals(1, stored.size)
        val messages = db.messages().forConversation(stored[0].id)
        assertEquals(Message.ROLE_USER, messages[0].role)
        assertEquals("hello", messages[0].content)
    }

    @Test
    fun `assistant reply is persisted with measured throughput`() = runTest(dispatcher) {
        val vm = newViewModel(reply = "alpha beta")
        vm.send("hi")
        dispatcher.scheduler.advanceUntilIdle()

        val conversation = db.conversations().observeAllOnce().first()
        val messages = db.messages().forConversation(conversation.id)
        assertEquals(2, messages.size)
        assertEquals(Message.ROLE_ASSISTANT, messages[1].role)
        assertEquals("alpha beta", messages[1].content.trim())
    }

    @Test
    fun `streaming text is cleared once the reply is persisted`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.send("hi")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("", vm.uiState.value.streamingText)
    }

    @Test
    fun `blank input is ignored`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.send("   ")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(db.conversations().observeAllOnce().isEmpty())
    }

    @Test
    fun `seeding the ready message persists it as an assistant message`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.seedReadyMessageIfNeeded()
        dispatcher.scheduler.advanceUntilIdle()

        val stored = db.conversations().observeAllOnce()
        assertEquals(1, stored.size)
        val messages = db.messages().forConversation(stored[0].id)
        assertEquals(1, messages.size)
        assertEquals(Message.ROLE_ASSISTANT, messages[0].role)
        assertEquals(ChatViewModel.READY_GREETING, messages[0].content)
    }

    @Test
    fun `seeding the ready message updates ui state`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.seedReadyMessageIfNeeded()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.uiState.value.messages.size)
        assertEquals(ChatViewModel.READY_GREETING, vm.uiState.value.messages[0].content)
    }

    @Test
    fun `seeding the ready message twice does not duplicate it`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.seedReadyMessageIfNeeded()
        dispatcher.scheduler.advanceUntilIdle()
        vm.seedReadyMessageIfNeeded()
        dispatcher.scheduler.advanceUntilIdle()

        val stored = db.conversations().observeAllOnce()
        assertEquals(1, stored.size)
        val messages = db.messages().forConversation(stored[0].id)
        assertEquals(1, messages.size)
    }

    @Test
    fun `seeding the ready message is skipped once a real conversation exists`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.send("hello")
        dispatcher.scheduler.advanceUntilIdle()

        vm.seedReadyMessageIfNeeded()
        dispatcher.scheduler.advanceUntilIdle()

        val stored = db.conversations().observeAllOnce()
        assertEquals(1, stored.size)
        val messages = db.messages().forConversation(stored[0].id)
        // Only the user message and its reply — no greeting inserted on top.
        assertEquals(2, messages.size)
        assertTrue(messages.none { it.content == ChatViewModel.READY_GREETING })
    }

    @Test
    fun `an all-reasoning response persists an explanatory message not an empty one`() = runTest(dispatcher) {
        // The model spends its whole budget inside <think> and never closes
        // the tag, so ReasoningFilter.visible() would show nothing.
        val vm = newViewModel(reply = "<think> reasoning that never closes and burns the whole budget ")
        vm.send("a hard question")
        dispatcher.scheduler.advanceUntilIdle()

        val conversation = db.conversations().observeAllOnce().first()
        val messages = db.messages().forConversation(conversation.id)
        assertEquals(2, messages.size)
        assertEquals(Message.ROLE_ASSISTANT, messages[1].role)
        assertEquals(ChatViewModel.TRUNCATED_REASONING_MESSAGE, messages[1].content)
    }

    @Test
    fun `a genuinely empty response persists a different explanatory message`() = runTest(dispatcher) {
        val vm = newViewModel(reply = "")
        vm.send("say nothing")
        dispatcher.scheduler.advanceUntilIdle()

        val conversation = db.conversations().observeAllOnce().first()
        val messages = db.messages().forConversation(conversation.id)
        assertEquals(2, messages.size)
        assertEquals(Message.ROLE_ASSISTANT, messages[1].role)
        assertEquals(ChatViewModel.EMPTY_RESPONSE_MESSAGE, messages[1].content)
    }

    @Test
    fun `stopping mid-generation keeps the partial reply`() = runTest(dispatcher) {
        val vm = newViewModel(reply = "one two three four five six seven eight")
        // Non-zero delay so the generation is still in-flight when we stop it.
        val engine = FakeEngine(scriptedReply = "one two three four five six seven eight", tokenDelayMillis = 10)
        engine.load(File("ignored"), InferenceConfig(4, 2048, 256))
        val vmWithDelay = ChatViewModel(engine, db, RuntimeTuner(), ::readings)

        vmWithDelay.send("hi")
        dispatcher.scheduler.advanceTimeBy(25)
        dispatcher.scheduler.runCurrent()

        vmWithDelay.stop()
        dispatcher.scheduler.advanceUntilIdle()

        val conversation = db.conversations().observeAllOnce().first()
        val messages = db.messages().forConversation(conversation.id)
        assertEquals(2, messages.size)
        assertEquals(Message.ROLE_ASSISTANT, messages[1].role)
        assertTrue(messages[1].content.isNotBlank())
        assertTrue("one".let { messages[1].content.contains(it) })
    }
}
