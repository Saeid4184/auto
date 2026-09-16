package com.example.autoclickerpro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * فاز ۵ — نگهداری لیستِ ضبط‌های لمسی، با همون الگوی ScenarioStore
 * (JSON در SharedPreferences، لود تنبل در اولین استفاده).
 */
object RecordingStore {
    private const val PREFS = "autoclicker_prefs"
    private const val KEY_RECORDINGS = "recordings_json"

    private val recordings = mutableListOf<Recording>()
    private var initialized = false

    @Synchronized
    private fun ensureInit(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RECORDINGS, null) ?: return
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                recordings.add(recordingFromJson(arr.getJSONObject(i)))
            }
        }
    }

    @Synchronized
    fun getAll(context: Context): List<Recording> {
        ensureInit(context)
        return recordings.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun get(context: Context, id: String): Recording? {
        ensureInit(context)
        return recordings.find { it.id == id }
    }

    @Synchronized
    fun add(context: Context, recording: Recording) {
        ensureInit(context)
        recordings.add(recording)
        persist(context)
    }

    @Synchronized
    fun rename(context: Context, id: String, newName: String) {
        ensureInit(context)
        recordings.find { it.id == id }?.name = newName.ifBlank { "ضبط بدون نام" }
        persist(context)
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        ensureInit(context)
        recordings.removeAll { it.id == id }
        persist(context)
    }

    fun newId(): String = UUID.randomUUID().toString()

    // ---------- (de)serialization ----------

    private fun pointFromJson(o: JSONObject) = TouchPoint(
        x = o.getDouble("x").toFloat(),
        y = o.getDouble("y").toFloat(),
        tOffsetMs = o.getLong("t")
    )

    private fun pointToJson(p: TouchPoint) = JSONObject().apply {
        put("x", p.x.toDouble())
        put("y", p.y.toDouble())
        put("t", p.tOffsetMs)
    }

    private fun strokeFromJson(o: JSONObject): RecordedStroke {
        val arr = o.getJSONArray("points")
        val pts = mutableListOf<TouchPoint>()
        for (i in 0 until arr.length()) pts.add(pointFromJson(arr.getJSONObject(i)))
        return RecordedStroke(pts)
    }

    private fun strokeToJson(s: RecordedStroke) = JSONObject().apply {
        val arr = JSONArray()
        for (p in s.points) arr.put(pointToJson(p))
        put("points", arr)
    }

    private fun recordingFromJson(o: JSONObject): Recording {
        val arr = o.getJSONArray("strokes")
        val strokes = mutableListOf<RecordedStroke>()
        for (i in 0 until arr.length()) strokes.add(strokeFromJson(arr.getJSONObject(i)))
        return Recording(
            id = o.getString("id"),
            name = o.getString("name"),
            strokes = strokes,
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
    }

    private fun recordingToJson(r: Recording) = JSONObject().apply {
        put("id", r.id)
        put("name", r.name)
        put("createdAt", r.createdAt)
        val arr = JSONArray()
        for (s in r.strokes) arr.put(strokeToJson(s))
        put("strokes", arr)
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        for (r in recordings) arr.put(recordingToJson(r))
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDINGS, arr.toString())
            .apply()
    }
}
