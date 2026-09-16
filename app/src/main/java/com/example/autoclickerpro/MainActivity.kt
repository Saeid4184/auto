package com.example.autoclickerpro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.autoclickerpro.databinding.ActivityMainBinding

/**
 * فاز ۱: مجوز SYSTEM_ALERT_WINDOW + فعال بودن AccessibilityService.
 * فاز ۳: دکمه‌ی جدید "فعال‌سازی ضبط صفحه" — چون گرفتن مجوز MediaProjection
 * (برخلاف overlay/accessibility) یک دیالوگ سیستمیه که فقط از یک Activity
 * قابل درخواسته، این‌جا با registerForActivityResult گرفته می‌شه و resultCode
 * + data به ScreenCaptureService پاس داده می‌شه تا خودش ضبط رو شروع کنه.
 * فاز ۴: دکمه‌ی جدید "مدیریت سناریوها".
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var screenCaptureGranted = false

    // باگ رفع‌شده: از اندروید ۱۳ (API 33) به بعد نمایش نوتیفیکیشن سرویس‌های
    // فورگراند نیاز به مجوز POST_NOTIFICATIONS داره؛ بدون درخواستش، کاربر
    // اعلانِ «در حال اجراست» رو اصلاً نمی‌بینه (سرویس‌ها فنی کار می‌کنن ولی
    // بی‌صدا و نامرئی، که گیج‌کننده‌ست).
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* نتیجه مهم نیست؛ اگه رد بشه فقط اعلان دیده نمی‌شه، عملکرد اصلی سالمه */ }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(intent)
            screenCaptureGranted = true
            Toast.makeText(this, "ضبط صفحه فعال شد", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "مجوز ضبط صفحه داده نشد", Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureNotificationPermission()

        binding.btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnGrantScreenCapture.setOnClickListener {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
        }

        binding.btnStart.setOnClickListener {
            // باگ رفع‌شده: قبلاً با startService ساده صدا زده می‌شد. OverlayService
            // همون لحظه‌ی onCreate خودش startForeground صدا می‌زنه (سرویس فورگراند
            // دائمی با اعلان)، و راه صحیح/مقاومِ اندروید برای این حالت
            // startForegroundService است (دقیقاً مثل ScreenCaptureService پایین‌تر
            // در همین فایل) — نه startService که مخصوص سرویس‌های پس‌زمینه‌ی
            // معمولیه.
            startForegroundService(Intent(this, OverlayService::class.java))
            // اپ رو به پس‌زمینه می‌بریم تا کاربر بتونه روی هر اپ دیگه‌ای دکمه شناور رو ببینه
            moveTaskToBack(true)
        }

        binding.btnManageRules.setOnClickListener {
            startActivity(Intent(this, RulesActivity::class.java))
        }

        binding.btnManageScenarios.setOnClickListener {
            startActivity(Intent(this, ScenariosActivity::class.java))
        }

        binding.btnManageRecordings.setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }

        binding.btnViewRunLog.setOnClickListener {
            startActivity(Intent(this, RunLogActivity::class.java))
        }

        binding.btnViewCounters.setOnClickListener {
            startActivity(Intent(this, CountersActivity::class.java))
        }

        binding.btnSimpleMode.setOnClickListener {
            startActivity(Intent(this, SimpleModeActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // باگ رفع‌شده: قبلاً این فقط در حالت true تنظیم می‌شد و هیچ‌وقت به
        // false برنمی‌گشت. اگه ScreenCaptureService توسط سیستم (کمبود
        // حافظه) یا با «توقف اشتراک‌گذاری صفحه» از نوار سیستم از بین می‌رفت
        // ولی MainActivity (و پرچم screenCaptureGranted توی حافظه‌اش) زنده
        // می‌موند، همچنان پیام «✅ ضبط صفحه فعال است» به‌اشتباه نشون داده
        // می‌شد. حالا وضعیت هر بار واقعاً از روی instance فعلی سرویس خونده
        // می‌شه.
        screenCaptureGranted = ScreenCaptureService.instance != null
        refreshStatus()
    }

    private fun refreshStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        binding.btnStart.isEnabled = hasOverlay && hasAccessibility
        binding.btnGrantScreenCapture.text = if (screenCaptureGranted)
            "✅ ضبط صفحه فعال است (برای تشخیص تصویری)"
        else
            getString(R.string.btn_grant_screen_capture)

        val activeScenarioName = ScenarioStore.getActiveScenario(this).name
        binding.tvStatus.text = when {
            !hasOverlay -> getString(R.string.status_need_overlay)
            !hasAccessibility -> getString(R.string.status_need_accessibility)
            else -> getString(R.string.status_ready) + "\nسناریوی فعال: $activeScenarioName"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${ClickAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedComponent, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
