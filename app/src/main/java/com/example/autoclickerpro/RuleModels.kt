package com.example.autoclickerpro

/**
 * نحوه‌ی تشخیص هدف:
 *  - TEXT: متن قابل‌مشاهده یا content-description المان (از UI Tree)
 *  - VIEW_ID: شناسه‌ی ریسورس المان (مثل com.app:id/btn_ok) — پایدارتر از متن،
 *             چون حتی اگه زبان اپ عوض بشه معمولاً ثابت می‌مونه
 *  - COORDINATE: مختصات ثابت روی صفحه (روش فاز ۱، بدون تشخیص هوشمند)
 *  - IMAGE: تشخیص تصویری با OpenCV (فاز ۳) — برای وقتی که UI Tree جواب نمی‌ده
 *           (مثلاً اپ‌های Unity/Cocos یا بازی‌هایی که UI رو مستقیم روی کانواس
 *           می‌کشن و AccessibilityService چیزی از المان‌هاشون نمی‌بینه).
 *           فاز ۹: حالا می‌تونه چند الگو با اولویت داشته باشه (imageTemplatePaths).
 *  - PIXEL_COLOR: فاز ۹ — به‌جای مقایسه‌ی کل تصویر با یک الگو (که کند و
 *           حساس به نویزه)، فقط رنگ *یک نقطه‌ی مشخص* از فریم صفحه رو با یک
 *           رنگ هدف مقایسه می‌کنه. برای چیزهایی مثل "آیا نوار سلامتی پر شده
 *           (سبز) یا خالی شده (قرمز)" یا "آیا این دکمه فعاله یا خاکستریه"
 *           خیلی سریع‌تر و دقیق‌تر از matchTemplate روی کل تصویره.
 *  - OCR_TEXT: فاز ۹ — با ML Kit Text Recognition متن روی یک ناحیه (یا کل)
 *           صفحه رو می‌خونه و اگه شامل matchValue بود، قانون "دیده‌شده" حساب
 *           می‌شه. برای خوندن امتیاز/وضعیت/شماره‌ی مرحله که به‌صورت متن روی
 *           صفحه‌ست (نه یک آیکون ثابت که matchTemplate بتونه پیداش کنه).
 */
enum class MatchType {
    TEXT, VIEW_ID, COORDINATE, IMAGE, PIXEL_COLOR, OCR_TEXT
}

enum class ActionType {
    // فاز ۶: SWIPE اضافه شد. مبدا = همون نقطه‌ای که matchType پیدا می‌کنه
    // (مختصات ثابت خود قانون، یا مرکز المان/تصویر تشخیص‌داده‌شده)؛ مقصد از
    // روی swipeEndValue خونده می‌شه.
    CLICK, LONG_PRESS, SWIPE,

    // فاز ۱۵ — این سه‌تا اصلاً کلیکی نمی‌زنن؛ matchType/matchValue هنوز
    // تعیین می‌کنه «کِی» این قانون اجرا بشه (می‌تونه هر نوع تشخیصی باشه:
    // متن، تصویر، OCR، یا حتی COORDINATE برای «همیشه»)، ولی به‌جای کلیک،
    // یک شمارنده‌ی نام‌دار (در CounterStore) رو دستکاری می‌کنن. برای
    // چیزهایی مثل «۵ بار این کارو بکن، بعد دیگه نکن» یا «هر ۳ بار یک‌بار
    // یه اکشن دیگه انجام بده» (با ترکیب با counterConditionِ زیر).
    INCREMENT_COUNTER, DECREMENT_COUNTER, RESET_COUNTER,

    // فاز ۱۵ — به‌جای کلیک، یک اکشن اندرویدی انجام می‌ده: باز کردن یک URL،
    // اجرای یک اپ دیگه، یا ارسال یک broadcast. جزئیات در intentActionKind/
    // intentTarget پایین‌تر.
    ANDROID_INTENT
}

/** فاز ۱۵ — نحوه‌ی مقایسه‌ی مقدارِ فعلیِ شمارنده با counterConditionValue. */
enum class CounterComparison {
    EQUALS, NOT_EQUALS, GREATER, GREATER_OR_EQUAL, LESS, LESS_OR_EQUAL;

    fun apply(current: Int, target: Int): Boolean = when (this) {
        EQUALS -> current == target
        NOT_EQUALS -> current != target
        GREATER -> current > target
        GREATER_OR_EQUAL -> current >= target
        LESS -> current < target
        LESS_OR_EQUAL -> current <= target
    }

    fun symbol(): String = when (this) {
        EQUALS -> "="
        NOT_EQUALS -> "≠"
        GREATER -> ">"
        GREATER_OR_EQUAL -> "≥"
        LESS -> "<"
        LESS_OR_EQUAL -> "≤"
    }
}

/** فاز ۱۵ — فقط برای actionType == ANDROID_INTENT: نوع اکشنی که intentTarget قراره باشه. */
enum class IntentActionKind {
    OPEN_URL,       // intentTarget = یک URL کامل (مثل https://example.com)
    LAUNCH_APP,     // intentTarget = نام پکیجِ اپِ مقصد (مثل com.android.settings)
    SEND_BROADCAST  // intentTarget = رشته‌ی اکشنِ broadcast (مثل com.example.MY_ACTION)
}

data class ClickRule(
    val id: String,
    val matchType: MatchType,
    // برای TEXT/VIEW_ID: مقدار متن یا شناسه.
    // برای COORDINATE / PIXEL_COLOR: "x,y".
    // برای IMAGE: مسیر فایل تصویر الگوی اصلی (اگه imageTemplatePaths خالی
    // باشه، همین یکی به‌تنهایی چک می‌شه؛ برای سازگاری با قوانین فاز ۳-۸).
    // برای OCR_TEXT: متنی که باید داخل نتیجه‌ی OCR جستجو بشه (بدون حساسیت
    // به بزرگ/کوچک بودن حروف، فقط contains — نه match دقیق).
    val matchValue: String,
    val actionType: ActionType = ActionType.CLICK,
    val label: String = matchValue, // برای نمایش خوانا در لیست قوانین
    var enabled: Boolean = true,
    // فقط برای IMAGE: حداقل میزان تطابق (۰ تا ۱) که matchTemplate باید بده
    // تا "دیده‌شدن" الگو قبول بشه. پیش‌فرض ۰.۸۵ — نه خیلی سخت‌گیر، نه خیلی شل.
    // برای همه‌ی الگوهای imageTemplatePaths هم همین یک threshold استفاده می‌شه.
    val threshold: Double = 0.85,
    // فقط برای actionType == SWIPE: نقطه‌ی مقصد به‌صورت "x,y". اگه null/نامعتبر
    // باشه، اجرای قانون در attemptClickRule سکوت می‌کنه (لاگ می‌شه، کلیک نمی‌خوره).
    val swipeEndValue: String? = null,
    val swipeDurationMs: Long = 300L,

    // ==================== فاز ۹ ====================

    // چند الگوی تصویری با اولویت: به ترتیب همین لیست چک می‌شن (ایندکس ۰ =
    // بالاترین اولویت) و اولین موردی که با threshold تطابق پیدا کنه انتخاب
    // می‌شه. اگه خالی باشه (قوانین قدیمی‌تر)، فقط matchValue چک می‌شه —
    // یعنی این فیلد کاملاً اختیاریه و رفتار قبلی رو خراب نمی‌کنه.
    val imageTemplatePaths: List<String> = emptyList(),

    // فقط برای PIXEL_COLOR: رنگ هدف («#RRGGBB») و حداکثر اختلافِ مجاز
    // (۰ تا ۲۵۵ در هر کانال رنگی) تا به‌خاطر فشرده‌سازی/نویز جزئیِ صفحه،
    // یک تفاوت رنگیِ خیلی کوچیک باعث "دیده نشدن" نشه.
    val targetColorHex: String = "#FFFFFF",
    val colorTolerance: Int = 12,

    // فقط برای OCR_TEXT: ناحیه‌ای از صفحه که باید OCR روش انجام بشه، به شکل
    // "left,top,right,bottom". اگه null باشه، کل فریم صفحه OCR می‌شه (کندتره
    // و احتمال تشخیص اشتباه بیشتره — محدود کردن ناحیه، مثلاً فقط جعبه‌ی
    // امتیاز بالای صفحه، هم سریع‌تره هم دقیق‌تر).
    val ocrRegion: String? = null,
    // فقط برای OCR_TEXT: نقطه‌ی کلیک وقتی متن پیدا شد، به شکل "x,y". اگه
    // null باشه، مرکز ocrRegion (یا مرکز کل صفحه اگه ناحیه‌ای تعیین نشده)
    // کلیک می‌خوره.
    val ocrClickPoint: String? = null,
    // فاز ۹ (بهینه‌سازی): true یعنی قبل از OCR، ناحیه خاکستری+کنتراست‌دارتر
    // و در صورت کوچیک بودن بزرگ‌نمایی می‌شه — مخصوصاً برای متنِ محیط‌های
    // Unity/Cocos (فونت‌های TextMeshPro با outline/سایه، یا فونت‌های ریز)
    // که کنتراست ساده‌ای با پس‌زمینه ندارن. برای متنِ اپ‌های عادی هم معمولاً
    // ضرری نداره، برای همین true پیش‌فرضه؛ اگه یک مورد خاص با پیش‌پردازش
    // بدتر شد (نادر ولی ممکنه)، از تنظیمات پیشرفته می‌شه خاموشش کرد.
    val ocrPreprocess: Boolean = true,

    // تاخیر تصادفی *اضافه* بعد از این قانون (روی فاصله‌ی عادی حلقه سوار
    // می‌شه)، برای طبیعی‌تر شدن الگوی کلیک — یک عدد تصادفی یکنواخت بین این
    // دو مقدار (میلی‌ثانیه) انتخاب می‌شه. اگه هر دو صفر باشن (پیش‌فرض)،
    // هیچ تاخیر اضافه‌ای اعمال نمی‌شه (رفتار قبلی حفظ می‌شه).
    val postClickDelayMinMs: Long = 0L,
    val postClickDelayMaxMs: Long = 0L,

    // «منتظر بمون تا المان/الگو ناپدید بشه»: بعد از کلیک، همین قانون به‌جای
    // ادامه‌ی فوری حلقه، هر waitForDisappearPollMs یک‌بار دوباره چک می‌کنه که
    // آیا هدف هنوز روی صفحه هست؛ به‌محض ناپدید شدن (یا رسیدن به سقف
    // waitForDisappearTimeoutMs) اجازه می‌ده حلقه ادامه بده. تا وقتی این
    // انتظار در جریانه، همین قانون در دورهای بعدیِ حلقه رد می‌شه (دوباره
    // کلیک نمی‌خوره) تا کلیک تکراری روی چیزی که هنوز داره محو می‌شه رخ نده.
    val waitForDisappearAfterClick: Boolean = false,
    val waitForDisappearTimeoutMs: Long = 5000L,
    val waitForDisappearPollMs: Long = 250L,

    // زنجیره‌ی شرطی («اگه X دیدی → Y بکن، وگرنه → Z بکن»): id یک قانونِ
    // دیگرِ همین سناریو. اگه شرطِ *این* قانون (matchType/matchValue) برقرار
    // نبود، به‌جای رد شدن ساده، بلافاصله همون دور، قانونِ elseRuleId اجرا
    // می‌شه (خودش هم می‌تونه شرط داشته باشه، یا COORDINATE باشه که همیشه
    // «برقراره»). عمداً فقط یک سطح branching پشتیبانی می‌شه (elseRuleId
    // زنجیره‌ی خودش رو دنبال می‌کنه، ولی سقفِ عمقِ زنجیره ۸ هاپه — همون‌طور که
    // در attemptClickRule توضیح داده شده). اگه دو قانون به هم اشاره کنن
    // (حلقه)، در همون دور تشخیص داده و متوقف می‌شه؛ کلیک واقعی هم نمی‌خوره).
    val elseRuleId: String? = null,

    // ==================== فاز ۱۵: شمارنده + اکشن اندروید ====================

    // شرطِ شمارنده (اختیاری، روی *هر* نوع قانونی قابل‌اعمال — فرقی نداره
    // actionType چیه): اگه counterConditionName پر باشه، حتی وقتی
    // matchType/matchValue عادی «دیده شد» تشخیص بده، قانون فقط در صورتی
    // واقعاً «برقرار» حساب می‌شه که مقدارِ فعلیِ همون شمارنده (در
    // CounterStore) با counterConditionComparison نسبت به
    // counterConditionValue درست دربیاد. یعنی AND منطقی بینِ تشخیصِ عادی و
    // شرطِ شمارنده — نه جایگزینِ اون. مثال: «این دکمه رو کلیک کن، ولی فقط
    // اگه شمارنده‌ی retry کمتر از ۵ باشه».
    val counterConditionName: String? = null,
    val counterConditionComparison: CounterComparison = CounterComparison.GREATER_OR_EQUAL,
    val counterConditionValue: Int = 0,

    // فقط برای actionType == INCREMENT_COUNTER / DECREMENT_COUNTER /
    // RESET_COUNTER: نامِ شمارنده‌ای که این اکشن دستکاریش می‌کنه، و مقدارِ
    // تغییر (برای RESET نادیده گرفته می‌شه، همیشه صفر می‌کنه).
    val counterActionName: String? = null,
    val counterActionDelta: Int = 1,

    // فقط برای actionType == ANDROID_INTENT.
    val intentActionKind: IntentActionKind = IntentActionKind.OPEN_URL,
    val intentTarget: String? = null
) {
    fun describe(): String {
        val typeFa = when (matchType) {
            MatchType.TEXT -> "متن"
            MatchType.VIEW_ID -> "شناسه"
            MatchType.COORDINATE -> "مختصات"
            MatchType.IMAGE -> if (imageTemplatePaths.size > 1) "تصویر (${imageTemplatePaths.size} الگو)" else "تصویر (OpenCV)"
            MatchType.PIXEL_COLOR -> "رنگ پیکسل"
            MatchType.OCR_TEXT -> "OCR متن"
        }
        val actionSuffix = when (actionType) {
            ActionType.CLICK -> ""
            ActionType.LONG_PRESS -> " (لانگ‌پرس)"
            ActionType.SWIPE -> " (سوایپ تا ${swipeEndValue ?: "؟"})"
            ActionType.INCREMENT_COUNTER -> " (🔢${counterActionName ?: "؟"} += $counterActionDelta)"
            ActionType.DECREMENT_COUNTER -> " (🔢${counterActionName ?: "؟"} -= $counterActionDelta)"
            ActionType.RESET_COUNTER -> " (🔢${counterActionName ?: "؟"} = 0)"
            ActionType.ANDROID_INTENT -> " (📱${intentActionKind}: ${intentTarget ?: "؟"})"
        }
        val waitSuffix = if (waitForDisappearAfterClick) " ⏳تا‌ناپدیدی" else ""
        val delaySuffix = if (postClickDelayMaxMs > 0) " 🎲${postClickDelayMinMs}-${postClickDelayMaxMs}ms" else ""
        val elseSuffix = if (elseRuleId != null) " ⤷وگرنه" else ""
        val ocrRawSuffix = if (matchType == MatchType.OCR_TEXT && !ocrPreprocess) " (خام، بدون پیش‌پردازش)" else ""
        val counterCondSuffix = if (counterConditionName != null) {
            " ⚖️${counterConditionName}${counterConditionComparison.symbol()}${counterConditionValue}"
        } else ""
        return "$typeFa: $label$actionSuffix$waitSuffix$delaySuffix$elseSuffix$ocrRawSuffix$counterCondSuffix"
    }
}

/**
 * فاز ۴ — یک سناریو یعنی یک مجموعه‌ی مستقل از قوانین («اگه X رو دیدی، Y کار
 * رو بکن») که با هم یک اسم و یک فاصله‌ی زمانی حلقه دارن. کاربر می‌تونه چند
 * سناریوی جدا بسازه (مثلاً یکی برای یک بازی، یکی برای یک اپ دیگه) و هر بار
 * فقط یکی از اون‌ها رو "فعال" کنه؛ فقط قوانین سناریوی فعال توسط OverlayService
 * اجرا می‌شن.
 */
data class Scenario(
    val id: String,
    var name: String,
    val rules: MutableList<ClickRule> = mutableListOf(),
    var loopIntervalMs: Long = 1500L
)
