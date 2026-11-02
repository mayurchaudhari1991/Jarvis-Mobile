package com.veg.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BackupRepositoryTest {

    private lateinit var db: VegDatabase
    private lateinit var repo: BackupRepository

    private fun newDb(): VegDatabase =
        Room.inMemoryDatabaseBuilder(
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

    @Before
    fun setUp() {
        db = newDb()
        repo = BackupRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `export then import into a fresh database restores everything`() = runTest {
        val convId = db.conversations().insert(
            Conversation(title = "Planning", createdAt = 1000, updatedAt = 2000)
        )
        db.messages().insert(
            Message(conversationId = convId, role = Message.ROLE_USER, content = "hello", createdAt = 1001)
        )
        db.messages().insert(
            Message(
                conversationId = convId,
                role = Message.ROLE_ASSISTANT,
                content = "hi",
                createdAt = 1002,
                tokensPerSecond = 10f
            )
        )

        val blob = repo.export("pass".toCharArray())

        val fresh = newDb()
        try {
            BackupRepository(fresh).import(blob, "pass".toCharArray())

            val conversations = fresh.conversations().observeAllOnce()
            assertEquals(1, conversations.size)
            assertEquals("Planning", conversations[0].title)

            val messages = fresh.messages().forConversation(conversations[0].id)
            assertEquals(2, messages.size)
            assertEquals("hello", messages[0].content)
            assertEquals(10f, messages[1].tokensPerSecond!!, 0.001f)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `import replaces existing content rather than merging`() = runTest {
        db.conversations().insert(Conversation(title = "Old", createdAt = 1, updatedAt = 1))
        val blob = BackupCodec.encrypt(
            BackupPayload(
                conversations = listOf(Conversation(id = 42, title = "Restored", createdAt = 9, updatedAt = 9)),
                messages = emptyList()
            ),
            "pass".toCharArray()
        )

        repo.import(blob, "pass".toCharArray())

        val conversations = db.conversations().observeAllOnce()
        assertEquals(1, conversations.size)
        assertEquals("Restored", conversations[0].title)
    }
}
