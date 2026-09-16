package com.example.autoclickerpro

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * فاز ۸ — یک ردیف از تاریخچه‌ی اجرا: کدوم قانون، کِی، با چه اکشنی، و آیا
 * واقعی بوده یا فقط حالت آزمایشی (dry-run).
 */
data class RunLogEntry(
    val timestampMs: Long,
    val ruleLabel: String,
    val matchType: MatchType,
    val actionType: ActionType,
    val x: Float,
    val y: Float,
    val dryRun: Boolean
) {
    fun describe(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
        val prefix = if (dryRun) "🧪 " else ""
        return "$prefix$time — $ruleLabel (${actionType.name.lowercase()} در ${x.toInt()},${y.toInt()})"
    }
}
