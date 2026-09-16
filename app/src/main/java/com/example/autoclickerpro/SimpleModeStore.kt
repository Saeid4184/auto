package com.example.autoclickerpro

import android.content.Context
import org.json.JSONArray

/**
 * فاز ۱۱ — نگهداری لیستِ نقاطِ «حالت ساده» + فاصله‌ی زمانیِ بین کلیک‌ها، با
 * همون الگوی RecordingStore/ScenarioStore (JSON در SharedPreferences، لود
 * تنبل در اولین استفاده).
 *
 * عمداً کاملاً مستقل از RuleStore/ScenarioStore نگه داشته شده — «حالت
 * ساده» قرار نیست با سیستم قوانین/سناریو قاطی بشه، چون کل ایده‌اش اینه که
 * یک مسیر خیلی ساده‌تر برای کسایی باشه که فقط می‌خوان چندتا نقطه‌ی ثابت رو
 * پشت سر هم کلیک کنن.
 */
object SimpleModeStore {
    private const val PREFS = "autoclicker_prefs"
    private const val KEY_POINTS = "simple_mode_points_json"
    private const val KEY_INTERVAL_MS = "simple_mode_interval_ms"

    const val DEFAULT_INTERVAL_MS = 1000L
    const val MIN_INTERVAL_MS = 100L // زیر این مقدار، خطر لود بی‌مورد روی سیستم بیشتر از فایده‌شه

    private val points = mutableListOf<SimplePoint>()
    private var initialized = false

    @Synchronized
    private fun ensureInit(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_POINTS, null) ?: return
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                points.add(SimplePoint(o.getDouble("x").toFloat(), o.getDouble("y").toFloat()))
            }
        }
    }

    @Synchronized
    fun getPoints(context: Context): List<SimplePoint> {
        ensureInit(context)
        return points.toList()
    }

    @Synchronized
    fun addPoint(context: Context, point: SimplePoint) {
        ensureInit(context)
        points.add(point)
        persist(context)
    }

    @Synchronized
    fun removePointAt(context: Context, index: Int) {
        ensureInit(context)
        if (index in points.indices) {
            points.removeAt(index)
            persist(context)
        }
    }

    @Synchronized
    fun clearPoints(context: Context) {
        ensureInit(context)
        points.clear()
        persist(context)
    }

    /** فاز ۱۲: جایگزینیِ کاملِ ترتیبِ نقاط — برای drag-to-reorder در UI. */
    @Synchronized
    fun setPoints(context: Context, newPoints: List<SimplePoint>) {
        ensureInit(context)
        points.clear()
        points.addAll(newPoints)
        persist(context)
    }

    fun getIntervalMs(context: Context): Long {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS)
    }

    fun setIntervalMs(context: Context, ms: Long) {
        val safe = ms.coerceAtLeast(MIN_INTERVAL_MS)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_INTERVAL_MS, safe)
            .apply()
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        for (p in points) {
            arr.put(org.json.JSONObject().apply {
                put("x", p.x.toDouble())
                put("y", p.y.toDouble())
            })
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_POINTS, arr.toString())
            .apply()
    }
}
