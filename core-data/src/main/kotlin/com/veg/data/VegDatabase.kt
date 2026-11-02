package com.veg.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Conversation::class, Message::class],
    version = 1,
    exportSchema = false
)
abstract class VegDatabase : RoomDatabase() {

    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao

    companion object {
        fun open(context: Context): VegDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                VegDatabase::class.java,
                "veg.db"
            )
                // Foreign keys are off by default in SQLite; the cascade delete
                // on messages depends on them being enabled per connection.
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys=ON;")
                    }
                })
                .build()
    }
}
