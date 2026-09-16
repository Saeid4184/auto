package com.example.autoclickerpro

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * فاز ۳ — تشخیص تصویری با OpenCV.
 *
 * این کلاس تنها یک کار می‌کنه: گرفتن آخرین فریم صفحه (از ScreenCaptureService)
 * و یک تصویر الگو (template که کاربر قبلاً کراپ کرده)، و پیدا کردن اینکه آیا
 * الگو در فریم فعلی حضور داره یا نه — با Imgproc.matchTemplate.
 *
 * چرا matchTemplate و نه چیز پیچیده‌تر (feature matching و امثالش)؟ چون برای
 * "پیدا کردن یک آیکون/دکمه‌ی ثابت در صفحه" که چرخش/مقیاس نداره (که حالت رایج
 * دکمه‌های UI هست)، matchTemplate هم سریع‌تره و هم برای این کار کافیه.
 */
object ImageMatcher {
    private const val TAG = "ImageMatcher"

    @Volatile
    private var loaded = false

    // بهینه‌سازی: قبلاً اگه initLocal() یک‌بار شکست می‌خورد (مثلاً چون کتابخونه‌ی
    // بومی برای ABI اون گوشی موجود نیست — یک وضعیتِ سطح دستگاه که تغییر
    // نمی‌کنه)، loaded همیشه false می‌موند و ensureLoaded() هر تیکِ حلقه‌ی
    // کلیک (برای هر قانون IMAGE) دوباره initLocal() رو صدا می‌زد — یعنی
    // برای همیشه، هر بار، دوباره تلاش و دوباره شکست، با یک Log.e تکراری.
    // چون این شکست عملاً همیشه دائمیه، فقط یک‌بار امتحان می‌کنیم.
    @Volatile
    private var attempted = false

    /** باید قبل از اولین استفاده صدا زده بشه (مثلاً در Application.onCreate یا اولین بار لازم). */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (attempted) return false
        attempted = true
        loaded = OpenCVLoader.initLocal()
        if (!loaded) {
            Log.e(TAG, "بارگذاری OpenCV ناموفق بود — قوانین تصویری (IMAGE) کار نخواهند کرد")
        }
        return loaded
    }

    data class MatchResult(val center: PointF, val confidence: Double)

    /**
     * دنبال [template] داخل [screenFrame] می‌گرده.
     * اگه بهترین تطابق >= threshold باشه، مرکز محل match (به مختصات همون
     * screenFrame که مستقیماً معادل مختصات صفحه‌ست) برگردونده می‌شه؛ وگرنه null.
     */
    fun findTemplate(screenFrame: Bitmap, template: Bitmap, threshold: Double): MatchResult? {
        if (!ensureLoaded()) return null
        if (template.width > screenFrame.width || template.height > screenFrame.height) {
            Log.w(TAG, "الگو از خودِ فریم صفحه بزرگ‌تره")
            return null
        }

        val sceneMat = Mat()
        val templateMat = Mat()
        val resultMat = Mat()
        try {
            Utils.bitmapToMat(screenFrame, sceneMat)
            Utils.bitmapToMat(template, templateMat)

            // برای سرعت و مقاومت در برابر تفاوت‌های جزئی رنگ (فشرده‌سازی صفحه و
            // غیره)، هر دو تصویر رو به grayscale تبدیل می‌کنیم.
            Imgproc.cvtColor(sceneMat, sceneMat, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(templateMat, templateMat, Imgproc.COLOR_RGBA2GRAY)

            val resultCols = sceneMat.cols() - templateMat.cols() + 1
            val resultRows = sceneMat.rows() - templateMat.rows() + 1
            if (resultCols <= 0 || resultRows <= 0) return null
            resultMat.create(resultRows, resultCols, CvType.CV_32FC1)

            // TM_CCOEFF_NORMED: خروجی بین -1 و 1 نرمالایز شده، مقدار نزدیک ۱
            // یعنی تطابق تقریباً کامل. نسبت به روشنایی کلی صحنه حساسیت کمی داره
            // که برای صفحه‌ی گوشی (که روشنایی زیاد تغییر نمی‌کنه) مناسبه.
            Imgproc.matchTemplate(sceneMat, templateMat, resultMat, Imgproc.TM_CCOEFF_NORMED)

            val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)
            val confidence = mmr.maxVal
            if (confidence < threshold) return null

            val topLeft = mmr.maxLoc
            val centerX = (topLeft.x + templateMat.cols() / 2.0).toFloat()
            val centerY = (topLeft.y + templateMat.rows() / 2.0).toFloat()
            return MatchResult(PointF(centerX, centerY), confidence)
        } catch (t: Throwable) {
            Log.e(TAG, "خطا در matchTemplate", t)
            return null
        } finally {
            sceneMat.release()
            templateMat.release()
            resultMat.release()
        }
    }
}
