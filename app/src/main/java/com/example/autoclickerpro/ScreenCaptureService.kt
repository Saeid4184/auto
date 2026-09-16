package com.example.autoclickerpro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * فاز ۳ — سرویسی که با MediaProjection API از صفحه فریم زنده می‌گیره، تا
 * ImageMatcher بتونه توشون دنبال تصویر الگو بگرده.
 *
 * نکته‌ی طراحی: درست مثل ClickAccessibilityService، از یک نمونه‌ی static
 * (companion) استفاده می‌کنیم تا OverlayService/ClickAccessibilityService
 * بتونن مستقیماً به آخرین فریم گرفته‌شده دسترسی داشته باشن، بدون نیاز به
 * AIDL/Binder پیچیده (هر سه‌تا در یک پروسه اجرا می‌شن).
 *
 * مجوز گرفتن اسکرین‌شات (MediaProjection) باید هر بار توسط کاربر از طریق یک
 * دیالوگ سیستمی تأیید بشه — این کار توی MainActivity با
 * registerForActivityResult(ActivityResultContracts.StartActivityForResult())
 * انجام می‌شه و resultCode+data به این سرویس پاس داده می‌شه.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val NOTIF_CHANNEL_ID = "autoclicker_capture_channel"
        const val NOTIF_ID = 1002

        // بهینه‌سازی مهم: VirtualDisplay دقیقاً هر فریمی که خودِ صفحه‌ی گوشی
        // (با فریم‌ریتِ واقعی نمایشگر، مثلاً ۶۰-۱۲۰ فریم بر ثانیه، وقتی محتوا
        // در حال انیمیشنه) تولید می‌کنه رو به ImageReader می‌فرسته — یعنی بدون
        // این محدودیت، onImageAvailable ده‌ها بار در ثانیه صدا زده می‌شه و هر
        // بار یک Bitmap تمام‌صفحه‌ی جدید (چند مگابایت) ساخته می‌شه، در حالی
        // که حلقه‌ی کلیک هر loopIntervalMs (پیش‌فرض ۱۵۰۰ میلی‌ثانیه، و طبق
        // توصیه‌ی خودِ این پروژه هیچ‌وقت کمتر از ~۱۰۰۰) فقط یک‌بار آخرین فریم
        // رو می‌خونه. پس دیکد کردنِ هر فریم صرفاً باتری/CPU/حافظه هدر می‌ده.
        // اینجا فریم رو حداکثر هر MIN_FRAME_INTERVAL_MS یک‌بار واقعاً دیکد
        // می‌کنیم؛ فریم‌های میانی فقط acquire+close می‌شن (خیلی سبک) تا صف
        // ImageReader (ظرفیت ۲) پر نشه و تولید فریم توسط VirtualDisplay دچار
        // backpressure نشه.
        private const val MIN_FRAME_INTERVAL_MS = 200L

        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        /** آخرین فریم گرفته‌شده از صفحه؛ ممکنه null باشه اگه هنوز فریمی نیومده. */
        @Volatile
        var latestFrame: Bitmap? = null
            private set
    }

    @Volatile
    private var lastFrameDecodeAtMs = 0L

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // ترد پس‌زمینه‌ی جدا برای پردازش فریم‌ها (دیکد Image -> Bitmap)، تا هیچ‌وقت
    // ترد اصلی (UI) رو برای این کار مسدود نکنیم — قبلاً این پردازش روی ترد اصلی
    // انجام می‌شد (باگ کارایی).
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    // باگ مهم رفع‌شده: از اندروید ۱۴ (API 34) به بعد، فراخوانی
    // MediaProjection.createVirtualDisplay بدون ثبت قبلی یک Callback با
    // registerCallback باعث IllegalStateException و کرش اپ می‌شه. علاوه بر
    // این، این Callback به ما اجازه می‌ده وقتی کاربر از طریق نوار سیستم/اعلان
    // «توقف اشتراک‌گذاری صفحه» رو بزنه، به‌درستی resource ها رو آزاد کنیم
    // (قبلاً در این حالت virtualDisplay/imageReader بلاتکلیف می‌موندن و
    // latestFrame برای همیشه یک فریم قدیمی/غیرمعتبر باقی می‌موند).
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection توسط سیستم/کاربر متوقف شد")
            stopCapture()
            latestFrame = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundWithNotification()
        bgThread = HandlerThread("AutoClickerPro-CaptureThread").apply { start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData != null) {
            startCapture(resultCode, resultData)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "AutoClicker Pro - ضبط صفحه",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("تشخیص تصویری فعال است")
            .setContentText("AutoClicker Pro در حال خواندن فریم‌های صفحه برای تطبیق الگو است")
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        stopCapture() // اگه از قبل در حال اجرا بود، اول تمیزش کن
        lastFrameDecodeAtMs = 0L // تا اولین فریمِ همین سشن بدون تاخیرِ throttle دیکد بشه

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData) ?: run {
            Log.e(TAG, "getMediaProjection نال برگردوند")
            return
        }
        mediaProjection = projection
        // باید قبل از هر فراخوانی دیگه‌ای روی projection (از جمله createVirtualDisplay)
        // انجام بشه، وگرنه روی اندروید ۱۴+ کرش می‌کنه.
        projection.registerCallback(projectionCallback, bgHandler)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        imageReader = reader

        // باگ رفع‌شده: قبلاً Handler پاس داده‌شده null بود که یعنی این listener
        // (شامل decode کردن هر فریم به Bitmap) روی ترد فراخوانی‌کننده — که همون
        // ترد اصلی/UI سرویس بود — اجرا می‌شد و می‌تونست باعث لگ/ANR بشه.
        //
        // باگ دیگه‌ای که رفع شد: قبلاً فقط تبدیلِ Image به Bitmap داخل
        // try/catch بود، ولی خودِ ir.acquireLatestImage() بیرون از try بود.
        // اگه دقیقاً هم‌زمان با این callback (که روی bgHandler اجرا می‌شه)،
        // ترد اصلی stopCapture()/imageReader.close() رو صدا بزنه (مثلاً کاربر
        // از نوار سیستم «توقف اشتراک‌گذاری» رو بزنه یا سرویس destroy بشه)،
        // acquireLatestImage روی یک ImageReader بسته‌شده می‌تونه
        // IllegalStateException بده؛ چون این روی یک ترد پس‌زمینه‌ی جداست، یک
        // exception گرفته‌نشده کل پروسه‌ی اپ رو کرش می‌ده، نه فقط همین ترد رو.
        // حالا کل بدنه (شامل acquire) قورت داده می‌شه.
        reader.setOnImageAvailableListener({ ir ->
            try {
                val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    // بهینه‌سازی: فقط اگه حداقل MIN_FRAME_INTERVAL_MS از آخرین
                    // دیکدِ واقعی گذشته باشه، Bitmap جدید می‌سازیم؛ وگرنه فقط
                    // همین acquire+close (سبک) صف رو خالی نگه می‌داره تا
                    // VirtualDisplay دچار backpressure نشه، بدون هزینه‌ی
                    // ساختن/کپی یک بیت‌مپ تمام‌صفحه‌ی بلااستفاده.
                    val now = System.currentTimeMillis()
                    if (now - lastFrameDecodeAtMs >= MIN_FRAME_INTERVAL_MS) {
                        latestFrame = imageToBitmap(image, width, height)
                        lastFrameDecodeAtMs = now
                    }
                } finally {
                    image.close()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "خطا در پردازش فریم صفحه (احتمالاً هم‌زمان با توقف ضبط)", t)
            }
        }, bgHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "AutoClickerPro-Capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )

        Log.d(TAG, "ضبط صفحه شروع شد ($width x $height)")
    }

    private fun imageToBitmap(image: android.media.Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        // ممکنه bitmap کمی عریض‌تر از width واقعی باشه (به‌خاطر padding سطرها)؛
        // برش می‌زنیم تا دقیقاً اندازه‌ی صفحه بشه و مختصات match با مختصات
        // واقعی صفحه یکی باشه.
        return if (bitmap.width != width) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } else {
            bitmap
        }
    }

    fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            it.stop()
        }
        mediaProjection = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        latestFrame = null
        instance = null
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
    }
}
