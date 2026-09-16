package com.example.autoclickerpro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * فاز ۹ — OCR برای خوندن متن روی صفحه (امتیاز، وضعیت، شماره‌ی مرحله و...).
 *
 * از ML Kit Text Recognition (نسخه‌ی on-device، مدل داخل خودِ APK باندل
 * می‌شه — نیازی به سرویس گوگل‌پلی یا اینترنت حین اجرا نیست، فقط حین
 * build/دانلود دیپندنسی از Maven اینترنت لازمه، دقیقاً مثل OpenCV در فاز ۳)
 * استفاده می‌کنیم.
 *
 * نکته‌ی طراحی مهم: recognizer.process() خودش async و مبتنی بر Task/Play
 * Services هست. چون این تابع قراره از داخل imageMatchExecutor (یک ترد
 * پس‌زمینه‌ی اختصاصی که ClickAccessibilityService برای کار سنگین
 * تصویر/تشخیص استفاده می‌کنه) صدا زده بشه — نه ترد اصلی —، با
 * Tasks.await(...) صبر می‌کنیم تا نتیجه برسه؛ این باعث نمی‌شه UI/حلقه‌ی
 * اصلی قفل بشه، چون از قبل روی ترد جدا هستیم.
 */
object OcrMatcher {
    private const val TAG = "OcrMatcher"
    private const val TIMEOUT_SECONDS = 4L

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // بهینه‌سازی: کش نتیجه‌ی OCR بر اساس (فریم فعلی + ناحیه). چرا لازمه؟ اگه
    // چند قانون OCR_TEXT (مثلاً در یک زنجیره‌ی شرطی) روی *همون ناحیه‌ی
    // دقیقاً یکسان* از *همون فریم* دنبال متن‌های مختلفی بگردن، بدون این کش
    // هر کدوم جدا ML Kit رو صدا می‌زنن، در حالی که متنِ تشخیص‌داده‌شده برای
    // یک فریم/ناحیه‌ی مشخص همیشه یکی‌ست. تشخیصِ «فریم عوض شده یا نه» با
    // مقایسه‌ی reference (===) انجام می‌شه چون ScreenCaptureService هر فریم
    // یک Bitmap کاملاً جدید می‌سازه (نه این‌که فریم قبلی رو mutate کنه).
    //
    // نکته: چون ConcurrentHashMap مقدار null قبول نمی‌کنه، شکستِ تشخیص (یا
    // متنِ کاملاً خالی) به‌صورت رشته‌ی خالی "" کش می‌شه — از نظر عملی مشکلی
    // نداره چون هیچ‌وقت rule.matchValueِ غیرخالی داخل یک رشته‌ی خالی پیدا
    // نمی‌شه (contains روش false برمی‌گردونه، دقیقاً همون رفتاری که بدون کش
    // هم می‌گرفتیم).
    @Volatile private var cachedFrame: Bitmap? = null
    private val regionCache = ConcurrentHashMap<String, String>()

    /**
     * نسخه‌ی کش‌شده‌ی «کراپ + پیش‌پردازش (اختیاری) + OCR»: برای [frame]/[region]
     * مشخص، اگه قبلاً (در همین فریم) محاسبه شده، بدون صدا زدن دوباره‌ی ML Kit
     * همون نتیجه رو برمی‌گردونه.
     *
     * [preprocess] رو true بذار وقتی هدف متنِ داخل بازی‌ها/محیط‌های
     * Unity-Cocos‌ه: فونت‌های این محیط‌ها (مخصوصاً TextMeshPro با
     * outline/سایه/گرادیان، یا فونت‌های پیکسلیِ کوچیک) اغلب کنتراست کمی
     * دارن یا خیلی ریزن؛ [preprocessForOcr] با خاکستری‌سازی+افزایش کنتراست+
     * بزرگ‌نمایی (در صورت نیاز) دقتِ ML Kit رو روی این نوع متن‌ها بهتر می‌کنه.
     * برای متنِ اپ‌های عادی (که از ویجت‌های استاندارد رندر می‌شه) لازم نیست
     * ولی معمولاً ضرری هم نداره.
     */
    fun recognizeRegion(frame: Bitmap, region: String?, preprocess: Boolean = true): String? {
        if (cachedFrame !== frame) {
            cachedFrame = frame
            regionCache.clear()
        }
        val key = "${region ?: "__FULL_FRAME__"}|pre=$preprocess"
        regionCache[key]?.let { return it.ifEmpty { null } }

        val cropped = cropRegion(frame, region)
        val input = if (preprocess) preprocessForOcr(cropped) else cropped
        val text = recognizeText(input) ?: ""
        regionCache[key] = text
        return text.ifEmpty { null }
    }

    /**
     * پیش‌پردازش برای بهبود دقت OCR روی متنِ محیط‌های بازی (Unity/Cocos):
     *  ۱) بزرگ‌نمایی: اگه ارتفاع ناحیه کوچیکه (فونت‌های پیکسلیِ ریزِ رایج در
     *     بازی‌ها)، ML Kit روی متن بزرگ‌تر خیلی بهتر عمل می‌کنه.
     *  ۲) خاکستری‌سازی + افزایش کنتراست: فونت‌های بازی (خصوصاً TextMeshPro با
     *     outline/سایه/گرادیانِ رنگی) کنتراستِ ساده‌ای با پس‌زمینه ندارن؛ یک
     *     ColorMatrix ساده معمولاً باعث می‌شه لبه‌ی حروف واضح‌تر بشه.
     * این عملیات سبکه (فقط یک بار در هر ناحیه/فریمِ جدید انجام می‌شه، به‌خاطر
     * کشِ [recognizeRegion]) و روی ترد پس‌زمینه‌ی OCR اجرا می‌شه.
     */
    private fun preprocessForOcr(bitmap: Bitmap): Bitmap {
        val scaleFactor = when {
            bitmap.height < 40 -> 3
            bitmap.height < 80 -> 2
            else -> 1
        }
        val scaled = if (scaleFactor > 1) {
            runCatching {
                Bitmap.createScaledBitmap(bitmap, bitmap.width * scaleFactor, bitmap.height * scaleFactor, true)
            }.getOrDefault(bitmap)
        } else bitmap

        return runCatching {
            val output = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint()
            val contrast = 1.6f
            val translate = (-0.5f * contrast + 0.5f) * 255f
            val cm = ColorMatrix(
                floatArrayOf(
                    0.213f * contrast, 0.715f * contrast, 0.072f * contrast, 0f, translate,
                    0.213f * contrast, 0.715f * contrast, 0.072f * contrast, 0f, translate,
                    0.213f * contrast, 0.715f * contrast, 0.072f * contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            paint.colorFilter = ColorMatrixColorFilter(cm)
            canvas.drawBitmap(scaled, 0f, 0f, paint)
            output
        }.getOrDefault(scaled)
    }

    /**
     * متن داخل [bitmap] رو تشخیص می‌ده و کل متن پیدا‌شده (چندخطی، به همون
     * شکلی که ML Kit برمی‌گردونه) رو به‌عنوان یک رشته‌ی واحد برمی‌گردونه.
     * در صورت خطا یا timeout، null برمی‌گردونه (نه exception) — چون این
     * تابع هر چند صد میلی‌ثانیه یک‌بار در حلقه‌ی کلیک صدا زده می‌شه و نباید
     * با یک خطای موقتی (مثلاً فریم خراب) کل سرویس رو کرش بده.
     *
     * ⚠️ باید از یک ترد پس‌زمینه صدا زده بشه، هرگز از ترد اصلی (چون داخلش
     * Tasks.await صدا می‌شه که مسدودکننده‌ست).
     */
    fun recognizeText(bitmap: Bitmap): String? {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(image), TIMEOUT_SECONDS, TimeUnit.SECONDS)
            result.text
        } catch (t: Throwable) {
            Log.e(TAG, "خطا در OCR (تشخیص متن)", t)
            null
        }
    }

    /**
     * [region] رو به شکل "left,top,right,bottom" پارس می‌کنه و همون بخش از
     * [frame] رو کراپ می‌کنه. اگه [region] نامعتبر/null باشه، خودِ [frame]
     * بدون تغییر برگردونده می‌شه (یعنی OCR روی کل صفحه انجام می‌شه — کندتر و
     * مستعد تشخیص اشتباه بیشتر، ولی به‌عنوان fallback امن کار می‌کنه).
     */
    fun cropRegion(frame: Bitmap, region: String?): Bitmap {
        if (region.isNullOrBlank()) return frame
        val parts = region.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 4) return frame
        val left = parts[0].coerceIn(0, frame.width - 1)
        val top = parts[1].coerceIn(0, frame.height - 1)
        val right = parts[2].coerceIn(left + 1, frame.width)
        val bottom = parts[3].coerceIn(top + 1, frame.height)
        return runCatching {
            Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
        }.getOrDefault(frame)
    }
}
