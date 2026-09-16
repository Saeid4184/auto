package com.example.autoclickerpro

import android.content.Context

/**
 * فاز ۲: نگه‌داری قوانین کلیک.
 * فاز ۴: این آبجکت دیگه خودش چیزی ذخیره نمی‌کنه — فقط یک واسط نازک (facade)
 * روی "سناریوی فعال" در ScenarioStore هست، تا OverlayService و
 * ClickAccessibilityService و RulesActivity بدون تغییر به همون شکل قبلی کار
 * کنن (همیشه روی قوانینِ سناریویی که الان فعاله عمل می‌کنن).
 * برای ساخت/حذف/سوییچ بین خودِ سناریوها، مستقیماً از ScenarioStore استفاده کن
 * (نگاه کن به ScenariosActivity).
 */
object RuleStore {
    val loopIntervalMs: Long
        get() = lastKnownIntervalMs

    // چون property بدون context باید مقدار برگردونه (برای کدهای قدیمی که
    // RuleStore.loopIntervalMs رو بدون context می‌خوندن)، آخرین مقدار خونده‌شده
    // رو کش می‌کنیم و هر بار getAll/getEnabled صداش می‌کنیم به‌روزش می‌کنیم.
    @Volatile
    private var lastKnownIntervalMs: Long = 1500L

    fun getAll(context: Context): List<ClickRule> {
        lastKnownIntervalMs = ScenarioStore.getLoopIntervalMs(context)
        return ScenarioStore.getRules(context)
    }

    fun getEnabled(context: Context): List<ClickRule> {
        lastKnownIntervalMs = ScenarioStore.getLoopIntervalMs(context)
        return ScenarioStore.getRules(context).filter { it.enabled }
    }

    fun add(context: Context, rule: ClickRule) {
        ScenarioStore.addRule(context, rule)
    }

    fun remove(context: Context, id: String) {
        ScenarioStore.removeRule(context, id)
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        ScenarioStore.setRuleEnabled(context, id, enabled)
    }

    /** فاز ۹: ذخیره‌ی کامل یک قانون ویرایش‌شده (تنظیمات پیشرفته). */
    fun update(context: Context, rule: ClickRule) {
        ScenarioStore.updateRule(context, rule)
    }

    /** فاز ۷: جابه‌جایی قانون در لیست = تغییر اولویتش در حلقه‌ی کلیک. */
    fun moveUp(context: Context, id: String) = ScenarioStore.moveRuleUp(context, id)
    fun moveDown(context: Context, id: String) = ScenarioStore.moveRuleDown(context, id)

    fun setLoopIntervalMs(context: Context, ms: Long) {
        lastKnownIntervalMs = ms
        ScenarioStore.setLoopIntervalMs(context, ms)
    }
}
