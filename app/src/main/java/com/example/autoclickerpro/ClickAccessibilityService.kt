package com.example.autoclickerpro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * فاز ۱:
 * این سرویس مسئول اجرای فیزیکی کلیک/درگ/سوایپ روی مختصات مشخص‌شده است،
 * از طریق dispatchGesture (نیازی به روت نداره).
 *
 * فاز ۲ به این سرویس متد findNodeAndClick اضافه می‌کنیم که با گشتن در
 * rootInActiveWindow دنبال یک متن/id خاص می‌گرده و به‌جای مختصات ثابت،
 * محل واقعی همون المان رو کلیک می‌کنه.
 *
 * نکته طراحی: از یک companion object با نمونه‌ی static استفاده می‌کنیم تا
 * OverlayService (که خودش یک Service جداست) بتونه مستقیماً به این سرویس
 * دستور بده، بدون نیاز به AIDL/Binder پیچیده.
 */
class ClickAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClickAccessibilityService"
        var instance: ClickAccessibilityService? = null
            private set

        // فاز ۷: حالت آزمایشی — وقتی روشنه، هیچ ژست واقعی‌ای dispatch نمی‌شه؛
        // فقط dryRunListener صدا زده می‌شه تا کاربر ببینه کدوم قانون کجا
        // می‌خواست کلیک بزنه، بدون اینکه واقعاً روی اپ زیرین اثر بذاره.
        var dryRunEnabled: Boolean = false
        var dryRunListener: ((ClickRule, Float, Float) -> Unit)? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // باگ کارایی رفع‌شده: قبلاً تطبیق تصویر با OpenCV (matchTemplate روی کل
    // فریم صفحه) مستقیماً روی ترد اصلی (همون تردی که حلقه‌ی کلیک باهاش
    // زمان‌بندی می‌شه) اجرا می‌شد. برای صفحه‌های بزرگ یا چند قانون IMAGE
    // هم‌زمان، این می‌تونست چند ده تا چند صد میلی‌ثانیه طول بکشه و باعث لگ/ANR
    // بشه. حالا این کار روی یک ترد پس‌زمینه‌ی اختصاصی انجام می‌شه.
    private val imageMatchExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        imageMatchExecutor.shutdownNow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // فاز ۲: اینجا می‌تونیم رویدادهای تغییر صفحه رو گوش بدیم تا بفهمیم
        // چه زمانی صفحه عوض شده و باید دوباره دنبال المان بگردیم.
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    /** یک تپ ساده روی مختصات (x, y) */
    fun performClick(x: Float, y: Float, onDone: ((Boolean) -> Unit)? = null) {
        ClickMarkerOverlay.mark(this, x, y)
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone?.invoke(false)
            }
        }, null)
    }

    /** سوایپ/درگ از یک نقطه به نقطه دیگر در بازه زمانی durationMs */
    fun performSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        ClickMarkerOverlay.mark(this, startX, startY)
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone?.invoke(false)
            }
        }, null)
    }

    /** لانگ‌پرس روی یک نقطه (برای عملیاتی مثل درگ‌ودراپ بعداً کاربرد داره) */
    fun performLongPress(x: Float, y: Float, durationMs: Long = 500, onDone: ((Boolean) -> Unit)? = null) {
        ClickMarkerOverlay.mark(this, x, y)
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone?.invoke(false)
            }
        }, null)
    }

    // ==================== فاز ۲: تشخیص المان از طریق UI Tree ====================

    data class ElementInfo(
        val label: String,
        val matchType: MatchType,
        val matchValue: String
    )

    /**
     * درخت المان‌های صفحه‌ی فعلی (rootInActiveWindow) رو پیمایش می‌کنه و
     * المان‌های قابل‌کلیک (isClickable == true) که متن، content-description
     * یا viewId قابل‌استفاده دارن رو برمی‌گردونه. این لیست برای نمایش به
     * کاربر (تا یکی رو به‌عنوان هدف انتخاب کنه) استفاده می‌شه.
     */
    fun listClickableElements(): List<ElementInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<ElementInfo>()
        val seen = HashSet<String>()

        // بهینه‌سازی/رفع نشتی: قبلاً هیچ‌کدوم از AccessibilityNodeInfoهایی که
        // با getChild گرفته می‌شدن recycle نمی‌شدن. زیر اندروید ۱۳ (که در اون
        // recycle() هنوز واقعاً کاری انجام می‌ده، نه فقط no-op) این یعنی برای
        // هر تپ روی دکمه‌ی شناور (که کل درخت المان‌ها رو می‌گرده) یک عالمه
        // آبجکت بومی بدون آزادسازی می‌موندن. چون هیچ‌جای دیگه‌ای رفرنس این
        // نودها نگه داشته نمی‌شه (فقط text/desc/viewId که رشته‌ن ازشون کپی
        // می‌شه)، recycle کردنِ هر نود بلافاصله بعد از تمومِ کارش (بعد از پیمایشِ
        // کامل فرزندانش) کاملاً امنه.
        val canRecycle = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 60) return
            // isVisibleToUser هم چک می‌شه تا این لیست دقیقاً همون چیزی باشه که
            // matchRule (هنگام اجرای واقعی قانون) هم می‌بینه — قبلاً ممکن بود
            // یک المانِ کلیک‌پذیرِ ولی نامرئی/خارج از صفحه (مثلاً هنوز اسکرول
            // نشده) توی لیست انتخاب نشون داده بشه که در عمل هیچ‌وقت match
            // نمی‌شد.
            if (node.isClickable && node.isVisibleToUser) {
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val viewId = node.viewIdResourceName

                val info = when {
                    !text.isNullOrBlank() -> ElementInfo(text, MatchType.TEXT, text)
                    !desc.isNullOrBlank() -> ElementInfo(desc, MatchType.TEXT, desc)
                    !viewId.isNullOrBlank() -> ElementInfo(viewId.substringAfterLast('/'), MatchType.VIEW_ID, viewId)
                    else -> null
                }
                if (info != null && seen.add(info.matchValue)) {
                    results.add(info)
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
            }
            if (canRecycle) {
                @Suppress("DEPRECATION")
                runCatching { node.recycle() }
            }
        }

        walk(root, 0)
        return results.take(50)
    }

    // فاز ۹: قوانینی که الان دارن منتظر می‌مونن (waitForDisappearAfterClick)
    // یا در میانه‌ی تاخیر تصادفی بعد از کلیک هستن، اینجا نگه‌داشته می‌شن تا
    // همون قانون در دورهای بعدیِ حلقه (که هر loopIntervalMs یک‌بار، مستقل از
    // این وضعیت، صدا زده می‌شه) دوباره کلیک نکنه — یعنی جلوی کلیک تکراری
    // روی چیزی که هنوز داره محو می‌شه رو می‌گیره.
    private val busyRuleIds = ConcurrentHashMap.newKeySet<String>()

    // بهینه‌سازی زنجیره‌ی شرطی: سقف تعداد hopِ elseRuleId در یک دور. اگه دو
    // قانون به هم اشاره کنن (A وگرنه B، B وگرنه A) و هیچ‌کدوم شرطشون برقرار
    // نباشه، بدون این سقف یا با StackOverflowError کرش می‌کردیم (برای انواع
    // sync مثل TEXT/VIEW_ID/COORDINATE) یا حلقه‌ی بی‌نهایتِ async می‌ساختیم
    // (برای IMAGE/PIXEL_COLOR/OCR که هر هاپ با Handler.post تاخیر می‌افته).
    private val maxElseChainDepth = 8

    /**
     * تلاش می‌کنه یک قانون رو اجرا کنه: اول شرط (matchType/matchValue) رو
     * چک می‌کنه؛ اگه برقرار بود، عمل رو انجام می‌ده (و در صورت تنظیم، بعدش
     * صبر می‌کنه تا هدف ناپدید بشه + یک تاخیر تصادفی اضافه می‌ذاره). اگه
     * برقرار نبود و [rule] یک elseRuleId داشته باشه (زنجیره‌ی شرطی «اگه X
     * دیدی → Y، وگرنه → Z»)، بلافاصله همون دور، قانون جایگزین (که باید در
     * [allRules] — معمولاً همه‌ی قوانین سناریوی فعال — پیدا بشه) اجرا می‌شه.
     *
     * [allRules] یک Map<id, ClickRule> هست (نه List) تا هر هاپِ زنجیره
     * O(1) باشه، نه O(n) — برای سناریوهایی با تعداد قانون زیاد و زنجیره‌های
     * طولانی، این تفاوت واقعاً حس می‌شه چون هر hop یک lookup جدیده.
     * OverlayService (حلقه‌ی کلیک) این Map رو یک‌بار در هر تیک می‌سازه و به
     * همه‌ی قوانینِ همون تیک پاس می‌ده؛ اگه پاس داده نشه (پیش‌فرض خالی)،
     * زنجیره‌ی شرطی کار نمی‌کنه ولی بقیه‌ی رفتار عادیه.
     *
     * [visited] فقط داخلیه (خودِ تابع موقع بازگشت روی elseRuleId پرش می‌کنه)
     * و کاربر/OverlayService هیچ‌وقت نباید مقداری براش پاس بده — برای
     * تشخیص و قطعِ زنجیره‌ی حلقوی استفاده می‌شه: اگه در همین یک دورِ حلقه
     * دوباره به قانونی برسیم که قبلاً در همین زنجیره دیده بودیمش، یعنی
     * حلقه پیدا شده و به‌جای ادامه‌ی بی‌نهایت، متوقف می‌شیم. [maxElseChainDepth]
     * هم یک سقفِ مطلقِ اضافه‌ست، برای زنجیره‌های خیلی طولانیِ غیرحلقوی هم.
     *
     * باگ رفع‌شده (فاز ۲): قبلاً rule.actionType (CLICK/LONG_PRESS) اصلاً
     * خونده نمی‌شد و همیشه performClick صدا زده می‌شد. حالا actionType
     * واقعاً اعمال می‌شه (این بخش بدون تغییر نسبت به قبل مونده).
     */
    fun attemptClickRule(
        rule: ClickRule,
        allRules: Map<String, ClickRule> = emptyMap(),
        onFinished: (() -> Unit)? = null,
        visited: MutableSet<String> = mutableSetOf()
    ) {
        if (!visited.add(rule.id) || visited.size > maxElseChainDepth) {
            Log.w(TAG, "زنجیره‌ی شرطی متوقف شد: حلقه یا عمقِ بیش‌ازحد در elseRuleId (قانون «${rule.label}»)")
            onFinished?.invoke()
            return
        }
        if (!busyRuleIds.add(rule.id)) {
            // یعنی این قانون از قبل مشغول انتظار/تاخیره — این دور رو رد می‌کنیم
            onFinished?.invoke()
            return
        }
        matchRule(rule) { point ->
            // فاز ۱۵: حتی وقتی matchType عادی «دیده شد» بگه، اگه شرطِ
            // شمارنده تنظیم شده باشه و برقرار نباشه، رفتار دقیقاً مثل
            // «دیده نشد» می‌شه (می‌ره سراغ elseRuleId، مثل بقیه‌ی حالت‌ها).
            val effectivePoint = if (point != null && counterConditionPasses(rule)) point else null
            if (effectivePoint != null) {
                performAction(rule, effectivePoint.x, effectivePoint.y)
                afterAction(rule) {
                    busyRuleIds.remove(rule.id)
                    onFinished?.invoke()
                }
            } else {
                busyRuleIds.remove(rule.id)
                val elseRule = rule.elseRuleId?.let { id -> if (id != rule.id) allRules[id] else null }
                if (elseRule != null) {
                    attemptClickRule(elseRule, allRules, onFinished, visited)
                } else {
                    onFinished?.invoke()
                }
            }
        }
    }

    /**
     * فاز ۱۵ — شرطِ شمارنده: یک AND منطقیِ اضافه روی تشخیصِ عادیِ matchType،
     * نه جایگزینش. اگه counterConditionName تنظیم نشده باشه (پیش‌فرضِ همه‌ی
     * قوانینِ قدیمی‌تر)، همیشه true برمی‌گرده — یعنی رفتارِ قبلی دست‌نخورده.
     */
    private fun counterConditionPasses(rule: ClickRule): Boolean {
        val name = rule.counterConditionName ?: return true
        val current = CounterStore.get(this, name)
        return rule.counterConditionComparison.apply(current, rule.counterConditionValue)
    }

    /**
     * هسته‌ی تشخیص، جدا از اجرای عمل: بر اساس matchType، یا بلافاصله
     * (COORDINATE/TEXT/VIEW_ID — از UI Tree، سریع و روی همین ترد) یا با یک
     * تاخیر روی ترد پس‌زمینه (IMAGE/PIXEL_COLOR/OCR_TEXT — چون فریمِ صفحه رو
     * پردازش می‌کنن) [callback] رو با نقطه‌ی کلیک (یا null اگه دیده نشد)
     * روی ترد اصلی صدا می‌زنه. این تابع فقط تشخیص می‌ده، هیچ کلیکی نمی‌زنه —
     * برای همینه که هم در attemptClickRule (برای کلیک واقعی) و هم در
     * isRuleTargetPresent (فقط برای چک «آیا هنوز هست»، در waitForDisappear)
     * قابل استفاده‌ی مشترکه.
     */
    private fun matchRule(rule: ClickRule, callback: (PointF?) -> Unit) {
        when (rule.matchType) {
            MatchType.COORDINATE -> {
                val parts = rule.matchValue.split(",")
                val x = parts.getOrNull(0)?.trim()?.toFloatOrNull()
                val y = parts.getOrNull(1)?.trim()?.toFloatOrNull()
                callback(if (x != null && y != null) PointF(x, y) else null)
            }
            MatchType.TEXT -> {
                val root = rootInActiveWindow
                val matches = root?.findAccessibilityNodeInfosByText(rule.matchValue) ?: emptyList()
                val node = matches.firstOrNull { it.isVisibleToUser }
                val point = node?.let { centerOf(it) }
                recycleNodesIfNeeded(matches, root)
                callback(point)
            }
            MatchType.VIEW_ID -> {
                val root = rootInActiveWindow
                val matches = root?.findAccessibilityNodeInfosByViewId(rule.matchValue) ?: emptyList()
                val node = matches.firstOrNull { it.isVisibleToUser }
                val point = node?.let { centerOf(it) }
                recycleNodesIfNeeded(matches, root)
                callback(point)
            }
            MatchType.IMAGE -> imageMatchExecutor.execute {
                val frame = ScreenCaptureService.latestFrame
                val point = if (frame != null) findImageMatchPoint(rule, frame) else {
                    Log.w(TAG, "سرویس ضبط صفحه فعال نیست یا هنوز فریمی نگرفته")
                    null
                }
                mainHandler.post { callback(point) }
            }
            MatchType.PIXEL_COLOR -> imageMatchExecutor.execute {
                val frame = ScreenCaptureService.latestFrame
                val point = if (frame != null) findPixelColorPoint(rule, frame) else null
                mainHandler.post { callback(point) }
            }
            MatchType.OCR_TEXT -> imageMatchExecutor.execute {
                val frame = ScreenCaptureService.latestFrame
                val point = if (frame != null) findOcrPoint(rule, frame) else null
                mainHandler.post { callback(point) }
            }
        }
    }

    /** فقط چک می‌کنه که آیا هدف قانون الان روی صفحه هست، بدون هیچ کلیکی — برای waitForDisappear. */
    private fun isRuleTargetPresent(rule: ClickRule, callback: (Boolean) -> Unit) {
        matchRule(rule) { point -> callback(point != null) }
    }

    /**
     * فاز ۹ — «منتظر بمون تا المان/الگو ناپدید بشه»: هر [pollMs] یک‌بار
     * (حداقل ۵۰ میلی‌ثانیه، تا لوپ خیلی فشرده نشه) دوباره چک می‌کنه؛ به‌محض
     * ناپدید شدن یا رسیدن به [timeoutMs] سقف، [onDone] صدا زده می‌شه.
     */
    private fun waitForDisappear(rule: ClickRule, timeoutMs: Long, pollMs: Long, onDone: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        val interval = pollMs.coerceAtLeast(50L)
        fun poll() {
            isRuleTargetPresent(rule) { present ->
                if (!present || System.currentTimeMillis() >= deadline) {
                    onDone()
                } else {
                    mainHandler.postDelayed({ poll() }, interval)
                }
            }
        }
        mainHandler.postDelayed({ poll() }, interval)
    }

    /**
     * فاز ۹ — تاخیر تصادفی بین کلیک‌ها: یک عدد صحیح تصادفیِ یکنواخت بین
     * postClickDelayMinMs و postClickDelayMaxMs. اگه max <= min (از جمله
     * حالت پیش‌فرض ۰/۰)، هیچ رندومی در کار نیست و همون مقدار ثابت
     * (احتمالاً صفر) برمی‌گرده — یعنی رفتار قوانین قدیمی‌تر دست‌نخورده می‌مونه.
     */
    private val delayRng = java.util.Random()
    private fun randomDelayFor(rule: ClickRule): Long {
        val min = rule.postClickDelayMinMs.coerceAtLeast(0L)
        val max = rule.postClickDelayMaxMs.coerceAtLeast(min)
        if (max <= min) return min
        return min + (delayRng.nextDouble() * (max - min)).toLong()
    }

    /** بعد از performAction: در صورت نیاز صبر برای ناپدیدی، بعد تاخیر تصادفی، بعد [onFinished]. */
    private fun afterAction(rule: ClickRule, onFinished: () -> Unit) {
        val proceed: () -> Unit = {
            val extra = randomDelayFor(rule)
            if (extra > 0) mainHandler.postDelayed(onFinished, extra) else onFinished()
        }
        if (rule.waitForDisappearAfterClick) {
            waitForDisappear(rule, rule.waitForDisappearTimeoutMs, rule.waitForDisappearPollMs, proceed)
        } else {
            proceed()
        }
    }

    /**
     * فاز ۶: مبدا (x, y) همیشه از روی matchType تعیین می‌شه (مختصات ثابت،
     * مرکز المان UI Tree، یا مرکز تطابق تصویر). برای SWIPE، مقصد از
     * rule.swipeEndValue خونده می‌شه؛ اگه غایب/نامعتبر باشه، هیچ ژستی اجرا
     * نمی‌شه (فقط لاگ) تا یک سوایپ ناقص/تصادفی رخ نده.
     */
    private fun performAction(rule: ClickRule, x: Float, y: Float) {
        // فاز ۸: هر اجرا (چه واقعی چه آزمایشی) رو در تاریخچه ثبت می‌کنیم — یک
        // نقطه‌ی مشترک برای همه‌ی مسیرها (COORDINATE/TEXT/VIEW_ID/IMAGE).
        RunLogStore.add(
            this,
            RunLogEntry(
                timestampMs = System.currentTimeMillis(),
                ruleLabel = rule.label,
                matchType = rule.matchType,
                actionType = rule.actionType,
                x = x,
                y = y,
                dryRun = dryRunEnabled
            )
        )

        if (dryRunEnabled) {
            // هیچ ژستی dispatch نمی‌شه — فقط گزارش می‌دیم که این قانون الان
            // «می‌خواست» چیکار کنه. برای دیباگ قوانین جدید بدون ریسک کلیک اشتباه.
            Log.i(TAG, "[آزمایشی] «${rule.label}» → ${rule.actionType} در (${x.toInt()}, ${y.toInt()})")
            dryRunListener?.invoke(rule, x, y)
            return
        }
        when (rule.actionType) {
            ActionType.CLICK -> performClick(x, y)
            ActionType.LONG_PRESS -> performLongPress(x, y)
            ActionType.SWIPE -> {
                val parts = rule.swipeEndValue?.split(",")
                val ex = parts?.getOrNull(0)?.trim()?.toFloatOrNull()
                val ey = parts?.getOrNull(1)?.trim()?.toFloatOrNull()
                if (ex != null && ey != null) {
                    performSwipe(x, y, ex, ey, rule.swipeDurationMs)
                } else {
                    Log.w(TAG, "قانون «${rule.label}» از نوع سوایپ است ولی نقطه‌ی مقصد معتبر ندارد")
                }
            }
            // فاز ۱۵: این چهارتا هیچ ژستی dispatch نمی‌کنن — x/y همون نقطه‌ای
            // که matchType پیدا کرده رو نگه می‌داره (فقط برای لاگ)، ولی اکشنِ
            // واقعی یک تغییرِ شمارنده یا یک Intent اندرویدیه.
            ActionType.INCREMENT_COUNTER -> a
