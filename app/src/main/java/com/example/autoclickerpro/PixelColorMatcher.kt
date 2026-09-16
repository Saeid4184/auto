package com.example.autoclickerpro

import android.graphics.Bitmap
import android.graphics.Color

/**
 * فاز ۹ — تشخیص رنگ پیکسل در یک نقطه‌ی خاص، به‌جای مقایسه‌ی کل تصویر با یک
 * الگو (کاری که ImageMatcher/OpenCV با matchTemplate انجام می‌ده).
 *
 * چرا این جدا از ImageMatcher است؟ چون خوندن رنگ یک پیکسل (getPixel روی یک
 * Bitmap که از قبل توسط ScreenCaptureService گرفته شده) کار خیلی سبک‌تری
 * نسبت به matchTemplate روی کل فریمه (نیازی به OpenCV/Mat/grayscale نیست) —
 * برای مواردی مثل "آیا نوار سلامتی سبزه یا قرمز؟" یا "آیا این آیکون فعال
 * (رنگی) یا غیرفعال (خاکستری) است؟" این روش هم سریع‌تره هم ساده‌تر.
 */
object PixelColorMatcher {

    data class ColorMatchResult(val x: Float, val y: Float, val actualColorHex: String)

    /**
     * رنگ پیکسل (x, y) در [frame] رو با [targetColorHex] («#RRGGBB» یا
     * «#AARRGGBB») مقایسه می‌کنه. اگه اختلاف هر کدوم از کانال‌های R/G/B از
     * [tolerance] (۰ تا ۲۵۵) بیشتر نباشه، match موفق حساب می‌شه.
     * کانال آلفا عمداً نادیده گرفته می‌شه چون فریم‌های گرفته‌شده از صفحه
     * معمولاً کاملاً غیرشفافن.
     */
    fun matches(frame: Bitmap, x: Int, y: Int, targetColorHex: String, tolerance: Int): ColorMatchResult? {
        if (x < 0 || y < 0 || x >= frame.width || y >= frame.height) return null
        val target = runCatching { Color.parseColor(targetColorHex) }.getOrNull() ?: return null
        val pixel = runCatching { frame.getPixel(x, y) }.getOrNull() ?: return null

        val tol = tolerance.coerceIn(0, 255)
        val dr = kotlin.math.abs(Color.red(pixel) - Color.red(target))
        val dg = kotlin.math.abs(Color.green(pixel) - Color.green(target))
        val db = kotlin.math.abs(Color.blue(pixel) - Color.blue(target))

        if (dr > tol || dg > tol || db > tol) return null

        val actualHex = String.format("#%06X", 0xFFFFFF and pixel)
        return ColorMatchResult(x.toFloat(), y.toFloat(), actualHex)
    }
}
