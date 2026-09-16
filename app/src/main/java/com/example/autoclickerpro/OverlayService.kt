package com.example.autoclickerpro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * فاز ۲:
 * این سرویس یک دکمه شناور کوچک نشون می‌ده که کاربر می‌تونه:
 *  - آن را جابه‌جا کند (درگ)
 *  - با تپ روی آن، بین دو حالت انتخاب کند:
 *      ۱. تشخیص المان: لیستی از المان‌های قابل‌کلیکِ صفحه‌ی فعلی (خونده‌شده
 *         از UI Tree توسط AccessibilityService) نشون داده می‌شه؛ با انتخاب
 *         یکی، یک "قانون" بر اساس متن یا شناسه‌ی همون المان ساخته می‌شه.
 *      ۲. نقطه‌ی ثابت: روش قدیمی فاز ۱ (کلیک روی مختصات ثابت، بدون تشخیص).
 *  - با نگه‌داشتن (long press) حلقه‌ی بررسی/کلیک روی همه‌ی قوانین فعال را
 *    شروع/متوقف کند. در هر تکرار حلقه، هر قانون فقط وقتی واقعاً روی صفحه
 *    دیده بشه کلیک می‌خوره (attemptClickRule در ClickAccessibilityService).
 *
 * قوانین در RuleStore نگه‌داری و در SharedPreferences ماندگار می‌شن.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager

    // فاز ۱۴: چون OverlayService خودش یک Service‌ه نه Activity، تمِ اپ
    // (Theme.AutoClickerPro) به‌طور خودکار روش اعمال نمی‌شه — یعنی هر View
    // یا Dialog که مستقیم با `this` به‌عنوان context ساخته می‌شد، رنگ/گردیِ
    // Material رو نمی‌گرفت (برای همینه که دیالوگ‌ها و دکمه‌های شناور قبلاً
    // «خام» به‌نظر می‌رسیدن). این یک ContextThemeWrapper هست که همون تم رو
    // دستی روی این context سوار می‌کنه؛ همه‌ی Viewها/Dialogهایی که باید
    // ظاهرِ Material داشته باشن، به‌جای `this`، از همین استفاده می‌کنن.
    private val themedContext by lazy { ContextThemeWrapper(this, R.style.Theme_AutoClickerPro) }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /**
     * فاز ۱۴: دکمه‌ی شناورِ کوچکِ گوشه‌ی بالا-راستِ صفحه (پایانِ ضبط/انتخابِ
     * نقطه، بستنِ پیش‌نمایش و...). قبلاً هرکدوم از این سه‌تا یک
     * android.widget.Button خام با setBackgroundColor تخت می‌ساختن — که
     * چون روی MaterialButton هم اعمال بشه، شکلِ گردگوشه‌ی پیش‌فرضش رو کاملاً
     * از بین می‌بره. این نسخه یک MaterialButton واقعی می‌سازه و رنگ رو با
     * backgroundTintList (نه setBackgroundColor) اعمال می‌کنه تا shapeِ
     * گردِ Material دست‌نخورده بمونه.
     */
    private fun addCornerActionButton(text: String, tintColor: Int, onClick: () -> Unit): MaterialButton {
        val btn = MaterialButton(themedContext).apply {
            this.text = text
            backgroundTintList = ColorStateList.valueOf(tintColor)
            setTextColor(Color.WHITE)
            cornerRadius = dpToPx(14)
            setOnClickListener { onClick() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 24
        params.y = 120
        windowManager.addView(btn, params)
        return btn
    }

    private var floatingView: View? = null
    private var pickerOverlayView: View? = null
    private var elementPickerView: View? = null
    private var imageSelectOverlay: View? = null

    // فاز ۵: ضبط حرکات لمسی
    private var recorderOverlayView: View? = null
    private var stopRecordingButton: View? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    private var activeRecording: Recording? = null
    private var currentStroke: RecordedStroke? = null

    // فاز ۱۱: «حالت ساده» — اوورلیِ گرفتنِ چند نقطه با تپ (شبیه ضبط لمسی
    // بالا، ولی به‌جای ذخیره‌ی کل مسیر لمس، فقط مختصاتِ هر تپ رو نگه می‌داره)
    // + حلقه‌ی جداگانه‌ای که مستقل از حلقه‌ی قوانین (clickLoopRunnable)،
    // به‌ترتیب و با یک فاصله‌ی زمانیِ ثابت روی نقاط کلیک می‌کنه.
    private var simplePickerOverlay: View? = null
    private var simplePickerStopButton: Button? = null
    private var simplePickerCount = 0
    private var isSimpleModeRunning = false
    private var simpleModeIndex = 0

    // فاز ۱۲: پیش‌نمایشِ نقاط (اوورلیِ غیرقابل‌لمس با مارکرهای شماره‌دار)
    private var pointsPreviewOverlay: View? = null
    private var pointsPreviewCloseButton: View? = null
    private val hidePointsPreviewRunnable = Runnable { hidePointsPreviewOverlay() }

    private val handler = Handler(Looper.getMainLooper())
    private var isLoopRunning = false

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    companion object {
        const val NOTIF_CHANNEL_ID = "autoclicker_overlay_channel"
        const val NOTIF_ID = 1001

        // فاز ۱۱ — اکشن‌های Intent برای «حالت ساده». چون OverlayService ممکنه
        // از قبل در حال اجرا باشه (کاربر قبلاً از منوی اصلی «شروع» رو زده)، به‌جای
        // ساختن یک راه ارتباطی جدید، از همون startForegroundService با یک
        // Intent دارای action استفاده می‌کنیم — چه سرویس تازه ساخته بشه (onCreate)
        // چه از قبل زنده باشه، در هر دو حالت onStartCommand این action رو
        // می‌گیره و پردازش می‌کنه.
        const val ACTION_START_SIMPLE_POINT_PICKING = "com.example.autoclickerpro.action.START_SIMPLE_POINT_PICKING"
        const val ACTION_START_SIMPLE_LOOP = "com.example.autoclickerpro.action.START_SIMPLE_LOOP"
        const val ACTION_STOP_SIMPLE_LOOP = "com.example.autoclickerpro.action.STOP_SIMPLE_LOOP"
        // فاز ۱۲: نمایشِ مارکرهای شماره‌دار روی نقاطِ ثبت‌شده، بدون کلیک واقعی.
        const val ACTION_PREVIEW_POINTS = "com.example.autoclickerpro.action.PREVIEW_POINTS"
        private const val PREVIEW_AUTO_HIDE_MS = 8000L
        // فاز ۱۳ (رفع باگ): آستانه‌ی تشخیصِ دستیِ long-press روی دکمه‌ی شناور.
        private const val LONG_PRESS_THRESHOLD_MS = 500L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingButton()

        // فاز ۷: وقتی حالت آزمایشی روشنه، به‌جای کلیک واقعی یک Toast نشون بده
        ClickAccessibilityService.dryRunListener = { rule, x, y ->
            handler.post {
                Toast.makeText(
                    this,
                    "🧪 «${rule.label}» می‌خواست ${rule.actionType} بزنه در (${x.toInt()}, ${y.toInt()})",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * فاز ۱۱: پردازشِ اکشن‌های «حالت ساده». چون این متد چه سرویس تازه ساخته
     * شده باشه (بلافاصله بعد از onCreate) چه از قبل زنده بوده باشه صدا زده
     * می‌شه، برای هر دو حالت (سرویس سرد/گرم) درست کار می‌کنه.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SIMPLE_POINT_PICKING -> showSimplePointPickerOverlay()
            ACTION_START_SIMPLE_LOOP -> startSimpleModeLoop()
            ACTION_STOP_SIMPLE_LOOP -> stopSimpleModeLoop()
            ACTION_PREVIEW_POINTS -> showPointsPreviewOverlay()
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "AutoClicker Pro",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("AutoClicker Pro در حال اجراست")
            .setContentText("دکمه شناور روی صفحه فعال است")
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    // ---------- دکمه شناور اصلی ----------

    private fun showFloatingButton() {
        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.overlay_button, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 300

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var pressStartTime = 0L

        floatingView?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    pressStartTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) {
                        isDragging = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // باگ رفع‌شده (بحرانی): چون همین OnTouchListener برای هر
                    // ایوینت (از جمله ACTION_DOWN) مقدار true برمی‌گردونه،
                    // View.onTouchEvent() اصلاً اجرا نمی‌شه — و دقیقاً همون
                    // جاییه که مکانیزم استاندارد تشخیصِ long-press اندروید
                    // زندگی می‌کنه. یعنی setOnLongClickListener که قبلاً اینجا
                    // بود، *هیچ‌وقت* صدا زده نمی‌شد؛ نتیجه‌ش این بود که تنها
                    // راهِ روشن‌کردنِ حلقه‌ی کلیک (toggleClickLoop) از کار
                    // افتاده بود، در حالی که تپِ ساده (که مستقیم از همین‌جا،
                    // نه از مسیر استاندارد کلیک، صدا زده می‌شه) درست کار
                    // می‌کرد — دقیقاً همون چیزی که به نظر می‌رسید «همه‌جا
                    // اطلاعات جمع می‌شه ولی هیچ کلیکی اجرا نمی‌شه».
                    // راه‌حل: تشخیصِ long-press رو خودمون دستی، از روی فاصله‌ی
                    // زمانیِ ACTION_DOWN تا ACTION_UP، انجام می‌دیم.
                    val pressDuration = System.currentTimeMillis() - pressStartTime
                    if (!isDragging) {
                        if (pressDuration >= LONG_PRESS_THRESHOLD_MS) {
                            toggleClickLoop()
                        } else {
                            onFloatingButtonTapped()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingView, params)
    }

    /** با تپ روی دکمه شناور، از کاربر می‌پرسیم چه نوع قانونی می‌خواد بسازه */
    private fun onFloatingButtonTapped() {
        val dryRunLabel = if (ClickAccessibilityService.dryRunEnabled)
            "🧪 حالت آزمایشی: روشن (بزن خاموش کن)"
        else
            "🧪 حالت آزمایشی: خاموش (بزن روشن کن)"

        val dialog = MaterialAlertDialogBuilder(themedContext)
            .setTitle("افزودن قانون جدید")
            .setItems(
                arrayOf(
                    "🎯 تشخیص المان (توصیه می‌شود)",
                    "🖼 تشخیص تصویری با OpenCV (وقتی UI Tree جواب نمی‌ده)",
                    "➕ افزودن الگوی تصویری دیگر به یک قانون تصویری (اولویت)",
                    "🎨 رنگ پیکسل در یک نقطه‌ی خاص",
                    "🔤 OCR: کلیک وقتی متنی خاص دیده شد",
                    "📍 ثبت نقطه‌ی ثابت روی صفحه",
                    "➡️ سوایپ بین دو نقطه‌ی ثابت",
                    "⏺ ضبط حرکات لمسی (دقیقاً مثل خودم)",
                    "▶ پخش یک ضبط ذخیره‌شده",
                    dryRunLabel
                )
            ) { _, which ->
                when (which) {
                    0 -> showElementPickerOverlay()
                    1 -> showImageTemplateSelectorOverlay()
                    2 -> showAddImageTemplateFlow()
                    3 -> showPixelColorPickerOverlay()
                    4 -> showOcrRuleCreatorOverlay()
                    5 -> showPointPickerOverlay()
                    6 -> showSwipePickerOverlay()
                    7 -> startTouchRecording()
                    8 -> showPlayRecordingDialog()
                    else -> toggleDryRun()
                }
            }
            .setNegativeButton("انصراف", null)
            .create()
        dialog.window?.setType(overlayWindowType())
        dialog.show()
    }

    /**
     * فاز ۷: حالت آزمایشی. وقتی روشنه، حلقه‌ی کلیک عیناً مثل قبل همه‌ی قوانین
     * فعال رو چک می‌کنه، ولی به‌جای dispatchGesture واقعی، فقط یک Toast نشون
     * می‌ده که کدوم قانون کجا می‌خواست کلیک بزنه — برای تست امن قوانین جدید
     * قبل از فعال کردنشون روی یک اپ/بازی واقعی.
     */
    private fun toggleDryRun() {
        ClickAccessibilityService.dryRunEnabled = !ClickAccessibilityService.dryRunEnabled
        // نشونه‌ی بصری روی خودِ دکمه‌ی شناور تا کاربر یادش نره حالت آزمایشیه روشنه
        (floatingView as? android.widget.TextView)?.text =
            if (ClickAccessibilityService.dryRunEnabled) "🧪" else "+"
        val msg = if (ClickAccessibilityService.dryRunEnabled)
            "🧪 حالت آزمایشی روشن شد — هیچ کلیک واقعی‌ای انجام نمی‌شه"
        else
            "حالت آزمایشی خاموش شد — کلیک‌ها از این به بعد واقعی‌ان"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------- لایه‌ی شفاف برای انتخاب نقطه با تپ (روش فاز ۱) ----------

    private fun showPointPickerOverlay() {
        if (pickerOverlayView != null) return // از قبل باز است

        val overlay = View(this)
        overlay.setBackgroundColor(0x22000000) // پس‌زمینه‌ی نیمه‌شفاف تا کاربر بفهمه در حالت انتخابه

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        overlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val rule = ClickRule(
                    id = UUID.randomUUID().toString(),
                    matchType = MatchType.COORDINATE,
                    matchValue = "${event.rawX},${event.rawY}",
                    label = "نقطه (${event.rawX.toInt()}, ${event.rawY.toInt()})"
                )
                RuleStore.add(this, rule)
                Toast.makeText(this, "قانون مختصات اضافه شد", Toast.LENGTH_SHORT).show()
                removePointPickerOverlay()
            }
            true
        }

        windowManager.addView(overlay, params)
        pickerOverlayView = overlay
    }

    private fun removePointPickerOverlay() {
        pickerOverlayView?.let {
            runCatching { windowManager.removeView(it) }
            pickerOverlayView = null
        }
    }

    // ---------- فاز ۶: انتخاب دو نقطه (مبدا و مقصد) برای قانون SWIPE ----------

    /**
     * دو تپ پشت‌سرهم می‌گیره: اولی نقطه‌ی مبدا (matchValue، مثل COORDINATE
     * معمولی)، دومی نقطه‌ی مقصد (swipeEndValue). بعد یک قانون COORDINATE با
     * actionType = SWIPE می‌سازه.
     */
    private fun showSwipePickerOverlay() {
        if (pickerOverlayView != null) return

        val overlay = View(this)
        overlay.setBackgroundColor(0x2200FF00) // ته‌رنگ سبز، برای تمایز از انتخاب نقطه‌ی ساده (آبی/خاکستری)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        var startX: Float? = null
        var startY: Float? = null

        Toast.makeText(this, "نقطه‌ی مبدأ سوایپ رو لمس کن", Toast.LENGTH_SHORT).show()

        overlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (startX == null) {
                    startX = event.rawX
                    startY = event.rawY
                    Toast.makeText(this, "حالا نقطه‌ی مقصد سوایپ رو لمس کن", Toast.LENGTH_SHORT).show()
                } else {
                    val sx = startX!!
                    val sy = startY!!
                    val rule = ClickRule(
                        id = UUID.randomUUID().toString(),
                        matchType = MatchType.COORDINATE,
                        matchValue = "${sx},${sy}",
                        actionType = ActionType.SWIPE,
                        label = "سوایپ (${sx.toInt()},${sy.toInt()}) ← (${event.rawX.toInt()},${event.rawY.toInt()})",
                        swipeEndValue = "${event.rawX},${event.rawY}"
                    )
                    RuleStore.add(this, rule)
                    Toast.makeText(this, "قانون سوایپ اضافه شد", Toast.LENGTH_SHORT).show()
                    removePointPickerOverlay()
                }
            }
            true
        }

        windowManager.addView(overlay, params)
        pickerOverlayView = overlay
    }

    // ---------- لیست شناور المان‌های قابل‌کلیک صفحه‌ی فعلی (فاز ۲) ----------

    private fun showElementPickerOverlay() {
        if (elementPickerView != null) return

        val svc = ClickAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, "سرویس Accessibility فعال نیست", Toast.LENGTH_SHORT).show()
            return
        }

        val elements = svc.listClickableElements()
        if (elements.isEmpty()) {
            Toast.makeText(this, "هیچ المان قابل‌کلیکی در صفحه‌ی فعلی پیدا نشد", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LayoutInflater.from(themedContext).inflate(R.layout.overlay_element_list, null)
        val listView = container.findViewById<ListView>(R.id.listElements)
        val btnCancel = container.findViewById<Button>(R.id.btnCancelPicker)

        // باگ رفع‌شده (فاز ۱۴): android.R.layout.simple_list_item_1 رنگِ
        // متنش رو از ?android:attr/textColorPrimary زمینه‌ای که بهش داده
        // می‌شه می‌گیره. چون این ArrayAdapter قبلاً با `this` (کانتکستِ
        // بی‌تمِ Service) ساخته می‌شد، متن معمولاً مشکی می‌شد — روی
        // پس‌زمینه‌ی تیرهٔ قبلیِ این اوورلی عملاً غیرقابل‌خوندن بود. حالا با
        // themedContext، رنگ از colorOnSurface‌ِ خودِ اپ می‌آد.
        listView.adapter = ArrayAdapter(
            themedContext,
            android.R.layout.simple_list_item_1,
            elements.map { it.label }
        )
        listView.setOnItemClickListener { _, _, position, _ ->
            val el = elements[position]
            val rule = ClickRule(
                id = UUID.randomUUID().toString(),
                matchType = el.matchType,
                matchValue = el.matchValue,
                label = el.label
            )
            RuleStore.add(this, rule)
            Toast.makeText(this, "قانون اضافه شد: ${el.label}", Toast.LENGTH_SHORT).show()
            removeElementPickerOverlay()
        }
        btnCancel.setOnClickListener { removeElementPickerOverlay() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.6).toInt(),
            overlayWindowType(),
            0, // focusable لازم است تا لمس روی آیتم‌های لیست به‌درستی کار کند
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM

        windowManager.addView(container, params)
        elementPickerView = container
    }

    private fun removeElementPickerOverlay() {
        elementPickerView?.let {
            runCatching { windowManager.removeView(it) }
            elementPickerView = null
        }
    }

    // ---------- انتخاب ناحیه‌ی تصویر الگو برای تشخیص با OpenCV (فاز ۳) ----------

    /**
     * قبل از هر چیز باید یک فریم از صفحه‌ی فعلی داشته باشیم (از
     * ScreenCaptureService)، تا کاربر بتونه رویش یک ناحیه رو به‌عنوان الگو
     * انتخاب کنه. اگه سرویس ضبط صفحه فعال نباشه یا هنوز فریمی نگرفته باشه،
     * از کاربر می‌خوایم اول از اپ اصلی "ضبط صفحه" رو فعال کنه.
     */
    private fun showImageTemplateSelectorOverlay() {
        if (imageSelectOverlay != null) return

        val frame = ScreenCaptureService.instance?.let { ScreenCaptureService.latestFrame }
        if (frame == null) {
            Toast.makeText(
                this,
                "اول باید «ضبط صفحه» رو از اپ اصلی فعال کنی (لازم برای تشخیص تصویری)",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        Toast.makeText(this, "با کشیدن انگشت، دور دکمه/آیکونی که می‌خوای بکش", Toast.LENGTH_LONG).show()

        val selectionView = RectSelectionView(this) { rect ->
            removeImageSelectOverlay()
            onImageRegionSelected(frame, rect)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        windowManager.addView(selectionView, params)
        imageSelectOverlay = selectionView
    }

    private fun removeImageSelectOverlay() {
        imageSelectOverlay?.let {
            runCatching { windowManager.removeView(it) }
            imageSelectOverlay = null
        }
    }

    /** بعد از اینکه کاربر یک ناحیه رو کشید، اون ناحیه رو از فریم کراپ و به‌عنوان قانون IMAGE ذخیره می‌کنیم. */
    private fun onImageRegionSelected(frame: Bitmap, rect: RectF) {
        val left = max(0, rect.left.toInt())
        val top = max(0, rect.top.toInt())
        val right = min(frame.width, rect.right.toInt())
        val bottom = min(frame.height, rect.bottom.toInt())
        val width = right - left
        val height = bottom - top

        if (width < 16 || height < 16) {
            Toast.makeText(this, "ناحیه‌ی انتخاب‌شده خیلی کوچیکه، دوباره امتحان کن", Toast.LENGTH_SHORT).show()
            return
        }

        val cropped = Bitmap.createBitmap(frame, left, top, width, height)
        val path = TemplateStore.saveTemplate(this, cropped)

        val rule = ClickRule(
            id = UUID.randomUUID().toString(),
            matchType = MatchType.IMAGE,
            matchValue = path,
            label = "الگوی تصویری (${width}x${height})"
        )
        RuleStore.add(this, rule)
        Toast.makeText(this, "قانون تصویری اضافه شد", Toast.LENGTH_SHORT).show()
    }

    // ---------- فاز ۹: افزودن الگوی تصویری دیگر با اولویت، به یک قانون تصویری موجود ----------

    /** لیست قوانین تصویری موجود رو نشون می‌ده تا کاربر یکی رو برای افزودن الگوی جدید انتخاب کنه. */
    private fun showAddImageTemplateFlow() {
        val imageRules = RuleStore.getAll(this).filter { it.matchType == MatchType.IMAGE }
        if (imageRules.isEmpty()) {
            Toast.makeText(this, "هنوز هیچ قانون تصویری‌ای نساختی — اول یکی با «تشخیص تصویری» بساز", Toast.LENGTH_LONG).show()
            return
        }
        val labels = imageRules.map { it.describe() }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(themedContext)
            .setTitle("افزودن الگوی جدید به کدوم قانون؟ (اولویتش پایین‌تر از الگوهای قبلیِ همون قانونه)")
            .setItems(labels) { _, which -> startExtraTemplateCapture(imageRules[which]) }
            .setNegativeButton("انصراف", null)
            .create()
        dialog.window?.setType(overlayWindowType())
        dialog.show()
    }

    private fun startExtraTemplateCapture(targetRule: ClickRule) {
        if (imageSelectOverlay != null) return
        val frame = ScreenCaptureService.instance?.let { ScreenCaptureService.latestFrame }
        if (frame == null) {
            Toast.makeText(this, "اول باید «ضبط صفحه» رو از اپ اصلی فعال کنی", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "دور الگوی تصویری جدید (پایین‌تر از الگوهای قبلی همین قانون) بکش", Toast.LENGTH_LONG).show()

        val selectionView = RectSelectionView(this) { rect ->
            removeImageSelectOverlay()
            onExtraTemplateRegionSelected(targetRule, frame, rect)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(selectionView, params)
        imageSelectOverlay = selectionView
    }

    private fun onExtraTemplateRegionSelected(targetRule: ClickRule, frame: Bitmap, rect: RectF) {
        val left = max(0, rect.left.toInt())
        val top = max(0, rect.top.toInt())
        val right = min(frame.width, rect.right.toInt())
        val bottom = min(frame.height, rect.bottom.toInt())
        val width = right - left
        val height = bottom - top
        if (width < 16 || height < 16) {
            Toast.makeText(this, "ناحیه‌ی انتخاب‌شده خیلی کوچیکه، دوباره امتحان کن", Toast.LENGTH_SHORT).show()
            return
        }
        val cropped = Bitmap.createBitmap(frame, left, top, width, height)
        val newPath = TemplateStore.saveTemplate(this, cropped)

        // آخرین نسخه‌ی قانون رو دوباره می‌خونیم (نه targetRule‌ی قدیمی که ممکنه
        // بین باز شدن منو و کشیدن مستطیل، جای دیگه‌ای تغییر کرده باشه).
        val latest = RuleStore.getAll(this).find { it.id == targetRule.id } ?: targetRule
        val newList = if (latest.imageTemplatePaths.isEmpty())
            listOf(latest.matchValue, newPath)
        else
            latest.imageTemplatePaths + newPath
        val updated = latest.copy(imageTemplatePaths = newList)
        RuleStore.update(this, updated)
        Toast.makeText(this, "الگوی جدید با اولویت ${newList.size} اضافه شد", Toast.LENGTH_SHORT).show()
    }

    // ---------- فاز ۹: تشخیص رنگ پیکسل در یک نقطه‌ی خاص ----------

    private fun showPixelColorPickerOverlay() {
        if (pickerOverlayView != null) return
        val frame = ScreenCaptureService.instance?.let { ScreenCaptureService.latestFrame }
        if (frame == null) {
            Toast.makeText(this, "اول باید «ضبط صفحه» رو از اپ اصلی فعال کنی (لازم برای خوندن رنگ پیکسل)", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "روی نقطه‌ای که می‌خوای رنگش رو زیر نظر بگیری تپ کن", Toast.LENGTH_LONG).show()

        val overlay = View(this)
        overlay.setBackgroundColor(0x2200BCD4) // ته‌رنگ فیروزه‌ای، برای تمایز از انتخاب‌گرهای دیگه

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        overlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.rawX.toInt().coerceIn(0, frame.width - 1)
                val y = event.rawY.toInt().coerceIn(0, frame.height - 1)
                val pixel = frame.getPixel(x, y)
                val colorHex = String.format("#%06X", 0xFFFFFF and pixel)
                val rule = ClickRule(
                    id = UUID.randomUUID().toString(),
                    matchType = MatchType.PIXEL_COLOR,
                    matchValue = "$x,$y",
                    targetColorHex = colorHex,
                    label = "رنگ پیکسل ($x,$y)=$colorHex"
                )
                RuleStore.add(this, rule)
                Toast.makeText(this, "قانون رنگ پیکسل اضافه شد ($colorHex) — تحمل رنگ پیش‌فرض ۱۲ واحده", Toast.LENGTH_LONG).show()
                removePointPickerOverlay()
            }
            true
        }

        windowManager.addView(overlay, params)
        pickerOverlayView = overlay
    }

    // ---------- فاز ۹: OCR — کلیک وقتی متنی خاص روی صفحه دیده شد ----------

    private fun showOcrRuleCreatorOverlay() {
        if (imageSelectOverlay != null) return
        val frame = ScreenCaptureService.instance?.let { ScreenCaptureService.latestFrame }
        if (frame == null) {
            Toast.makeText(this, "اول باید «ضبط صفحه» رو از اپ اصلی فعال کنی (لازم برای OCR)", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "دور ناحیه‌ای که متن موردنظر (مثلاً امتیاز) توشه بکش", Toast.LENGTH_LONG).show()

        val selectionView = RectSelectionView(this) { rect ->
            removeImageSelectOverlay()
            onOcrRegionSelected(rect)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(selectionView, params)
        imageSelectOverlay = selectionView
    }

    private fun onOcrRegionSelected(rect: RectF) {
        if (rect.width() < 8 || rect.height() < 8) {
            Toast.makeText(this, "ناحیه‌ی انتخاب‌شده خیلی کوچیکه، دوباره امتحان کن", Toast.LENGTH_SHORT).show()
            return
        }
        val region = "${rect.left.toInt()},${rect.top.toInt()},${rect.right.toInt()},${rect.bottom.toInt()}"

        val input = EditText(themedContext).apply {
            hint = "مثلاً: پیروزی  یا  Game Over  یا یک عدد خاص"
        }
        val dialog = MaterialAlertDialogBuilder(themedContext)
            .setTitle("دنبال چه متنی توی این ناحیه بگردیم؟")
            .setView(input)
            .setPositiveButton("ثبت") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "متن نمی‌تونه خالی باشه", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val rule = ClickRule(
                    id = UUID.randomUUID().toString(),
                    matchType = MatchType.OCR_TEXT,
                    matchValue = text,
                    ocrRegion = region,
                    label = "OCR «$text»"
                )
                RuleStore.add(this, rule)
                Toast.makeText(
                    this,
                    "قانون OCR اضافه شد — کلیک پیش‌فرض روی مرکز همین ناحیه‌ست؛ برای نقطه‌ی کلیکِ متفاوت از «⚙ تنظیمات پیشرفته» در «مدیریت قوانین کلیک» استفاده کن",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("انصراف", null)
            .create()
        dialog.window?.setType(overlayWindowType())
        dialog.show()
    }

    /**
     * یک View شفاف تمام‌صفحه که با کشیدن انگشت یک مستطیل رسم می‌کنه و در
     * پایان (ACTION_UP) مستطیل نهایی رو به [onSelected] پاس می‌ده.
     */
    private class RectSelectionView(
        context: Context,
        private val onSelected: (RectF) -> Unit
    ) : View(context) {

        private val dimPaint = Paint().apply { color = Color.argb(90, 0, 0, 0) }
        private val borderPaint = Paint().apply {
            color = Color.parseColor("#FF4081")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val fillPaint = Paint().apply { color = Color.argb(60, 255, 64, 129) }

        private var startX = 0f
        private var startY = 0f
        private val currentRect = RectF()
        private var dragging = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    currentRect.set(startX, startY, startX, startY)
                    dragging = true
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return true
                    currentRect.set(
                        min(startX, event.rawX), min(startY, event.rawY),
                        max(startX, event.rawX), max(startY, event.rawY)
                    )
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    val result = RectF(currentRect)
                    invalidate()
                    onSelected(result)
                }
                MotionEvent.ACTION_CANCEL -> {
                    // باگ رفع‌شده: قبلاً ACTION_CANCEL اصلاً هندل نمی‌شد. اگه سیستم
                    // وسط کشیدنِ مستطیل لمس رو قطع می‌کرد (مثلاً یک تماس ورودی یا
                    // دیالوگ مجوز روی صفحه می‌اومد)، onSelected هیچ‌وقت صدا زده
                    // نمی‌شد و imageSelectOverlay برای همیشه غیرنال می‌موند —
                    // یعنی هیچ‌کدوم از منوهای «تشخیص تصویری/رنگ پیکسل/OCR» دیگه
                    // تا ری‌استارت سرویس باز نمی‌شدن (چون همه‌شون چک می‌کنن
                    // `if (imageSelectOverlay != null) return`). با یک مستطیلِ
                    // خالی onSelected رو صدا می‌زنیم؛ چک «حداقل اندازه» که در
                    // on...RegionSelected از قبل وجود داره خودش این رو رد می‌کنه
                    // (بدون ساختن قانون جدید)، ولی overlay درست حذف و state ریست
                    // می‌شه.
                    dragging = false
                    invalidate()
                    onSelected(RectF())
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            if (!currentRect.isEmpty) {
                canvas.drawRect(currentRect, fillPaint)
                canvas.drawRect(currentRect, borderPaint)
            }
        }
    }

    // ==================== فاز ۵: ضبط و پخش دقیق حرکات لمسی ====================

    /**
     * یک لایه‌ی شفاف تمام‌صفحه باز می‌کنه که هر لمسِ کاربر (DOWN/MOVE/UP)
     * رو با زمانِ دقیقش ثبت می‌کنه. برای اینکه حین ضبط، اپ زیرین (مثلاً
     * بازی) هم واقعاً واکنش نشون بده، همون لحظه که یک لمس تموم می‌شه
     * (UP/CANCEL)، دقیقاً همون مسیر رو از طریق AccessibilityService دوباره
     * روی صفحه اجرا می‌کنیم (performRecordedStroke).
     *
     * محدودیت مهم: چون این اوورلی خودش لمس رو می‌گیره، اپ زیرین یک تپ
     * ساده رو تقریباً بی‌تاخیر می‌بینه، ولی یک سوایپ/درگ رو فقط *بعد* از
     * برداشتن انگشت (نه هم‌زمان با کشیدنش) — یعنی حین کشیدن، صفحه‌ی زیرین
     * تا لحظه‌ی رهاسازی حرکت نمی‌کنه. این محدودیتِ روش بدون‌روتِ اندرویده
     * (بدون AccessibilityService غیرمصرف‌کننده‌ی لمس که فقط از اندروید ۱۴
     * به بالا وجود داره)، نه یک باگ. خودِ فایل ضبط‌شده کاملاً دقیقه و پخش
     * بعدی‌اش (playRecording) این تاخیر رو نداره.
     */
    private fun startTouchRecording() {
        if (isRecording) return
        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        activeRecording = Recording(
            id = RecordingStore.newId(),
            name = "ضبط " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
        )
        currentStroke = null

        val overlay = View(this)
        overlay.setBackgroundColor(0x11FF0000) // ته‌رنگ قرمزِ خیلی کمرنگ، فقط برای یادآوریِ «در حال ضبط»

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        overlay.setOnTouchListener { _, event ->
            val t = System.currentTimeMillis() - recordingStartTime
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    currentStroke = RecordedStroke().apply {
                        points.add(TouchPoint(event.rawX, event.rawY, t))
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    currentStroke?.points?.add(TouchPoint(event.rawX, event.rawY, t))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val stroke = currentStroke
                    currentStroke = null
                    if (stroke != null) {
                        stroke.points.add(TouchPoint(event.rawX, event.rawY, t))
                        activeRecording?.strokes?.add(stroke)
                        ClickAccessibilityService.instance?.performRecordedStroke(stroke)
                    }
                }
            }
            true
        }

        windowManager.addView(overlay, params)
        recorderOverlayView = overlay
        showRecordingStopButton()

        Toast.makeText(
            this,
            "ضبط شروع شد — هرکاری با انگشتت روی صفحه انجام بدی دقیقاً ذخیره می‌شه. برای پایان، دکمه‌ی قرمز رو بزن.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showRecordingStopButton() {
        stopRecordingButton = addCornerActionButton(
            "⏹ پایان ضبط",
            ContextCompat.getColor(this, R.color.brand_error)
        ) { stopTouchRecording() }
    }

    private fun stopTouchRecording() {
        if (!isRecording) return
        isRecording = false

        recorderOverlayView?.let { runCatching { windowManager.removeView(it) } }
        recorderOverlayView = null
        stopRecordingButton?.let { runCatching { windowManager.removeView(it) } }
        stopRecordingButton = null

        val recording = activeRecording
        activeRecording = null
        if (recording == null || recording.strokes.isEmpty()) {
            Toast.makeText(this, "هیچ لمسی ضبط نشد", Toast.LENGTH_SHORT).show()
            return
        }
        promptSaveRecording(recording)
    }

    private fun promptSaveRecording(recording: Recording) {
        val input = EditText(themedContext).apply { setText(recording.name) }
        val dialog = MaterialAlertDialogBuilder(themedContext)
            .setTitle("ذخیره‌ی ضبط (${recording.describe()})")
            .setView(input)
            .setPositiveButton("ذخیره") { _, _ ->
                recording.name = input.text.toString().trim().ifBlank { recording.name }
                RecordingStore.add(this, recording)
                Toast.makeText(this, "ضبط ذخیره شد", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("رد کردن (ذخیره نشه)", null)
            .create()
        dialog.window?.setType(overlayWindowType())
        dialog.show()
    }

    private fun showPlayRecordingDialog() {
        val recordings = RecordingStore.getAll(this)
        if (recordings.isEmpty()) {
            Toast.makeText(this, "هنوز هیچ ضبطی ذخیره نشده", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = recordings.map { "${it.name} — ${it.describe()}" }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(themedContext)
            .setTitle("پخش کدوم ضبط؟")
            .setItems(labels) { _, which ->
                val svc = ClickAccessibilityService.instance
                if (svc == null) {
                    Toast.makeText(this, "سرویس Accessibility فعال نیست", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                val chosen = recordings[which]
                Toast.makeText(this, "در حال پخش «${chosen.name}»…", Toast.LENGTH_SHORT).show()
                svc.playRecording(chosen) {
                    handler.post { Toast.makeText(this, "پخش تمام شد", Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton("انصراف", null)
            .create()
        dialog.window?.setType(overlayWindowType())
        dialog.show()
    }

    // ---------- فاز ۱۱: «حالت ساده» — گرفتنِ چند نقطه با تپ ----------

    /**
     * یک اوورلیِ تمام‌صفحه‌ی شفاف که هر تپ روش رو به‌عنوان یک نقطه‌ی جدید در
     * [SimpleModeStore] ثبت می‌کنه (چندتا تپ پشت‌سرهم مجازه — برخلاف
     * انتخاب‌گرهای تک‌نقطه‌ایِ فاز ۲ مثل showPointPickerOverlay). دقیقاً
     * همون الگوی startTouchRecording (اوورلیِ لمس + دکمه‌ی شناورِ پایان)، با
     * این تفاوت که این‌جا فقط مختصات ثبت می‌شه، نه کل مسیر لمس.
     */
    private fun showSimplePointPickerOverlay() {
        if (simplePickerOverlay != null) return
        simplePickerCount = SimpleModeStore.getPoints(this).size

        val overlay = View(this)
        overlay.setBackgroundColor(0x1100C853) // ته‌رنگ سبزِ خیلی کمرنگ، برای تمایز از سایر انتخاب‌گرها

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        overlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                SimpleModeStore.addPoint(this, SimplePoint(event.rawX, event.rawY))
                simplePickerCount++
                simplePickerStopButton?.text = "✅ پایان ($simplePickerCount نقطه)"
                Toast.makeText(
                    this,
                    "نقطه‌ی $simplePickerCount ثبت شد (${event.rawX.toInt()}, ${event.rawY.toInt()})",
                    Toast.LENGTH_SHORT
                ).show()
            }
            true
        }

        windowManager.addView(overlay, params)
        simplePickerOverlay = overlay
        showSimplePickerStopButton()

        Toast.makeText(
            this,
            "روی هر نقطه‌ای که می‌خوای کلیک بشه تپ کن — چندتا نقطه پشت‌سرهم مجازه. برای پایان، دکمه‌ی سبز رو بزن.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showSimplePickerStopButton() {
        simplePickerStopButton = addCornerActionButton(
            "✅ پایان ($simplePickerCount نقطه)",
            ContextCompat.getColor(this, R.color.brand_success)
        ) { stopSimplePointPicking() }
    }

    private fun stopSimplePointPicking() {
        simplePickerOverlay?.let { runCatching { windowManager.removeView(it) } }
        simplePickerOverlay = null
        simplePickerStopButton?.let { runCatching { windowManager.removeView(it) } }
        simplePickerStopButton = null

        val total = SimpleModeStore.getPoints(this).size
        Toast.makeText(this, "پایان انتخاب نقاط — $total نقطه ثبت‌شده", Toast.LENGTH_LONG).show()

        // کاربر رو به همون صفحه‌ی «حالت ساده» برمی‌گردونیم تا لیست به‌روزشده رو ببینه
        val intent = Intent(this, SimpleModeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
    }

    // ---------- فاز ۱۱: «حالت ساده» — حلقه‌ی کلیکِ متوالی روی نقاط ----------

    /**
     * برخلاف clickLoopRunnable (که هر تیک، *همه‌ی* قوانین فعال رو چک می‌کنه)،
     * این حلقه هر تیک فقط *یک* نقطه رو کلیک می‌کنه و به نقطه‌ی بعدی می‌ره —
     * دقیقاً معنای «به‌ترتیب، با یک فاصله‌ی زمانی» که کاربر خواسته. کلیکِ
     * واقعی از طریق یک ClickRule موقتِ COORDINATE انجام می‌شه (نه یک مسیر
     * جدا) تا زیرساخت موجود (لاگِ اجرا، حالت آزمایشی و...) رایگان اضافه بشه.
     */
    private val simpleModeRunnable = object : Runnable {
        override fun run() {
            if (!isSimpleModeRunning) return

            val points = SimpleModeStore.getPoints(this@OverlayService)
            if (points.isEmpty()) {
                Toast.makeText(this@OverlayService, "هیچ نقطه‌ای ثبت نشده — حالت ساده متوقف شد", Toast.LENGTH_LONG).show()
                isSimpleModeRunning = false
                return
            }

            val svc = ClickAccessibilityService.instance
            if (svc != null) {
                val idx = simpleModeIndex % points.size
                val point = points[idx]
                val tempRule = ClickRule(
                    id = "simple_mode_point_$idx",
                    matchType = MatchType.COORDINATE,
                    matchValue = "${point.x},${point.y}",
                    label = "حالت ساده — نقطه‌ی ${idx + 1}"
                )
                svc.attemptClickRule(tempRule)
                simpleModeIndex = idx + 1
            }

            handler.postDelayed(this, SimpleModeStore.getIntervalMs(this@OverlayService))
        }
    }

    private fun startSimpleModeLoop() {
        if (isSimpleModeRunning) return
        if (SimpleModeStore.getPoints(this).isEmpty()) {
            Toast.makeText(this, "اول باید حداقل یک نقطه اضافه کنی", Toast.LENGTH_LONG).show()
            return
        }
        isSimpleModeRunning = true
        simpleModeIndex = 0
        val count = SimpleModeStore.getPoints(this).size
        val interval = SimpleModeStore.getIntervalMs(this)
        Toast.makeText(this, "حالت ساده شروع شد ($count نقطه، هر ${interval}ms)", Toast.LENGTH_LONG).show()
        handler.post(simpleModeRunnable)
    }

    private fun stopSimpleModeLoop() {
        isSimpleModeRunning = false
        handler.removeCallbacks(simpleModeRunnable)
        Toast.makeText(this, "حالت ساده متوقف شد", Toast.LENGTH_SHORT).show()
    }

    // ---------- فاز ۱۲: پیش‌نمایشِ نقاطِ «حالت ساده» روی صفحه ----------

    /**
     * یک اوورلیِ تمام‌صفحه‌ی *غیرقابل‌لمس* (FLAG_NOT_TOUCHABLE — لمس‌ها رد
     * می‌شن و به اپ زیرین می‌رسن) که فقط یک دایره‌ی شماره‌دار دقیقاً روی
     * مختصاتِ هر نقطه‌ی ثبت‌شده می‌کشه. هیچ کلیکی انجام نمی‌شه؛ صرفاً برای
     * اینه که کاربر مطمئن بشه نقطه‌ها هنوز جای درستی‌ان (مثلاً بعد از
     * تغییر رزولوشن یا layout بازیِ هدف).
     *
     * خودکار بعد از [PREVIEW_AUTO_HIDE_MS] بسته می‌شه؛ یک دکمه‌ی «✖ بستن»
     * هم برای بستنِ زودتر هست.
     */
    private fun showPointsPreviewOverlay() {
        if (pointsPreviewOverlay != null) return
        val points = SimpleModeStore.getPoints(this)
        if (points.isEmpty()) {
            Toast.makeText(this, "هیچ نقطه‌ای برای پیش‌نمایش ثبت نشده", Toast.LENGTH_SHORT).show()
            return
        }

        val overlay = PointsPreviewView(this, points)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlay, params)
        pointsPreviewOverlay = overlay
        showPointsPreviewCloseButton()

        Toast.makeText(this, "پیش‌نمایش ${points.size} نقطه — چند ثانیه‌ی دیگه خودکار بسته می‌شه", Toast.LENGTH_SHORT).show()
        handler.removeCallbacks(hidePointsPreviewRunnable)
        handler.postDelayed(hidePointsPreviewRunnable, PREVIEW_AUTO_HIDE_MS)
    }

    private fun showPointsPreviewCloseButton() {
        pointsPreviewCloseButton = addCornerActionButton(
            "✖ بستن پیش‌نمایش",
            ContextCompat.getColor(this, R.color.app_on_surface_variant)
        ) { hidePointsPreviewOverlay() }
    }

    private fun hidePointsPreviewOverlay() {
        handler.removeCallbacks(hidePointsPreviewRunnable)
        pointsPreviewOverlay?.let { runCatching { windowManager.removeView(it) } }
        pointsPreviewOverlay = null
        pointsPreviewCloseButton?.let { runCatching { windowManager.removeView(it) } }
        pointsPreviewCloseButton = null
    }

    /** فقط رسم می‌کنه؛ هیچ لمسی نمی‌گیره (اوورلیِ بیرونی FLAG_NOT_TOUCHABLE داره). */
    private class PointsPreviewView(context: Context, private val points: List<SimplePoint>) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E53935")
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            textAlign = Paint.Align.CENTER
        }
        private val radius = 28f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            points.forEachIndexed { index, p ->
                canvas.drawCircle(p.x, p.y, radius, fillPaint)
                canvas.drawCircle(p.x, p.y, radius, borderPaint)
                val label = (index + 1).toString()
                val textY = p.y - (textPaint.descent() + textPaint.ascent()) / 2
                canvas.drawText(label, p.x, textY, textPaint)
            }
        }
    }

    // ---------- حلقه‌ی کلیک خودکار ----------

    private val clickLoopRunnable = object : Runnable {
        override fun run() {
            if (!isLoopRunning) return

            val svc = ClickAccessibilityService.instance
            val enabledRules = RuleStore.getEnabled(this@OverlayService)

            if (svc != null) {
                // فاز ۹: همه‌ی قوانین سناریوی فعال (نه فقط فعال‌ها) رو به‌صورت
                // Map<id, rule> پاس می‌دیم تا زنجیره‌ی شرطی (elseRuleId) هم
                // بتونه قانونِ جایگزین رو حتی اگه خودش «فعال» نباشه پیدا کنه، و
                // هم این پیدا کردن O(1) باشه (نه O(n) با هر hop زنجیره).
                val allRulesById = ScenarioStore.getRules(this@OverlayService).associateBy { it.id }
                // برای هر قانون فعال، فقط اگه المان/مختصات هدف واقعاً روی صفحه‌ی
                // فعلی حضور داشته باشه کلیک انجام می‌شه؛ در غیر این صورت رد می‌شه.
                for (rule in enabledRules) {
                    svc.attemptClickRule(rule, allRulesById)
                }
            }

            handler.postDelayed(this, RuleStore.loopIntervalMs)
        }
    }

    private fun toggleClickLoop() {
        isLoopRunning = !isLoopRunning
        if (isLoopRunning) {
            val count = RuleStore.getEnabled(this).size
            Toast.makeText(this, "حلقه‌ی کلیک شروع شد ($count قانون فعال)", Toast.LENGTH_SHORT).show()
            handler.post(clickLoopRunnable)
        } else {
            Toast.makeText(this, "حلقه‌ی کلیک متوقف شد", Toast.LENGTH_SHORT).show()
            handler.removeCallbacks(clickLoopRunnable)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        ClickAccessibilityService.dryRunListener = null
        ClickAccessibilityService.dryRunEnabled = false
        isSimpleModeRunning = false
        floatingView?.let { runCatching { windowManager.removeView(it) } }
        pickerOverlayView?.let { runCatching { windowManager.removeView(it) } }
        elementPickerView?.let { runCatching { windowManager.removeView(it) } }
        imageSelectOverlay?.let { runCatching { windowManager.removeView(it) } }
        recorderOverlayView?.let { runCatching { windowManager.removeView(it) } }
        stopRecordingButton?.let { runCatching { windowManager.removeView(it) } }
        simplePickerOverlay?.let { runCatching { windowManager.removeView(it) } }
        simplePickerStopButton?.let { runCatching { windowManager.removeView(it) } }
        pointsPreviewOverlay?.let { runCatching { windowManager.removeView(it) } }
        pointsPreviewCloseButton?.let { runCatching { windowManager.removeView(it) } }
    }
}
