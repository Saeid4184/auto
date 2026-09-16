package com.example.autoclickerpro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * فاز ۸ — تاریخچه‌ی اجرای قوانین (چه واقعی، چه در حالت آزمایشی). هر بار
 * ClickAccessibilityService.performAction اجرا بشه، یک ردیف اینجا اضافه
 * می‌شه. برای اینکه SharedPreferences بی‌نهایت بزرگ نشه، فقط MAX_ENTRIES
 * ردیفِ آخر نگه‌داشته می‌شه (قدیمی‌ترها خودکار حذف می‌شن).
 *
 * بهینه‌سازی مهم: قبلاً این آبجکت (برخلاف ScenarioStore/RecordingStore/
 * RuleStore که همه از یک الگوی «لود تنبل یک‌بار + لیستِ در حافظه» استفاده
 * می‌کنن) چیزی رو در حافظه کش نمی‌کرد — هر add() اول با getAll() کل آرایه‌ی
 * JSON رو از SharedPreferences دوباره می‌خوند و پارس می‌کرد، بعد یک ردیف
 * اضافه و کل آرایه رو دوباره serialize/نوشت. چون add() دقیقاً همون‌جاییه که
 * روی هر کلیک/سوایپ/لانگ‌پرسِ *واقعی* (و هر تلاشِ حالت آزمایشی) صدا زده
 * می‌شه — یعنی احتمالاً پرتکرارترین نقطه‌ی کل اپ —، این یعنی پارسِ کاملِ تا
 * ۳۰۰ ردیف JSON روی هر تکِ کلیک، برای هیچ‌دلیلی جز اینکه از قبل تو حافظه
 * نداشتیمش. حالا دقیقاً مثل بقیه‌ی store ها، لیست فقط یک‌بار (اولین
 * استفاده) از SharedPreferences پارس می‌شه و از اون به بعد مستقیم در حافظه
 * append/persist می‌شه.
 */
object RunLogStore {
    private const val PREFS = "autoclicker_prefs"
    private const val KEY_LOG = "run_log_json"
    private const val MAX_ENTRIES = 300

    private val entries = mutableListOf<RunLogEntry>()
    private var initialized = false

    @Synchronized
    private fun ensureInit(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LOG, null) ?: return
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                entries.add(entryFromJson(arr.getJSONObject(i)))
            }
        }
    }

    @Synchronized
    fun add(context: Context, entry: RunLogEntry) {
        ensureInit(context)
        entries.add(entry)
        if (entries.size > MAX_ENTRIES) {
            // فقط MAX_ENTRIES ردیفِ آخر باقی می‌مونه — حذف از ابتدا به‌جای
            // ساختنِ یک لیست جدید با takeLast، چون این لیست همون نمونه‌ی در
            // حافظه‌ایه که مستقیم mutate می‌شه.
            repeat(entries.size - MAX_ENTRIES) { entries.removeAt(0) }
        }
        persist(context)
    }

    @Synchronized
    fun getAll(context: Context): List<RunLogEntry> {
        ensureInit(context)
        return entries.toList()
    }

    @Synchronized
    fun clear(context: Context) {
        ensureInit(context)
        entries.clear()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_LOG).apply()
    }

    private fun entryToJson(e: RunLogEntry): JSONObject = JSONObject().apply {
        put("timestampMs", e.timestampMs)
        put("ruleLabel", e.ruleLabel)
        put("matchType", e.matchType.name)
        put("actionType", e.actionType.name)
        put("x", e.x.toDouble())
        put("y", e.y.toDouble())
        put("dryRun", e.dryRun)
    }

    private fun entryFromJson(obj: JSONObject): RunLogEntry = RunLogEntry(
        timestampMs = obj.getLong("timestampMs"),
        ruleLabel = obj.getString("ruleLabel"),
        matchType = MatchType.valueOf(obj.getString("matchType")),
        actionType = ActionType.valueOf(obj.getString("actionType")),
        x = obj.getDouble("x").toFloat(),
        y = obj.getDouble("y").toFloat(),
        dryRun = obj.optBoolean("dryRun", false)
    )

    private fun persist(context: Context) {
        val arr = JSONArray()
        for (e in entries) arr.put(entryToJson(e))
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOG, arr.toString())
            .apply()
    }
}
