package io.dossier.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ProfileConsistencyCache(context: Context) {
    private val dbHelper = DbHelper(context)
    private var db: SQLiteDatabase? = null

    init {
        // null database name creates an in-memory SQLite database
        db = dbHelper.writableDatabase
    }

    fun clearAll() {
        val db = this.db ?: return
        db.execSQL("DELETE FROM consistency_cache")
    }

    fun close() {
        dbHelper.close()
        db = null
    }

    private class DbHelper(context: Context) : SQLiteOpenHelper(context, null, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE consistency_cache (url TEXT PRIMARY KEY)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS consistency_cache")
            onCreate(db)
        }
    }
}
