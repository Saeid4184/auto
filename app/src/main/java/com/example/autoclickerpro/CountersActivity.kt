package com.example.autoclickerpro

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.autoclickerpro.databinding.ActivityCountersBinding

/**
 * فاز ۱۵ — نمایش/مدیریتِ شمارنده‌ها (CounterStore). خودِ شمارنده‌ها با
 * اکشنِ INCREMENT_COUNTER/DECREMENT_COUNTER/RESET_COUNTER روی قوانین ساخته
 * و تغییر می‌کنن (نه از این صفحه)؛ این‌جا فقط برای دیدنِ مقدارِ فعلی، صفر
 * کردنِ دستی، حذفِ کامل، یا ساختنِ یک شمارنده‌ی جدید با مقدارِ شروعِ دلخواه
 * (مثلاً برای تست) هست — بدون این صفحه، شمارنده‌ها فقط از طریق Logcat
 * قابل‌دیدن بودن، که برای یک کاربرِ عادی عملاً نامرئی حساب می‌شه.
 */
class CountersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCountersBinding
    private lateinit var adapter: CounterListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCountersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = CounterListAdapter()
        binding.listCounters.adapter = adapter

        binding.btnNewCounter.setOnClickListener { showCreateDialog() }
    }

    override fun onResume() {
        super.onResume()
        adapter.refresh()
    }

    private fun showCreateDialog() {
        val nameInput = EditText(this).apply { hint = "نام شمارنده" }
        val valueInput = EditText(this).apply {
            hint = "مقدار شروع (پیش‌فرض ۰)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(nameInput)
            addView(valueInput)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("شمارنده‌ی جدید")
            .setView(container)
            .setPositiveButton("ساخت") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "نام نمی‌تونه خالی باشه", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val startValue = valueInput.text.toString().toIntOrNull() ?: 0
                CounterStore.set(this, name, startValue)
                adapter.refresh()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private inner class CounterListAdapter : BaseAdapter() {
        private var counters: List<Pair<String, Int>> = emptyList()

        fun refresh() {
            counters = CounterStore.getAll(this@CountersActivity).toList().sortedBy { it.first }
            binding.tvEmptyCounters.visibility = if (counters.isEmpty()) View.VISIBLE else View.GONE
            notifyDataSetChanged()
        }

        override fun getCount() = counters.size
        override fun getItem(position: Int) = counters[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@CountersActivity)
                .inflate(R.layout.item_counter, parent, false)

            val (name, value) = counters[position]
            view.findViewById<TextView>(R.id.tvCounterName).text = name
            view.findViewById<TextView>(R.id.tvCounterValue).text = value.toString()

            view.findViewById<android.widget.Button>(R.id.btnResetCounter).setOnClickListener {
                CounterStore.reset(this@CountersActivity, name)
                refresh()
            }
            view.findViewById<android.widget.Button>(R.id.btnDeleteCounter).setOnClickListener {
                CounterStore.delete(this@CountersActivity, name)
                refresh()
            }

            return view
        }
    }
}
