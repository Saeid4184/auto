package com.example.autoclickerpro

/**
 * فاز ۵ — ضبط و پخش دقیق حرکات لمسی.
 *
 * برخلاف ClickRule (که «اگه X رو دیدی، Y کار رو بکن» هست و به تشخیص
 * المان/تصویر وابسته‌ست)، یک Recording یعنی «دقیقاً همون کاری که من با
 * انگشتم روی صفحه انجام دادم، با همون مختصات و همون فاصله‌ی زمانیِ بین
 * لمس‌ها، دوباره انجام بده» — بدون هیچ تشخیصی. برای توالی‌های ثابت و
 * تکراری (مثلاً یک زنجیره‌ی چندمرحله‌ای تپ/سوایپ که هر بار دقیقاً یک‌جور
 * تکرار می‌شه) ساده‌تر و سریع‌تر از ساختن چند قانون جداست.
 */

/** یک نقطه‌ی نمونه‌برداری‌شده از یک لمسِ پیوسته، با زمانِ نسبت به لحظه‌ی
 *  شروعِ کل ضبط (نه شروع همین لمس) — همین که همه‌ی نقاط یک مرجع زمانی
 *  مشترک دارن، فاصله‌ی طبیعی بین لمس‌های مختلف هم خودکار حفظ می‌شه. */
data class TouchPoint(
    val x: Float,
    val y: Float,
    val tOffsetMs: Long
)

/**
 * یک "لمس پیوسته": از لحظه‌ای که انگشت روی صفحه گذاشته شد (DOWN) تا لحظه‌ای
 * که برداشته شد (UP/CANCEL). یک تپ ساده = یک استروک با یکی‌دو نقطه؛ یک
 * سوایپ یا درگ = یک استروک با چند نقطه‌ی میانی که مسیر واقعی انگشت رو
 * دنبال می‌کنن.
 */
data class RecordedStroke(
    val points: MutableList<TouchPoint> = mutableListOf()
) {
    val startTimeMs: Long get() = points.firstOrNull()?.tOffsetMs ?: 0L

    val durationMs: Long get() {
        if (points.size < 2) return 60L // حداقل مدت برای یک تپ سادهٔ تک‌نقطه‌ای
        return (points.last().tOffsetMs - points.first().tOffsetMs).coerceAtLeast(1L)
    }
}

data class Recording(
    val id: String,
    var name: String,
    val strokes: MutableList<RecordedStroke> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
) {
    val totalDurationMs: Long
        get() = strokes.maxOfOrNull { it.startTimeMs + it.durationMs } ?: 0L

    fun describe(): String {
        val seconds = totalDurationMs / 1000.0
        return "${strokes.size} لمس · حدود ${"%.1f".format(seconds)} ثانیه"
    }
}
