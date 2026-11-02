package com.veg.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class VegDatabaseTest {

    private lateinit var db: VegDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            VegDatabase::class.java
        )
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(sqlDb: SupportSQLiteDatabase) {
                    sqlDb.execSQL("PRAGMA foreign_keys=ON;")
                }
            })
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `inserting a conversation returns a usable id`() = runTest {
        val id = db.conversations().insert(
            Conversation(title = "First", createdAt = 1000, updatedAt = 1000)
        )
        assertEquals("First", db.conversations().byId(id)?.title)
    }

    @Test
    fun `messages round trip in insertion order`() = runTest {
        val convId = db.conversations().insert(
            Conversation(title = "Chat", createdAt = 1000, updatedAt = 1000)
        )
        db.messages().insert(
            Message(conversationId = convId, role = Message.ROLE_USER, content = "hello", createdAt = 1001)
        )
        db.messages().insert(
            Message(
                conversationId = convId,
                role = Message.ROLE_ASSISTANT,
                content = "hi there",
                createdAt = 1002,
                tokensPerSecond = 10.4f
            )
        )

        val messages = db.messages().forConversation(convId)
        assertEquals(2, messages.size)
        assertEquals("hello", messages[0].content)
        assertEquals(Message.ROLE_ASSISTANT, messages[1].role)
        assertEquals(10.4f, messages[1].tokensPerSecond!!, 0.001f)
    }

    @Test
    fun `observing messages emits on insert`() = runTest {
        val convId = db.conversations().insert(
            Conversation(title = "Chat", createdAt = 1000, updatedAt = 1000)
        )

        db.messages().observeForConversation(convId).test {
            assertEquals(0, awaitItem().size)

            db.messages().insert(
                Message(conversationId = convId, role = Message.ROLE_USER, content = "ping", createdAt = 1001)
            )
            assertEquals(1, awaitItem().size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting a conversation cascades to its messages`() = runTest {
        val convId = db.conversations().insert(
            Conversation(title = "Chat", createdAt = 1000, updatedAt = 1000)
        )
        db.messages().insert(
            Message(conversationId = convId, role = Message.ROLE_USER, content = "x", createdAt = 1001)
        )

        db.conversations().delete(convId)

        assertNull(db.conversations().byId(convId))
        assertEquals(0, db.messages().forConversation(convId).size)
    }

    @Test
    fun `touch updates the conversation timestamp`() = runTest {
        val convId = db.conversations().insert(
            Conversation(title = "Chat", createdAt = 1000, updatedAt = 1000)
        )
        db.conversations().touch(convId, 5000L)
        assertEquals(5000L, db.conversations().byId(convId)?.updatedAt)
    }

    @Test
    fun `conversations are observed newest first`() = runTest {
        db.conversations().insert(Conversation(title = "Older", createdAt = 1000, updatedAt = 1000))
        db.conversations().insert(Conversation(title = "Newer", createdAt = 2000, updatedAt = 2000))

        db.conversations().observeAll().test {
            val list = awaitItem()
            assertEquals("Newer", list[0].title)
            assertEquals("Older", list[1].title)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
