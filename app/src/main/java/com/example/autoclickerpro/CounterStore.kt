package com.example.autoclickerpro

import android.content.Context
import org.json.JSONObject

/**
 * فاز ۱۵ — شمارنده‌های نام‌دار: یک Map<نام, عدد صحیح> ساده که هم به‌عنوان
 * شرط («فقط اگه شمارنده‌ی X کمتر از ۵ باشه») و هم به‌عنوان اکشن («شمارنده‌ی
 * X رو یکی زیاد کن») در ClickRule استفاده می‌شه.
 *
 * عمداً global (نه مخصوصِ یک سناریو) نگه داشته شده — دقیقاً مثل
 * SimpleModeStore، یک لایه‌ی کاملاً جدا و ساده، نه بخشی از مدلِ Scenario/
 * ClickRule که پیچیده‌ترش کنه. اگه کاربر بینِ سناریوها جابه‌جا بشه، مقدارِ
 * شمارنده‌ها حفظ می‌مونه (چون بیشتر کاربردش «چندبار این اتفاق افتاده از
 * وقتی که ریست کردم»ه، نه چیزی که باید per-scenario جدا باشه).
 */
object CounterStore {
    private const val PREFS = "autoclicker_prefs"
    private const val KEY_COUNTERS_JSON = "counters_json"

    private val counters = mutableMapOf<String, Int>()
    private var initialized = false

    @Synchronized
    private fun ensureInit(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_COUNTERS_JSON, null) ?: return
        runCatching {
            val obj = JSONObject(json)
            val it = obj.keys()
            while (it.hasNext()) {
                val key = it.next()
                counters[key] = obj.getInt(key)
            }
        }
    }

    @Synchronized
    fun getAll(context: Context): Map<String, Int> {
        ensureInit(context)
        return counters.toMap()
    }

    @Synchronized
    fun get(context: Context, name: String): Int {
        ensureInit(context)
        return counters[name] ?: 0
    }

    @Synchronized
    fun set(context: Context, name: String, value: Int) {
        ensureInit(context)
        counters[name] = value
        persist(context)
    }

    /** برمی‌گردونه مقدارِ *جدید* (بعد از تغییر) — برای لاگ کردن راحته. */
    @Synchronized
    fun increment(context: Context, name: String, delta: Int): Int {
        ensureInit(context)
        val newValue = (counters[name] ?: 0) + delta
        counters[name] = newValue
        persist(context)
        return newValue
    }

    @Synchronized
    fun reset(context: Context, name: String) {
        ensureInit(context)
        counters[name] = 0
        persist(context)
    }

    @Synchronized
    fun delete(context: Context, name: String) {
        ensureInit(context)
        counters.remove(name)
        persist(context)
    }

    private fun persist(context: Context) {
        val obj = JSONObject()
        for ((k, v) in counters) obj.put(k, v)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COUNTERS_JSON, obj.toString())
            .apply()
    }
}
