package com.example.autoclickerpro

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.autoclickerpro.databinding.ActivityScenariosBinding

/**
 * فاز ۴ — مدیریت سناریوها: «اگه X رو دیدی، Y کار رو بکن» می‌تونه شامل چند
 * قانون باشه، و کاربر می‌تونه چند دسته از این قوانین (چند سناریو) بسازه —
 * مثلاً یکی برای یک بازی، یکی برای اپ دیگه — و هر بار فقط یکی رو فعال کنه.
 * فقط قوانینِ سناریوی فعال توسط OverlayService اجرا می‌شن.
 */
class ScenariosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScenariosBinding
    private lateinit var adapter: ScenarioListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScenariosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.btnNewScenario.setOnClickListener { showCreateDialog() }
        binding.btnImportScenario.setOnClickListener { showImportDialog() }

        adapter = ScenarioListAdapter()
        binding.listScenarios.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        adapter.refresh()
    }

    private fun showCreateDialog() {
        val input = EditText(this)
        input.hint = "مثلاً: بازی X یا اپ Y"
        MaterialAlertDialogBuilder(this)
            .setTitle("سناریوی جدید")
            .setView(input)
            .setPositiveButton("ساخت") { _, _ ->
                val name = input.text.toString().trim()
                ScenarioStore.createScenario(this, name.ifBlank { "سناریوی بدون نام" })
                adapter.refresh()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun showRenameDialog(scenario: Scenario) {
        val input = EditText(this)
        input.setText(scenario.name)
        MaterialAlertDialogBuilder(this)
            .setTitle("تغییر نام سناریو")
            .setView(input)
            .setPositiveButton("ذخیره") { _, _ ->
                ScenarioStore.renameScenario(this, scenario.id, input.text.toString().trim())
                adapter.refresh()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun confirmDelete(scenario: Scenario) {
        if (ScenarioStore.getAllScenarios(this).size <= 1) {
            Toast.makeText(this, "حداقل یک سناریو باید باقی بمونه", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("حذف سناریو")
            .setMessage("سناریوی «${scenario.name}» و همه‌ی ${scenario.rules.size} قانونش حذف بشه؟")
            .setPositiveButton("حذف") { _, _ ->
                // پاک‌سازی فایل‌های الگوی تصویری (اصلی + الگوهای اضافه‌ی فاز ۹)
                // حالا مرکزی و کامل داخل ScenarioStore.deleteScenario انجام می‌شه.
                ScenarioStore.deleteScenario(this, scenario.id)
                adapter.refresh()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    // ---------- فاز ۶: Export/Import سناریو با JSON ----------

    /**
     * JSON خروجی رو (شامل تصاویر الگو به‌صورت Base64، اگه سناریو قانون IMAGE
     * داشته باشه) از طریق منوی اشتراک‌گذاری اندروید می‌فرسته — مثلاً به
     * تلگرام/ایمیل/فایل. محدودیت: چون از ACTION_SEND با متن ساده استفاده
     * می‌شه (بدون FileProvider اضافه)، برای سناریوهای با چند قانون IMAGE
     * حجیم، بعضی اپ‌های مقصد ممکنه متن خیلی بزرگ رو قبول نکنن — در اون حالت
     * بهتره فقط از طریق «کپی» به یک اپ یادداشت/فایل منتقل بشه.
     */
    private fun exportScenario(scenario: Scenario) {
        val json = ScenarioStore.exportScenarioJson(this, scenario.id)
        if (json == null) {
            Toast.makeText(this, "خروجی گرفتن ناموفق بود", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "سناریوی AutoClicker Pro: ${scenario.name}")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(intent, "اشتراک‌گذاری سناریو"))
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "JSON سناریو رو اینجا پیست کن"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("ورودی سناریو از JSON")
            .setView(input)
            .setPositiveButton("وارد کردن") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "چیزی پیست نشده", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val scenario = ScenarioStore.importScenarioJson(this, text)
                if (scenario == null) {
                    Toast.makeText(this, "JSON نامعتبر بود، وارد نشد", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "سناریوی «${scenario.name}» وارد شد", Toast.LENGTH_SHORT).show()
                    adapter.refresh()
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private inner class ScenarioListAdapter : BaseAdapter() {
        private var scenarios: List<Scenario> = emptyList()
        private var activeId: String = ""

        fun refresh() {
            scenarios = ScenarioStore.getAllScenarios(this@ScenariosActivity)
            activeId = ScenarioStore.getActiveScenarioId(this@ScenariosActivity)
            notifyDataSetChanged()
        }

        override fun getCount() = scenarios.size
        override fun getItem(position: Int) = scenarios[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@ScenariosActivity)
                .inflate(R.layout.item_scenario, parent, false)

            val scenario = scenarios[position]
            val radioActive = view.findViewById<RadioButton>(R.id.radioActiveScenario)
            val tvName = view.findViewById<TextView>(R.id.tvScenarioName)
            val tvSummary = view.findViewById<TextView>(R.id.tvScenarioSummary)
            val btnRename = view.findViewById<Button>(R.id.btnRenameScenario)
            val btnDuplicate = view.findViewById<Button>(R.id.btnDuplicateScenario)
            val btnDelete = view.findViewById<Button>(R.id.btnDeleteScenario)
            val btnExport = view.findViewById<Button>(R.id.btnExportScenario)

            tvName.text = scenario.name
            tvSummary.text = "${scenario.rules.size} قانون · هر ${scenario.loopIntervalMs} میلی‌ثانیه"

            radioActive.setOnCheckedChangeListener(null)
            radioActive.isChecked = scenario.id == activeId
            radioActive.setOnClickListener {
                ScenarioStore.setActiveScenario(this@ScenariosActivity, scenario.id)
                refresh()
            }

            view.setOnClickListener {
                ScenarioStore.setActiveScenario(this@ScenariosActivity, scenario.id)
                refresh()
            }

            btnRename.setOnClickListener { showRenameDialog(scenario) }
            btnDuplicate.setOnClickListener {
                ScenarioStore.duplicateScenario(this@ScenariosActivity, scenario.id)
                refresh()
            }
            btnDelete.setOnClickListener { confirmDelete(scenario) }
            btnExport.setOnClickListener { exportScenario(scenario) }

            return view
        }
    }
}
