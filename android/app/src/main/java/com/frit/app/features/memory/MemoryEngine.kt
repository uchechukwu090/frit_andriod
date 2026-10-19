package com.frit.app.features.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * MemoryEngine — SQLite-backed memory with relevance retrieval and a hard cap.
 * - Max 50 entries (oldest non-seed evicted when full)
 * - all() returns seed + up to 10 most recent entries (avoids token bloat)
 * - relevant(query) returns seed + entries that share keywords with the query
 */
object MemoryEngine {
    private var db: SQLiteDatabase? = null
    private const val MAX_ENTRIES = 50
    private const val MAX_INJECT = 12  // max entries injected into any prompt

    private val SEED = listOf(
        "assistant_name: FRIT",
        "assistant_goal: Full-Range Intelligence Terminal — better than Siri",
        "user_location: Lagos, Nigeria",
        "preferred_currency: NGN and USD"
    )

    fun init(context: Context) {
        if (db != null) return
        db = FritMemoryDbHelper(context).writableDatabase
        val count = db!!.rawQuery("SELECT COUNT(*) FROM memory", null).use {
            it.moveToFirst(); it.getInt(0)
        }
        if (count == 0) SEED.forEach { insertRaw(it, seed = true) }
    }

    /** Returns seed facts + most recent entries, capped at MAX_INJECT */
    fun all(): List<String> {
        val result = mutableListOf<String>()
        db?.rawQuery(
            "SELECT entry FROM memory ORDER BY is_seed DESC, id DESC LIMIT ?",
            arrayOf(MAX_INJECT.toString())
        )?.use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getString(0))
        }
        return result.ifEmpty { SEED }
    }

    /** Returns seed facts + entries relevant to the query keywords */
    fun relevant(query: String): List<String> {
        val keywords = query.lowercase().split(" ")
            .filter { it.length > 3 }
            .take(6)
        if (keywords.isEmpty()) return all()

        val seedEntries = db?.rawQuery(
            "SELECT entry FROM memory WHERE is_seed=1", null
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } } ?: SEED

        val matched = mutableListOf<String>()
        keywords.forEach { kw ->
            db?.rawQuery(
                "SELECT entry FROM memory WHERE is_seed=0 AND lower(entry) LIKE ? LIMIT 5",
                arrayOf("%$kw%")
            )?.use { c -> while (c.moveToNext()) { val e = c.getString(0); if (e !in matched) matched.add(e) } }
        }

        return (seedEntries + matched.take(MAX_INJECT - seedEntries.size))
    }

    fun add(entry: String) {
        if (entry.isBlank()) return
        val exists = db?.rawQuery("SELECT COUNT(*) FROM memory WHERE entry=?", arrayOf(entry))?.use {
            it.moveToFirst(); it.getInt(0) > 0
        } ?: false
        if (exists) return

        // Evict oldest non-seed if at cap
        val count = db?.rawQuery("SELECT COUNT(*) FROM memory WHERE is_seed=0", null)?.use {
            it.moveToFirst(); it.getInt(0)
        } ?: 0
        if (count >= MAX_ENTRIES) {
            db?.execSQL("DELETE FROM memory WHERE is_seed=0 AND id=(SELECT MIN(id) FROM memory WHERE is_seed=0)")
        }
        insertRaw(entry, seed = false)
    }

    fun clear() { db?.execSQL("DELETE FROM memory WHERE is_seed=0") }

    /**
     * Thumbs feedback from chat bubbles. Negative entries are keyword-matched
     * by relevant() on future tasks, and the server prompt carries an
     * avoid-past-failures rule — so a thumbs-down on "Opay transfer tapped
     * the wrong button" steers the next identical task away from it.
     */
    fun addFeedback(positive: Boolean, userTask: String, aiReply: String) {
        val task = userTask.replace(Regex("\\s+"), " ").trim().take(140)
        val reply = aiReply.replace(Regex("\\s+"), " ").trim().take(200)
        if (positive) {
            add("feedback_positive: task=[$task] good_reply=[$reply]")
        } else {
            add("feedback_negative: task=[$task] bad_reply=[$reply] — avoid this approach next time")
        }
    }

    fun remove(entry: String) { db?.delete("memory", "entry=?", arrayOf(entry)) }

    private fun insertRaw(entry: String, seed: Boolean = false) {
        db?.insert("memory", null, ContentValues().apply {
            put("entry", entry)
            put("is_seed", if (seed) 1 else 0)
        })
    }

    private class FritMemoryDbHelper(context: Context) :
        SQLiteOpenHelper(context, "frit_memory.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS memory (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    entry TEXT NOT NULL,
                    is_seed INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Add is_seed column if upgrading from v2
            try { db.execSQL("ALTER TABLE memory ADD COLUMN is_seed INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
        }
    }
}
