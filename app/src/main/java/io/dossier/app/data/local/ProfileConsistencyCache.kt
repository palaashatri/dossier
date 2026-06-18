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

    fun insertEmbedding(url: String, embedding: FloatArray) {
        val db = this.db ?: return
        val embeddingString = embedding.joinToString(",")
        db.execSQL(
            "INSERT OR REPLACE INTO consistency_cache (url, embedding) VALUES (?, ?)",
            arrayOf(url, embeddingString)
        )
    }

    fun getEmbedding(url: String): FloatArray? {
        val db = this.db ?: return null
        val cursor = db.rawQuery(
            "SELECT embedding FROM consistency_cache WHERE url = ?",
            arrayOf(url)
        )
        cursor.use {
            if (it.moveToFirst()) {
                val str = it.getString(0)
                if (str.isBlank()) return null
                return str.split(",").mapNotNull { s -> s.trim().toFloatOrNull() }.toFloatArray()
            }
        }
        return null
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
                "CREATE TABLE consistency_cache (url TEXT PRIMARY KEY, embedding TEXT)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS consistency_cache")
            onCreate(db)
        }
    }
}
