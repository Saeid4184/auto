package com.example.autoclickerpro

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.autoclickerpro.databinding.ActivityRunLogBinding

/**
 * فاز ۸ — نمایش تاریخچه‌ی اجرای قوانین (RunLogStore)، جدیدترین در بالا.
 * هم برای دیباگ سناریوهای واقعی مفیده، هم مکمل حالت آزمایشی (فاز ۷) —
 * چون حالت آزمایشی فقط یک Toast لحظه‌ای نشون می‌ده، ولی اینجا کل تاریخچه
 * قابل مرور و مقایسه‌ست.
 */
class RunLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRunLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRunLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.btnClearLog.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("پاک کردن تاریخچه")
                .setMessage("همه‌ی ردیف‌های تاریخچه‌ی اجرا حذف بشن؟")
                .setPositiveButton("پاک کن") { _, _ ->
                    RunLogStore.clear(this)
                    refresh()
                }
                .setNegativeButton("انصراف", null)
                .show()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        // جدیدترین اجرا اول نشون داده بشه
        val entries = RunLogStore.getAll(this).asReversed()
        binding.tvEmptyLog.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.listLog.adapter = object : ArrayAdapter<RunLogEntry>(this, R.layout.item_log_entry, entries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(this@RunLogActivity)
                    .inflate(R.layout.item_log_entry, parent, false)
                view.findViewById<TextView>(R.id.tvLogLine).text = entries[position].describe()
                return view
            }
        }
    }
}
