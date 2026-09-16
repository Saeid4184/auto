package com.example.autoclickerpro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * فاز ۳ — ذخیره‌سازی تصاویر الگو (template) برای قوانین IMAGE.
 * فایل‌ها به‌صورت PNG در پوشه‌ی داخلی اپ (filesDir/templates) نگه‌داری می‌شن؛
 * فقط مسیر فایل در ClickRule.matchValue ذخیره می‌شه، نه خودِ تصویر (که حجم
 * SharedPreferences رو زیاد نکنه).
 */
object TemplateStore {
    private const val TAG = "TemplateStore"
    private const val DIR_NAME = "templates"

    // کش کوچیک تا هر تکرار حلقه‌ی کلیک، فایل رو دوباره از دیسک دیکد نکنیم.
    // بهینه‌سازی: قبلاً LinkedHashMap معمولی بود (ترتیب بر اساس زمان
    // insert، نه زمان استفاده) و موقع پر شدن، همیشه قدیمی‌ترین *ورودی‌شده*
    // پاک می‌شد — حتی اگه همون یکی پرکاربردترین الگو بود. با
    // accessOrder=true، هر get() خودش آیتم رو به انتهای صف (تازه‌ترین)
    // منتقل می‌کنه، یعنی واقعاً LRU (کم‌استفاده‌ترین حذف می‌شه، نه
    // قدیمی‌ترین‌اضافه‌شده).
    private val cache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private const val MAX_CACHE = 20

    // بهینه‌سازی: اگه فایل یک الگو گم/خراب بشه (نادر، ولی مثلاً به‌خاطر
    // پاک‌سازی حافظه‌ی سیستم یا یک باگ بیرونی ممکنه)، بدون این کش، هر
    // قانون IMAGE که به همون مسیر اشاره می‌کنه هر تیکِ حلقه‌ی کلیک (هر
    // ۱ تا چند بار در ثانیه) دوباره سعی می‌کنه از دیسک بخونتش و دوباره
    // شکست می‌خوره — I/O و لاگِ تکراریِ بی‌فایده. یک بار که مسیری قطعاً
    // ناموفق بود، تا وقتی cache پاک نشه دیگه دوباره امتحان نمی‌کنیم.
    private val knownBadPaths = HashSet<String>()
    private const val MAX_KNOWN_BAD = 50

    private fun dir(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** یک بیت‌مپ کراپ‌شده رو ذخیره می‌کنه و مسیر فایل رو برمی‌گردونه. */
    fun saveTemplate(context: Context, bitmap: Bitmap): String {
        val file = File(dir(context), "template_${UUID.randomUUID()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file.absolutePath
    }

    @Synchronized
    fun loadTemplate(context: Context, path: String): Bitmap? {
        cache[path]?.let { return it }
        if (path in knownBadPaths) return null
        val bmp = runCatching { BitmapFactory.decodeFile(path) }
            .onFailure { Log.e(TAG, "خطا در بارگذاری الگو از $path", it) }
            .getOrNull()
        if (bmp == null) {
            if (knownBadPaths.size >= MAX_KNOWN_BAD) knownBadPaths.clear()
            knownBadPaths.add(path)
            return null
        }
        if (cache.size >= MAX_CACHE) {
            val oldestKey = cache.keys.firstOrNull()
            if (oldestKey != null) cache.remove(oldestKey)
        }
        cache[path] = bmp
        return bmp
    }

    @Synchronized
    fun deleteTemplate(path: String) {
        cache.remove(path)
        knownBadPaths.remove(path)
        runCatching { File(path).delete() }
    }

    /**
     * یک فایل الگوی موجود رو با نام جدید کپی می‌کنه و مسیر فایل جدید رو
     * برمی‌گردونه (یا null اگه فایل اصلی موجود نباشه). این برای رفع یک باگ
     * لازم شد: قبلاً وقتی یک سناریو کپی می‌شد (duplicateScenario)، قوانین
     * IMAGE کپی‌شده هنوز به همون مسیر فایل تصویر سناریوی اصلی اشاره می‌کردن؛
     * پس با حذف قانون/سناریوی اصلی، فایل تصویر پاک می‌شد و قانون IMAGE در
     * سناریوی کپی‌شده هم برای همیشه از کار می‌افتاد (بدون کرش، فقط ساکت).
     */
    fun copyTemplate(context: Context, originalPath: String): String? {
        val source = File(originalPath)
        if (!source.exists()) return null
        val dest = File(dir(context), "template_${UUID.randomUUID()}.png")
        return runCatching {
            source.copyTo(dest, overwrite = true)
            dest.absolutePath
        }.onFailure { Log.e(TAG, "خطا در کپی الگو از $originalPath", it) }.getOrNull()
    }
}
