package com.example.autoclickerpro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * فاز ۴ — مدیریت سناریوها.
 *
 * قبلاً (فاز ۲) فقط یک لیست تخت از قوانین وجود داشت (RuleStore). حالا اون
 * لیست، لیستِ قوانینِ "سناریوی فعال" شده؛ کاربر می‌تونه چند سناریوی جدا
 * بسازه، بینشون سوییچ کنه، و هرکدوم رو مستقل مدیریت کنه (تغییرنام،
 * کپی، حذف). ذخیره‌سازی مثل قبل با JSON در SharedPreferences انجام می‌شه —
 * فقط این بار یک آرایه از سناریوها به‌جای یک آرایه از قوانین.
 *
 * برای سازگاری با کدهای قبلی (OverlayService، ClickAccessibilityService،
 * RulesActivity)، RuleStore دست‌نخورده می‌مونه و فقط به‌عنوان یک واسطه‌ی نازک
 * روی "سناریوی فعال" این کلاس عمل می‌کنه.
 */
object ScenarioStore {
    private const val PREFS = "autoclicker_prefs"
    private const val KEY_SCENARIOS = "scenarios_json"
    private const val KEY_ACTIVE_ID = "active_scenario_id"

    // کلیدهای نسخه‌ی قدیمی فاز ۲ (تک‌سناریویی) — فقط برای مهاجرت خودکار لازمن
    private const val LEGACY_KEY_RULES = "rules_json"
    private const val LEGACY_KEY_INTERVAL_MS = "loop_interval_ms"

    private val scenarios = mutableListOf<Scenario>()
    private var activeId: String = ""
    private var initialized = false

    @Synchronized
    private fun ensureInit(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val json = prefs.getString(KEY_SCENARIOS, null)
        if (json != null) {
            runCatching {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    scenarios.add(scenarioFromJson(arr.getJSONObject(i)))
                }
            }
        }

        if (scenarios.isEmpty()) {
            // یا این اولین اجراست، یا از فاز ۲ (تک‌سناریویی) مهاجرت می‌کنیم.
            val migratedRules = migrateLegacyRules(prefs)
            val defaultScenario = Scenario(
                id = UUID.randomUUID().toString(),
                name = if (migratedRules.isNotEmpty()) "سناریوی قبلی" else "سناریوی پیش‌فرض",
                rules = migratedRules.toMutableList(),
                loopIntervalMs = prefs.getLong(LEGACY_KEY_INTERVAL_MS, 1500L)
            )
            scenarios.add(defaultScenario)
            activeId = defaultScenario.id
            persist(context)
        } else {
            activeId = prefs.getString(KEY_ACTIVE_ID, null) ?: scenarios.first().id
            if (scenarios.none { it.id == activeId }) {
                activeId = scenarios.first().id
            }
        }
    }

    private fun migrateLegacyRules(prefs: android.content.SharedPreferences): List<ClickRule> {
        val json = prefs.getString(LEGACY_KEY_RULES, null) ?: return emptyList()
        val result = mutableListOf<ClickRule>()
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(ruleFromJson(obj))
            }
        }
        return result
    }

    // ---------- خواندن ----------

    @Synchronized
    fun getAllScenarios(context: Context): List<Scenario> {
        ensureInit(context)
        return scenarios.toList()
    }

    @Synchronized
    fun getActiveScenario(context: Context): Scenario {
        ensureInit(context)
        return scenarios.first { it.id == activeId }
    }

    @Synchronized
    fun getActiveScenarioId(context: Context): String {
        ensureInit(context)
        return activeId
    }

    // ---------- مدیریت سناریوها ----------

    @Synchronized
    fun createScenario(context: Context, name: String): Scenario {
        ensureInit(context)
        val scenario = Scenario(id = UUID.randomUUID().toString(), name = name.ifBlank { "سناریوی بدون نام" })
        scenarios.add(scenario)
        persist(context)
        return scenario
    }

    @Synchronized
    fun duplicateScenario(context: Context, id: String): Scenario? {
        ensureInit(context)
        val original = scenarios.find { it.id == id } ?: return null
        // باگ رفع‌شده: قوانین IMAGE قبلاً مستقیماً کپی می‌شدن و matchValue
        // (مسیر فایل تصویر) رو بدون تغییر نگه می‌داشتن؛ یعنی سناریوی اصلی و
        // کپی‌اش یک فایل تصویر رو به اشتراک می‌ذاشتن. حذف اون قانون/سناریو در
        // یکی از دوتا، فایل رو پاک می‌کرد و اون‌یکی رو خاموش (و برای همیشه
        // ناموفق در تشخیص تصویر) می‌کرد. حالا برای هر قانون IMAGE یک فایل
        // تصویر جدید و مستقل ساخته می‌شه.
        //
        // باگِ ناقص‌بودنِ همین رفع رو هم رفع کردیم: فیکس بالا فقط matchValue
        // (الگوی اصلی) رو مستقل می‌کرد، ولی از فاز ۹ به بعد یک قانون IMAGE
        // می‌تونه چند الگوی اضافه هم در imageTemplatePaths داشته باشه — که
        // قبلاً کپی نمی‌شدن و همچنان بین سناریوی اصلی و کپی مشترک می‌موندن
        // (دقیقاً همون باگِ قبلی، فقط برای فیلد جدیدتر). حالا هر مسیر توی
        // imageTemplatePaths هم جدا کپی می‌شه.
        val copiedRules = original.rules.map { rule ->
            if (rule.matchType == MatchType.IMAGE) {
                val newPath = TemplateStore.copyTemplate(context, rule.matchValue) ?: rule.matchValue
                val newExtraPaths = rule.imageTemplatePaths.map { p ->
                    TemplateStore.copyTemplate(context, p) ?: p
                }
                rule.copy(id = UUID.randomUUID().toString(), matchValue = newPath, imageTemplatePaths = newExtraPaths)
            } else {
                rule.copy(id = UUID.randomUUID().toString())
            }
        }.toMutableList()
        val copy = Scenario(
            id = UUID.randomUUID().toString(),
            name = "${original.name} (کپی)",
            rules = copiedRules,
            loopIntervalMs = original.loopIntervalMs
        )
        scenarios.add(copy)
        persist(context)
        return copy
    }

    @Synchronized
    fun renameScenario(context: Context, id: String, newName: String) {
        ensureInit(context)
        scenarios.find { it.id == id }?.name = newName.ifBlank { "سناریوی بدون نام" }
        persist(context)
    }

    @Synchronized
    fun deleteScenario(context: Context, id: String) {
        ensureInit(context)
        if (scenarios.size <= 1) return // حداقل یک سناریو باید باقی بمونه
        val target = scenarios.find { it.id == id }
        scenarios.removeAll { it.id == id }
        if (activeId == id) {
            activeId = scenarios.first().id
        }
        // باگ رفع‌شده: قبلاً پاک‌سازی فایل‌های الگوی تصویری این سناریو به عهده‌ی
        // ScenariosActivity گذاشته شده بود و فقط matchValue (نه
        // imageTemplatePaths) رو پاک می‌کرد. حالا همون منطقِ مرکزیِ removeRule
        // برای هر قانونِ سناریو استفاده می‌شه، پس هیچ فایلی یتیم نمی‌مونه.
        target?.rules?.forEach { deleteRuleTemplateFiles(it) }
        persist(context)
    }

    @Synchronized
    fun setActiveScenario(context: Context, id: String) {
        ensureInit(context)
        if (scenarios.any { it.id == id }) {
            activeId = id
            persist(context)
        }
    }

    // ---------- مدیریت قوانین داخل سناریوی فعال (استفاده‌شده توسط RuleStore) ----------

    @Synchronized
    fun getRules(context: Context): List<ClickRule> {
        ensureInit(context)
        return getActiveScenario(context).rules.toList()
    }

    @Synchronized
    fun addRule(context: Context, rule: ClickRule) {
        ensureInit(context)
        getActiveScenario(context).rules.add(rule)
        persist(context)
    }

    @Synchronized
    fun removeRule(context: Context, ruleId: String) {
        ensureInit(context)
        val rules = getActiveScenario(context).rules
        val removed = rules.find { it.id == ruleId }
        rules.removeAll { it.id == ruleId }
        if (removed != null) deleteRuleTemplateFiles(removed)
        persist(context)
    }

    /**
     * باگ رفع‌شده: قبلاً حذفِ یک قانون IMAGE (چه از «مدیریت قوانین کلیک»،
     * چه با حذف کل سناریو) فقط فایلِ matchValue (الگوی اصلی) رو پاک می‌کرد؛
     * الگوهای اضافه‌ی فاز ۹ در imageTemplatePaths هیچ‌وقت پاک نمی‌شدن و
     * برای همیشه روی دیسک باقی می‌موندن (فایل یتیم/هدررفتِ حافظه). این تابع
     * هر دو رو با هم پاک می‌کنه و تنها جای مرکزیِ این منطقه‌ست — RulesActivity
     * و ScenariosActivity دیگه نیازی به تکرار این منطق ندارن.
     */
    private fun deleteRuleTemplateFiles(rule: ClickRule) {
        if (rule.matchType != MatchType.IMAGE) return
        TemplateStore.deleteTemplate(rule.matchValue)
        for (p in rule.imageTemplatePaths) TemplateStore.deleteTemplate(p)
    }

    @Synchronized
    fun setRuleEnabled(context: Context, ruleId: String, enabled: Boolean) {
        ensureInit(context)
        getActiveScenario(context).rules.find { it.id == ruleId }?.enabled = enabled
        persist(context)
    }

    /**
     * فاز ۹ — جایگزینی کامل یک قانون موجود (برای دیالوگ «تنظیمات پیشرفته»:
     * زنجیره‌ی شرطی، تاخیر تصادفی، انتظار تا ناپدیدی، الگوهای تصویری اضافه،
     * رنگ پیکسل، OCR). بر اساس id قانون قدیمی پیدا و با نسخه‌ی جدید عوض
     * می‌شه؛ اگه پیدا نشه (مثلاً هم‌زمان حذف شده)، بی‌سروصدا کاری نمی‌کنه.
     */
    @Synchronized
    fun updateRule(context: Context, updated: ClickRule) {
        ensureInit(context)
        val rules = getActiveScenario(context).rules
        val idx = rules.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            rules[idx] = updated
            persist(context)
        }
    }

    /**
     * فاز ۷ — اولویت دستی: حلقه‌ی کلیک (OverlayService.clickLoopRunnable) قوانین
     * فعال رو دقیقاً به همون ترتیبی که در این لیست هستن چک می‌کنه. این مهم‌ترین
     * موردیه که وقتی چند قانون IMAGE هم‌زمان فعالن اهمیت پیدا می‌کنه — مثلاً
     * می‌خوای اول دنبال دکمه‌ی «رد کردن تبلیغ» بگرده، بعد دنبال دکمه‌ی اصلی
     * بازی، نه برعکس. moveRuleUp/Down جای دو قانون همسایه رو در لیست عوض
     * می‌کنه (بدون نیاز به فیلد عددی priority جداگانه).
     */
    @Synchronized
    fun moveRuleUp(context: Context, ruleId: String) {
        ensureInit(context)
        val rules = getActiveScenario(context).rules
        val idx = rules.indexOfFirst { it.id == ruleId }
        if (idx > 0) {
            val tmp = rules[idx]
            rules[idx] = rules[idx - 1]
            rules[idx - 1] = tmp
            persist(context)
        }
    }

    @Synchronized
    fun moveRuleDown(context: Context, ruleId: String) {
        ensureInit(context)
        val rules = getActiveScenario(context).rules
        val idx = rules.indexOfFirst { it.id == ruleId }
        if (idx in 0 until rules.size - 1) {
            val tmp = rules[idx]
            rules[idx] = rules[idx + 1]
            rules[idx + 1] = tmp
            persist(context)
        }
    }

    @Synchronized
    fun getLoopIntervalMs(context: Context): Long {
        ensureInit(context)
        return getActiveScenario(context).loopIntervalMs
    }

    @Synchronized
    fun setLoopIntervalMs(context: Context, ms: Long) {
        ensureInit(context)
        getActiveScenario(context).loopIntervalMs = ms
        persist(context)
    }

    // ---------- فاز ۶: Export/Import سناریو (نقشه‌ی راه فاز ۴) ----------

    /**
     * سناریو رو به یک رشته‌ی JSON قابل‌اشتراک‌گذاری تبدیل می‌کنه. برای قوانین
     * IMAGE، چون مسیر فایل محلی (matchValue) روی گوشیِ مقصد معنایی نداره،
     * خودِ تصویر الگو به‌صورت Base64 داخل JSON جاسازی می‌شه — یعنی خروجی
     * می‌تونه برای سناریوهای پر از قانون تصویری نسبتاً حجیم بشه.
     */
    @Synchronized
    fun exportScenarioJson(context: Context, id: String): String? {
        ensureInit(context)
        val scenario = scenarios.find { it.id == id } ?: return null
        val obj = JSONObject()
        obj.put("exportFormat", "autoclickerpro_scenario_v1")
        obj.put("name", scenario.name)
        obj.put("loopIntervalMs", scenario.loopIntervalMs)
        val rulesArr = JSONArray()
        for (r in scenario.rules) {
            val ruleObj = ruleToJson(r)
            if (r.matchType == MatchType.IMAGE) {
                val bmp = TemplateStore.loadTemplate(context, r.matchValue)
                if (bmp != null) {
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    ruleObj.put("imageDataBase64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                }
                // فاز ۹: اگه چند الگوی اولویت‌بندی‌شده داره، هرکدوم رو هم به همین
                // شکل Base64 جاسازی می‌کنیم (به همون ترتیب imageTemplatePaths).
                if (r.imageTemplatePaths.isNotEmpty()) {
                    val extraArr = JSONArray()
                    for (path in r.imageTemplatePaths) {
                        val extraBmp = TemplateStore.loadTemplate(context, path)
                        if (extraBmp != null) {
                            val extraOut = ByteArrayOutputStream()
                            extraBmp.compress(Bitmap.CompressFormat.PNG, 100, extraOut)
                            extraArr.put(Base64.encodeToString(extraOut.toByteArray(), Base64.NO_WRAP))
                        } else {
                            extraArr.put(JSONObject.NULL) // نگه‌داشتن جای خالی تا ایندکس‌ها جابه‌جا نشن
                        }
                    }
                    ruleObj.put("imageTemplatesBase64", extraArr)
                }
            }
            rulesArr.put(ruleObj)
        }
        obj.put("rules", rulesArr)
        return obj.toString()
    }

    /**
     * از رشته‌ی JSON خروجی‌گرفته‌شده‌ی exportScenarioJson، یک سناریوی جدید
     * (با idهای کاملاً تازه، هم برای خودِ سناریو هم برای هر قانون، تا با
     * سناریوهای موجود تداخل نکنه) می‌سازه و به لیست اضافه می‌کنه. قوانین
     * IMAGE که تصویرشون قابل بازسازی نبود (JSON خراب/ناقص) به‌صورت غیرفعال
     * وارد می‌شن تا کاربر متوجه بشه باید دوباره الگو رو بگیره.
     */
    @Synchronized
    fun importScenarioJson(context: Context, json: String): Scenario? {
        ensureInit(context)
        return runCatching {
            val obj = JSONObject(json)
            val rulesArr = obj.optJSONArray("rules") ?: JSONArray()
            val rules = mutableListOf<ClickRule>()
            for (i in 0 until rulesArr.length()) {
                val ruleObj = rulesArr.getJSONObject(i)
                var rule = ruleFromJson(ruleObj).copy(id = UUID.randomUUID().toString())
                if (rule.matchType == MatchType.IMAGE) {
                    val b64 = ruleObj.optString("imageDataBase64", "")
                    val bmp = if (b64.isNotEmpty()) {
                        runCatching {
                            val bytes = Base64.decode(b64, Base64.NO_WRAP)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }.getOrNull()
                    } else null
                    rule = if (bmp != null) {
                        rule.copy(matchValue = TemplateStore.saveTemplate(context, bmp))
                    } else {
                        // تصویر همراه JSON نبود یا خراب بود — قانون غیرفعال وارد می‌شه
                        rule.copy(enabled = false)
                    }

                    // فاز ۹: بازسازی الگوهای اضافه (اولویت‌بندی‌شده). هر کدوم که
                    // دیکد نشد، از لیست حذف می‌شه (نه اینکه کل قانون غیرفعال بشه —
                    // چون الگوی اصلی/دیگر الگوها ممکنه سالم باشن).
                    val extraArr = ruleObj.optJSONArray("imageTemplatesBase64")
                    if (extraArr != null) {
                        val restoredPaths = mutableListOf<String>()
                        for (i in 0 until extraArr.length()) {
                            val entryB64 = extraArr.optString(i, "")
                            val entryBmp = if (entryB64.isNotEmpty()) {
                                runCatching {
                                    val bytes = Base64.decode(entryB64, Base64.NO_WRAP)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                }.getOrNull()
                            } else null
                            if (entryBmp != null) {
                                restoredPaths.add(TemplateStore.saveTemplate(context, entryBmp))
                            } else {
                                Log.w("ScenarioStore", "الگوی تصویریِ اضافه (اولویت $i) از JSON بازسازی نشد")
                            }
                        }
                        rule = rule.copy(imageTemplatePaths = restoredPaths)
                    }
                }
                rules.add(rule)
            }
            val name = obj.optString("name", "سناریوی وارد‌شده")
            val scenario = Scenario(
                id = UUID.randomUUID().toString(),
                name = "$name (وارد‌شده)",
                rules = rules,
                loopIntervalMs = obj.optLong("loopIntervalMs", 1500L)
            )
            scenarios.add(scenario)
            persist(context)
            scenario
        }.onFailure { Log.e("ScenarioStore", "خطا در وارد کردن سناریو از JSON", it) }.getOrNull()
    }

    // ---------- (de)serialization ----------

    private fun ruleFromJson(obj: JSONObject): ClickRule {
        val templatesArr = obj.optJSONArray("imageTemplatePaths")
        val templates = mutableListOf<String>()
        if (templatesArr != null) {
            for (i in 0 until templatesArr.length()) templates.add(templatesArr.getString(i))
        }
        return ClickRule(
            id = obj.getString("id"),
            matchType = MatchType.valueOf(obj.getString("matchType")),
            matchValue = obj.getString("matchValue"),
            actionType = ActionType.valueOf(obj.optString("actionType", "CLICK")),
            label = obj.optString("label", obj.getString("matchValue")),
            enabled = obj.optBoolean("enabled", true),
            threshold = obj.optDouble("threshold", 0.85),
            // فاز ۶: قوانین قدیمی (فاز ۱-۵) این کلیدها رو ندارن؛ null/۳۰۰ پیش‌فرض امنه
            // چون فقط برای actionType == SWIPE خونده می‌شن.
            swipeEndValue = obj.optString("swipeEndValue", "").ifBlank { null },
            swipeDurationMs = obj.optLong("swipeDurationMs", 300L),
            // فاز ۹: همه‌ی کلیدهای زیر در قوانین قدیمی‌تر (فاز ۱-۸) وجود ندارن؛
            // پیش‌فرض‌های امن (خنثی، بدون تغییر رفتار قبلی) استفاده می‌شن.
            imageTemplatePaths = templates,
            targetColorHex = obj.optString("targetColorHex", "#FFFFFF"),
            colorTolerance = obj.optInt("colorTolerance", 12),
            ocrRegion = obj.optString("ocrRegion", "").ifBlank { null },
            ocrClickPoint = obj.optString("ocrClickPoint", "").ifBlank { null },
            ocrPreprocess = obj.optBoolean("ocrPreprocess", true),
            postClickDelayMinMs = obj.optLong("postClickDelayMinMs", 0L),
            postClickDelayMaxMs = obj.optLong("postClickDelayMaxMs", 0L),
            waitForDisappearAfterClick = obj.optBoolean("waitForDisappearAfterClick", false),
            waitForDisappearTimeoutMs = obj.optLong("waitForDisappearTimeoutMs", 5000L),
            waitForDisappearPollMs = obj.optLong("waitForDisappearPollMs", 250L),
            elseRuleId = obj.optString("elseRuleId", "").ifBlank { null },
            // فاز ۱۵: شمارنده + اکشن اندروید — پیش‌فرض‌های امن برای قوانینِ قدیمی‌تر.
            counterConditionName = obj.optString("counterConditionName", "").ifBlank { null },
            counterConditionComparison = runCatching {
                CounterComparison.valueOf(obj.optString("counterConditionComparison", "GREATER_OR_EQUAL"))
            }.getOrDefault(CounterComparison.GREATER_OR_EQUAL),
            counterConditionValue = obj.optInt("counterConditionValue", 0),
            counterActionName = obj.optString("counterActionName", "").ifBlank { null },
            counterActionDelta = obj.optInt("counterActionDelta", 1),
            intentActionKind = runCatching {
                IntentActionKind.valueOf(obj.optString("intentActionKind", "OPEN_URL"))
            }.getOrDefault(IntentActionKind.OPEN_URL),
            intentTarget = obj.optString("intentTarget", "").ifBlank { null }
        )
    }

    private fun ruleToJson(r: ClickRule): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("matchType", r.matchType.name)
        put("matchValue", r.matchValue)
        put("actionType", r.actionType.name)
        put("label", r.label)
        put("enabled", r.enabled)
        put("threshold", r.threshold)
        if (r.swipeEndValue != null) put("swipeEndValue", r.swipeEndValue)
        put("swipeDurationMs", r.swipeDurationMs)
        // فاز ۹
        if (r.imageTemplatePaths.isNotEmpty()) {
            val arr = JSONArray()
            for (p in r.imageTemplatePaths) arr.put(p)
            put("imageTemplatePaths", arr)
        }
        put("targetColorHex", r.targetColorHex)
        put("colorTolerance", r.colorTolerance)
        if (r.ocrRegion != null) put("ocrRegion", r.ocrRegion)
        if (r.ocrClickPoint != null) put("ocrClickPoint", r.ocrClickPoint)
        put("ocrPreprocess", r.ocrPreprocess)
        put("postClickDelayMinMs", r.postClickDelayMinMs)
        put("postClickDelayMaxMs", r.postClickDelayMaxMs)
        put("waitForDisappearAfterClick", r.waitForDisappearAfterClick)
        put("waitForDisappearTimeoutMs", r.waitForDisappearTimeoutMs)
        put("waitForDisappearPollMs", r.waitForDisappearPollMs)
        if (r.elseRuleId != null) put("elseRuleId", r.elseRuleId)
        // فاز ۱۵
        if (r.counterConditionName != null) {
            put("counterConditionName", r.counterConditionName)
            put("counterConditionComparison", r.counterConditionComparison.name)
            put("counterConditionValue", r.counterConditionValue)
        }
        if (r.counterActionName != null) {
            put("counterActionName", r.counterActionName)
            put("counterActionDelta", r.counterActionDelta)
        }
        if (r.intentTarget != null) {
            put("intentActionKind", r.intentActionKind.name)
            put("intentTarget", r.intentTarget)
        }
    }

    private fun scenarioFromJson(obj: JSONObject): Scenario {
        val rulesArr = obj.optJSONArray("rules") ?: JSONArray()
        val rules = mutableListOf<ClickRule>()
        for (i in 0 until rulesArr.length()) {
            rules.add(ruleFromJson(rulesArr.getJSONObject(i)))
        }
        return Scenario(
            id = obj.getString("id"),
            name = obj.getString("name"),
            rules = rules,
            loopIntervalMs = obj.optLong("loopIntervalMs", 1500L)
        )
    }

    private fun scenarioToJson(s: Scenario): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("loopIntervalMs", s.loopIntervalMs)
        val rulesArr = JSONArray()
        for (r in s.rules) rulesArr.put(ruleToJson(r))
        put("rules", rulesArr)
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        for (s in scenarios) arr.put(scenarioToJson(s))
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCENARIOS, arr.toString())
            .putString(KEY_ACTIVE_ID, activeId)
            // کلیدهای قدیمی فاز ۲ رو پاک می‌کنیم تا دوباره مهاجرت انجام نشه
            .remove(LEGACY_KEY_RULES)
            .apply()
    }
}
