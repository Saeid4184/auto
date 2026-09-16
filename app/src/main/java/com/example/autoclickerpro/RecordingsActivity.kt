package com.example.autoclickerpro

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.autoclickerpro.databinding.ActivityRecordingsBinding

/**
 * فاز ۵ — مدیریت ضبط‌های لمسی: هرکدوم یعنی «توالی دقیق لمس‌هایی که کاربر
 * روی صفحه انجام داده»، کاملاً مستقل از تشخیص المان/تصویر. این‌جا می‌شه
 * پخش، تغییرنام یا حذفشون کرد.
 *
 * برای *ساختن* ضبط جدید باید از منوی دکمه‌ی شناور («⏺ ضبط حرکات لمسی»)
 * استفاده کرد، چون ضبط باید حین کار واقعی با اپ هدف انجام بشه، نه از
 * داخل همین اپ.
 */
class RecordingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsBinding
    private lateinit var adapter: RecordingListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = RecordingListAdapter()
        binding.listRecordings.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        adapter.refresh()
    }

    private fun showRenameDialog(recording: Recording) {
        val input = EditText(this)
        input.setText(recording.name)
        MaterialAlertDialogBuilder(this)
            .setTitle("تغییر نام ضبط")
            .setView(input)
            .setPositiveButton("ذخیره") { _, _ ->
                RecordingStore.rename(this, recording.id, input.text.toString().trim())
                adapter.refresh()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun confirmDelete(recording: Recording) {
        MaterialAlertDialogBuilder(this)
            .setTitle("حذف ضبط")
            .setMessage("ضبط «${recording.name}» حذف بشه؟")
            .setPositiveButton("حذف") { _, _ ->
                RecordingStore.delete(this, recording.id)
                adapter.refresh()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    /** پخش از داخل خودِ اپ: اول برمی‌گردیم به پس‌زمینه تا اپ هدف روی صفحه باشه. */
    private fun play(recording: Recording) {
        val svc = ClickAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(
                this,
                "اول باید سرویس Accessibility فعال باشه و اپ هدف باز باشه",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Toast.makeText(this, "پخش «${recording.name}» شروع شد — به اپ هدف برگرد", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
        svc.playRecording(recording) {
            runOnUiThread {
                Toast.makeText(this, "پخش «${recording.name}» تمام شد", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inner class RecordingListAdapter : BaseAdapter() {
        private var recordings: List<Recording> = emptyList()

        fun refresh() {
            recordings = RecordingStore.getAll(this@RecordingsActivity)
            notifyDataSetChanged()
        }

        override fun getCount() = recordings.size
        override fun getItem(position: Int) = recordings[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@RecordingsActivity)
                .inflate(R.layout.item_recording, parent, false)

            val recording = recordings[position]
            view.findViewById<TextView>(R.id.tvRecordingName).text = recording.name
            view.findViewById<TextView>(R.id.tvRecordingSummary).text = recording.describe()
            view.findViewById<Button>(R.id.btnPlayRecording).setOnClickListener { play(recording) }
            view.findViewById<Button>(R.id.btnRenameRecording).setOnClickListener { showRenameDialog(recording) }
            view.findViewById<Button>(R.id.btnDeleteRecording).setOnClickListener { confirmDelete(recording) }

            return view
        }
    }
}
