package com.example.autoclickerpro

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.autoclickerpro.databinding.ActivitySimpleModeBinding
import java.util.Collections

/**
 * فاز ۱۱ — «حالت ساده»: در برابر سیستم پیچیده‌ی قوانین/سناریو (تشخیص
 * المان/تصویر/رنگ/OCR + زنجیره‌ی شرطی و...)، این یک مسیر خیلی ساده‌ست:
 * چند نقطه انتخاب کن، یک فاصله‌ی زمانی بده، به‌ترتیب و تا وقتی خودت متوقف
 * نکردی پشت‌سرهم روشون کلیک می‌خوره. هیچ شرط/تشخیصی در کار نیست.
 *
 * ساختنِ خودِ نقاط باید حین کار واقعی با اپ هدف انجام بشه (چون باید دقیقاً
 * جایی که کاربر می‌بینه رو تپ کنه)، پس درست مثل ضبطِ لمسی (فاز ۵)، دکمه‌ی
 * «افزودن نقطه» یک اوورلی تمام‌صفحه روی OverlayService باز می‌کنه و اپ به
 * پس‌زمینه می‌ره؛ با هر تپ یک نقطه ثبت می‌شه، تا وقتی دکمه‌ی سبزِ «پایان»
 * (که خودِ OverlayService نشون می‌ده) زده بشه.
 *
 * فاز ۱۲: دو تا اضافه شد —
 *  ۱) ترتیبِ نقاط با درگ‌کردنِ دستگیره‌ی ☰ کنار هر ردیف قابل جابه‌جاییه
 *     (RecyclerView + ItemTouchHelper، چون ListView قدیمی از drag پشتیبانی
 *     نمی‌کنه).
 *  ۲) دکمه‌ی «پیش‌نمایش نقاط روی صفحه» که یک اوورلیِ غیرقابل‌لمس با
 *     مارکرهای شماره‌دار دقیقاً روی مختصاتِ ثبت‌شده نشون می‌ده — برای
 *     مطمئن‌شدن از اینکه نقطه‌ها هنوز درستن، بدون اینکه واقعاً کلیکی بخوره.
 */
class SimpleModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySimpleModeBinding
    private lateinit var adapter: SimplePointAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimpleModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = SimplePointAdapter()
        binding.rvSimplePoints.layoutManager = LinearLayoutManager(this)
        binding.rvSimplePoints.adapter = adapter

        val touchHelper = ItemTouchHelper(ReorderCallback(adapter))
        touchHelper.attachToRecyclerView(binding.rvSimplePoints)
        adapter.itemTouchHelper = touchHelper

        binding.etIntervalMs.setText(SimpleModeStore.getIntervalMs(this).toString())

        binding.btnAddPoint.setOnClickListener { startPointPicking() }
        binding.btnPreviewPoints.setOnClickListener { previewPoints() }

        binding.btnClearPoints.setOnClickListener {
            SimpleModeStore.clearPoints(this)
            adapter.refresh()
        }

        binding.btnStartSimple.setOnClickListener { startSimpleMode() }
        binding.btnStopSimple.setOnClickListener { stopSimpleMode() }
    }

    override fun onResume() {
        super.onResume()
        // ممکنه کاربر از داخل اوورلی نقطه اضافه کرده باشه و همین الان به این
        // صفحه برگشته باشه — لیست رو دوباره از SimpleModeStore می‌خونیم.
        adapter.refresh()
    }

    private fun saveIntervalFromInput() {
        val ms = binding.etIntervalMs.text.toString().toLongOrNull()
        if (ms != null) {
            SimpleModeStore.setIntervalMs(this, ms)
            // اگه کاربر عددی زیر حداقل مجاز وارد کرده بود، مقدارِ واقعاً
            // ذخیره‌شده (بعد از coerce) رو تو کادر نشون می‌دیم تا گیج نشه.
            binding.etIntervalMs.setText(SimpleModeStore.getIntervalMs(this).toString())
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val hasOverlay = Settings.canDrawOverlays(this)
        val expectedComponent = "$packageName/${ClickAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        var hasAccessibility = false
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedComponent, ignoreCase = true)) {
                hasAccessibility = true
                break
            }
        }
        if (!hasOverlay || !hasAccessibility) {
            Toast.makeText(
                this,
                "اول از صفحه‌ی اصلی، مجوز «نمایش روی سایر برنامه‌ها» و «سرویس Accessibility» رو فعال کن",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        return true
    }

    private fun startPointPicking() {
        if (!hasRequiredPermissions()) return
        saveIntervalFromInput()

        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START_SIMPLE_POINT_PICKING
        }
        startForegroundService(intent)
        Toast.makeText(
            this,
            "به اپ هدف برو و روی هر نقطه‌ای که می‌خوای کلیک بشه تپ کن — به‌ترتیبی که تپ می‌کنی ثبت می‌شن. برای پایان، دکمه‌ی سبزِ بالای صفحه رو بزن.",
            Toast.LENGTH_LONG
        ).show()
        moveTaskToBack(true)
    }

    /** فاز ۱۲: نمایشِ مارکرهای شماره‌دار روی مختصاتِ ثبت‌شده، بدون هیچ کلیکی. */
    private fun previewPoints() {
        if (!hasRequiredPermissions()) return
        if (SimpleModeStore.getPoints(this).isEmpty()) {
            Toast.makeText(this, "اول حداقل یک نقطه اضافه کن", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_PREVIEW_POINTS
        }
        startForegroundService(intent)
        Toast.makeText(this, "پیش‌نمایش نشون داده شد — به اپ هدف برگرد", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }

    private fun startSimpleMode() {
        if (!hasRequiredPermissions()) return
        saveIntervalFromInput()

        if (SimpleModeStore.getPoints(this).isEmpty()) {
            Toast.makeText(this, "اول حداقل یک نقطه اضافه کن", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START_SIMPLE_LOOP
        }
        startForegroundService(intent)
        Toast.makeText(this, "حالت ساده شروع شد — به اپ هدف برگرد", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun stopSimpleMode() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP_SIMPLE_LOOP
        }
        startForegroundService(intent)
        Toast.makeText(this, "حالت ساده متوقف شد", Toast.LENGTH_SHORT).show()
    }

    /**
     * فاز ۱۲ — drag-to-reorder: فقط بالا/پایین (dragDirs)، بدون swipe-to-dismiss
     * (حذف از قبل یک دکمه‌ی جدا داره، قاطی‌کردنش با swipe فقط باعثِ حذفِ
     * تصادفی می‌شه). هر move فقط لیستِ نمایشیِ آداپتر رو جابه‌جا می‌کنه؛
     * ذخیره‌ی واقعی در SimpleModeStore وقتیه که کاربر انگشتش رو برمی‌داره
     * (onDrop/clearView) — نه با هر پیکسل جابه‌جایی، که فشار بی‌مورد به
     * SharedPreferences می‌زد.
     */
    private inner class ReorderCallback(private val adapter: SimplePointAdapter) :
        ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            // swipe غیرفعاله (swipeDirs = 0)، پس این هیچ‌وقت صدا زده نمی‌شه.
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            adapter.commitOrder()
        }
    }

    private inner class SimplePointAdapter : RecyclerView.Adapter<SimplePointAdapter.ViewHolder>() {
        // لیستِ محلیِ قابل‌تغییر برای نمایشِ زنده‌ی drag، جدا از SimpleModeStore
        // (که فقط در commitOrder/refresh باهاش هماهنگ می‌شه).
        private var points: MutableList<SimplePoint> = mutableListOf()
        var itemTouchHelper: ItemTouchHelper? = null

        fun refresh() {
            points = SimpleModeStore.getPoints(this@SimpleModeActivity).toMutableList()
            notifyDataSetChanged()
        }

        /** جابه‌جاییِ بصریِ دو آیتم حین درگ — بدون نوشتن در Store (نگاه کن به commitOrder). */
        fun moveItem(from: Int, to: Int) {
            if (from < 0 || to < 0 || from >= points.size || to >= points.size) return
            Collections.swap(points, from, to)
            notifyItemMoved(from, to)
        }

        /** انگشت برداشته شد — الان ترتیبِ نهایی رو در SimpleModeStore ذخیره می‌کنیم. */
        fun commitOrder() {
            SimpleModeStore.setPoints(this@SimpleModeActivity, points)
            notifyDataSetChanged() // برای به‌روزشدنِ شماره‌های نمایشی (نقطه‌ی ۱، ۲، ...)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvLabel: TextView = view.findViewById(R.id.tvSimplePointLabel)
            val btnDelete: Button = view.findViewById(R.id.btnDeleteSimplePoint)
            val dragHandle: TextView = view.findViewById(R.id.tvDragHandle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_simple_point, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount() = points.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val point = points[position]
            holder.tvLabel.text = "نقطه‌ی ${position + 1}: (${point.x.toInt()}, ${point.y.toInt()})"
            holder.btnDelete.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                SimpleModeStore.removePointAt(this@SimpleModeActivity, pos)
                refresh()
            }
            holder.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper?.startDrag(holder)
                }
                false
            }
        }
    }
}
