package com.example.autoclickerpro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import java.util.concurrent.CopyOnWriteArrayList

/**
 * فاز ۱۷ — نشونه‌گذاریِ کلیک‌ها: هر بار که اپ (در هر حالتی — قوانین،
 * «حالت ساده»، یا پخشِ یک ضبط) واقعاً یک کلیک/سوایپ/لانگ‌پرس روی صفحه
 * dispatch می‌کنه، یک دایره‌ی شماره‌دار دقیقاً روی همون نقطه کشیده می‌شه
 * و می‌مونه — تا کاربر هم تعداد کل کلیک‌ها و هم محل دقیقشون رو ببینه.
 *
 * طراحی: یک اوورلیِ تمام‌صفحه‌ی *غیرقابل‌لمس* (FLAG_NOT_TOUCHABLE) —
 * یعنی هیچ لمسی رو نمی‌گیره و روی خودِ کلیک‌های واقعی/اسکرول زیرین هیچ
 * اثری نمی‌ذاره، فقط رسم می‌کنه. چون این یک singleton مستقل از هر
 * Service خاصه (فقط به Context.applicationContext نیاز داره)، از هر
 * جای اپ (ClickAccessibilityService برای همه‌ی کلیک‌های خودکار) قابل
 * صدا زدنه، بدون قفل‌شدن به چرخه‌ی حیاتِ یک Service.
 */
object ClickMarkerOverlay {

    private data class Marker(val x: Float, val y: Float, val number: Int)

    private const val RADIUS_PX = 42f

    // بعد از این تعداد، قدیمی‌ترین نشونه‌ها از صفحه حذف می‌شن (وگرنه بعد از
    // چند صد کلیک، هم صفحه غیرقابل‌خوندن می‌شه هم onDraw هزینه‌ی نامحدود
    // می‌گیره)؛ شمارنده‌ی کلِ کلیک‌ها (totalCount) اما مستقل از این حذف،
    // فقط با دکمه‌ی پاک‌کردن صفر می‌شه.
    private const val MAX_VISIBLE_MARKERS = 200

    private val markers = CopyOnWriteArrayList<Marker>()

    @Volatile
    private var totalCount = 0

    private var windowManager: WindowManager? = null
    private var drawView: MarkerView? = null
    private var counterButton: Button? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    /** ثبتِ یک کلیک/سوایپ/لانگ‌پرسِ جدید روی نقطه‌ی (x, y) و رسمِ نشونه‌ش. */
    @Synchronized
    fun mark(context: Context, x: Float, y: Float) {
        totalCount += 1
        markers.add(Marker(x, y, totalCount))
        while (markers.size > MAX_VISIBLE_MARKERS) {
            markers.removeAt(0)
        }
        val countNow = totalCount
        mainHandler.post {
            ensureOverlay(context)
            drawView?.invalidate()
            counterButton?.text = "🎯 $countNow"
        }
    }

    private fun ensureOverlay(context: Context) {
        if (drawView != null) return
        val appContext = context.applicationContext
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val view = MarkerView(appContext)
        val viewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        runCatching { wm.addView(view, viewParams) }
        drawView = view

        // دکمه‌ی کوچیکِ گوشه‌ی پایین-چپ: شمارنده‌ی کل رو نشون می‌ده و با تپ
        // روش، همه‌ی نشونه‌ها/شمارنده پاک می‌شن (برای شروع یک شمارش تازه).
        val btn = Button(appContext).apply {
            text = "🎯 $totalCount"
            setOnClickListener { clear() }
        }
        val btnParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 24
            y = 160
        }
        runCatching { wm.addView(btn, btnParams) }
        counterButton = btn
    }

    /** همه‌ی نشونه‌های روی صفحه و شمارنده‌ی کل رو پاک می‌کنه. */
    @Synchronized
    fun clear() {
        totalCount = 0
        markers.clear()
        mainHandler.post {
            drawView?.invalidate()
            counterButton?.text = "🎯 0"
        }
    }

    /** فقط رسم می‌کنه؛ هیچ لمسی نمی‌گیره (خودِ اوورلی FLAG_NOT_TOUCHABLE داره). */
    private class MarkerView(context: Context) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC2962FF")
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (m in markers) {
                canvas.drawCircle(m.x, m.y, RADIUS_PX, fillPaint)
                canvas.drawCircle(m.x, m.y, RADIUS_PX, borderPaint)
                val label = m.number.toString()
                val textY = m.y - (textPaint.descent() + textPaint.ascent()) / 2
                canvas.drawText(label, m.x, textY, textPaint)
            }
        }
    }
}

