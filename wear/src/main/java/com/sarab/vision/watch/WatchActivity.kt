package com.sarab.vision.watch

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sarab.vision.R
import com.sarab.vision.wear.shared.WatchAlert

/**
 * Hedayah watch: a quiet forest canvas, the original mark, ivory Arabic type and one gold action.
 * The ritual and recorded count lead; group details follow below the thumb-sized controls.
 * Native scrolling and rotary input keep the centre chord of a round Watch 8 Classic usable.
 */
class WatchActivity : Activity() {
    private lateinit var repository: WatchRepository
    private lateinit var scroll: ScrollView
    private lateinit var column: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var lastIncrementAt = 0L
    private var receiverRegistered = false
    private var screenKey = ""
    private val deferredAlerts = mutableSetOf<String>()
    private val accent = Color.rgb(180, 154, 91)
    private val ink = Color.rgb(8, 27, 22)
    private val ivory = Color.rgb(245, 243, 235)
    private val surface = Color.rgb(19, 63, 53)
    private val outline = Color.rgb(65, 91, 75)
    private val muted = Color.rgb(193, 204, 193)
    private val regular by lazy { resources.getFont(R.font.hedaya_regular) }
    private val semibold by lazy { resources.getFont(R.font.hedaya_semibold) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { render() }
    }
    private val refresh = object : Runnable {
        override fun run() {
            repository.refreshConnection()
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = WatchRepository.get(this)
        window.statusBarColor = ink
        window.navigationBarColor = ink
        scroll = ScrollView(this).apply {
            setBackgroundColor(ink)
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            clipToPadding = true
            isFocusableInTouchMode = true
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            // Round corners are unavailable touch area; the content remains inside the centre chord.
            setPadding(dp(24), dp(18), dp(24), dp(38))
        }
        scroll.addView(column, FrameLayout.LayoutParams(-1, -2))
        setContentView(scroll)
        scroll.requestApplyInsets()
        scroll.setOnGenericMotionListener { _, event ->
            if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                val pixels = -event.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(this).scaledVerticalScrollFactor
                scroll.scrollBy(0, pixels.toInt())
                true
            } else false
        }
        render(force = true)
        scroll.requestFocus()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(WatchRepository.ACTION_CHANGED)
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        handler.post(refresh)
        repository.sendStatus()
        render(force = true)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        if (receiverRegistered) { unregisterReceiver(receiver); receiverRegistered = false }
        setTurnScreenOn(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deferredAlerts.clear()
        render(force = true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render(force = true)
    }

    private fun render(force: Boolean = false) {
        val lap = repository.lap()
        val alerts = repository.alerts()
        val selected = alerts.firstOrNull { it.id == intent?.getStringExtra("alertId") && it.id !in deferredAlerts }
            ?: alerts.firstOrNull { it.id !in deferredAlerts }
        val notificationsAllowed = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val key = "${lap.mode}:${lap.count}:${repository.connected}:${repository.cloudConnected}:${repository.groupName}:${repository.pendingCount}:${repository.helpStatus}:${alerts.map { it.id }}:$notificationsAllowed"
        if (!force && key == screenKey) return
        screenKey = key
        val oldScroll = scroll.scrollY
        column.removeAllViews()
        if (selected != null) {
            renderAlert(selected, alerts.size)
            setTurnScreenOn(true)
            scroll.post { scroll.scrollTo(0, 0) }
            return
        }
        setTurnScreenOn(false)
        brandHeader()
        val modes = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        modes.addView(modeButton("الطواف", "tawaf", lap.mode == "tawaf"), LinearLayout.LayoutParams(0, dp(48), 1f))
        modes.addView(modeButton("السعي", "sai", lap.mode == "sai"), LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        column.addView(modes, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        label("${arabic(lap.count)} من ٧", 38f, ivory, bold = true).apply {
            contentDescription = "${if (lap.mode == "tawaf") "الطواف" else "السعي"}: ${lap.count} من سبعة، عدّ يدوي"
            setPadding(0, dp(2), 0, 0)
        }
        label(if (lap.count == 7) "٧ أشواط سجّلتها يدويًا" else "عدّ يدوي · بعد إتمام الشوط", 11f, muted)
        button(if (lap.count == 7) "العدّ مكتمل: ٧" else "+ سجّل شوطًا", primary = true, enabled = lap.count < 7) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastIncrementAt >= 500) {
                lastIncrementAt = now
                repository.increment()
                scroll.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                render(force = true)
            }
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(makeButton("تراجع", false, lap.count > 0) { repository.undo(); render(force = true) },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(makeButton("مساعدة", false, true) { confirmHelp() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        column.addView(actions, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        if (alerts.isNotEmpty()) button("التنبيهات غير المؤكدة (${arabic(alerts.size)})") {
            deferredAlerts.clear()
            render(force = true)
        }
        if (repository.helpStatus.isNotEmpty()) label(repository.helpStatus, 12f, accent, top = 10)
        divider()
        label(if (repository.connected) "الجوال متصل" else "العدّ يعمل دون اتصال", 12f, ivory, bold = true)
        if (repository.groupName.isNotEmpty()) label(repository.groupName, 12f, accent, top = 6)
        label(when {
            !repository.connected -> "افتح المطوف الذكي على الجوال المتصل؛ تُرسل الطلبات المحفوظة عند عودة الاتصال."
            !repository.cloudConnected -> "الجوال متصل؛ اتصال المجموعة غير متاح الآن."
            else -> "المجموعة متصلة عبر الجوال"
        }, 11f, muted, top = 6)
        if (!notificationsAllowed) button("تفعيل تنبيهات المجموعة") {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        button("بدء عدّ جديد", quiet = true) {
            AlertDialog.Builder(this).setTitle("تصفير ${if (lap.mode == "tawaf") "الطواف" else "السعي"}؟")
                .setMessage("المسجّل الآن ${arabic(lap.count)} من ٧. سيبدأ عدّ جديد من الصفر.")
                .setNegativeButton("إلغاء", null).setPositiveButton("ابدأ من صفر") { _, _ -> repository.reset(); render(force = true) }.show()
        }
        label("يُسجّل العداد تأكيدك اليدوي فقط. لا يقيس الأشواط تلقائيًا.", 11f, muted, top = 8)
        if (lap.mode == "sai") label("سجّل كل انتقال بين الصفا والمروة بعد إتمامه.", 11f, muted, top = 5)
        scroll.post { scroll.scrollTo(0, oldScroll) }
    }

    private fun renderAlert(alert: WatchAlert, total: Int) {
        label(when (alert.kind) { "help" -> "طلب مساعدة"; "regroup" -> "تجمّع المجموعة"; else -> "تنبيه المجموعة" }, 18f, accent, true)
        if (alert.sourceName.isNotBlank()) label(alert.sourceName, 12f, muted, top = 6)
        divider()
        label(alert.message, 17f, ivory)
        if (total > 1) label("${arabic(total)} تنبيهات بانتظار التأكيد", 11f, muted, top = 10)
        button("وصلني التنبيه", primary = true) {
            if (!repository.acknowledge(alert.id)) toast("تعذّر حفظ التأكيد. حاول بعد اتصال الجوال.")
            render(force = true)
        }
        label("يُرسل التأكيد عند ضغط الزر فقط.", 11f, muted, top = 6)
        button("العودة للعدّ") {
            deferredAlerts.addAll(repository.alerts().map { it.id })
            render(force = true)
        }
        button("أحتاج مساعدة") { confirmHelp() }
    }

    private fun confirmHelp() {
        AlertDialog.Builder(this).setTitle("إرسال طلب مساعدة؟")
            .setMessage(if (repository.connected) "يرسل الجوال الطلب إلى مجموعتك عند توفر اتصالها."
                else "الطلب سيُحفظ على الساعة حتى يرجع اتصال الجوال.")
            .setNegativeButton("إلغاء", null).setPositiveButton("أرسل الطلب") { _, _ ->
                if (repository.requestHelp()) toast("حُفظ طلب المساعدة")
                else toast("قائمة الإرسال ممتلئة. افتح المطوف الذكي على الجوال وأعد المحاولة.")
                render(force = true)
            }.show()
    }

    private fun modeButton(text: String, mode: String, selected: Boolean) = makeButton(text, false, true) {
        repository.chooseMode(mode); render(force = true)
    }.apply {
        isSelected = selected
        contentDescription = "$text، ${if (selected) "محدد" else "تغيير نوع العدّ"}"
        setTextColor(if (selected) ivory else muted)
        background = controlBackground(if (selected) surface else ink, if (selected) accent else outline)
        typeface = if (selected) semibold else regular
    }

    private fun brandHeader() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.hedayathon_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "هداية ثون"
        }, LinearLayout.LayoutParams(dp(34), dp(24)).apply { marginEnd = dp(6) })
        header.addView(TextView(this).apply {
            text = "المطوف الذكي"
            textSize = 12f
            typeface = semibold
            setTextColor(ivory)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(-2, -2))
        column.addView(header, LinearLayout.LayoutParams(-1, -2))
    }

    private fun divider() {
        column.addView(View(this).apply { setBackgroundColor(outline) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(18); bottomMargin = dp(14) })
    }

    private fun button(text: String, primary: Boolean = false, enabled: Boolean = true, quiet: Boolean = false, action: () -> Unit) {
        val control = makeButton(text, primary, enabled, action)
        if (quiet) control.background = controlBackground(ink, outline)
        column.addView(control, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
    }
    private fun makeButton(text: String, primary: Boolean, enabled: Boolean, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        typeface = semibold
        setTextColor(if (primary) ink else ivory)
        background = controlBackground(if (primary) accent else surface)
        minHeight = dp(48)
        minimumWidth = 0
        setPadding(dp(7), 0, dp(7), 0)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.55f
        setOnClickListener { action() }
    }
    private fun controlBackground(fill: Int, stroke: Int? = null): RippleDrawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(fill)
            stroke?.let { setStroke(dp(1), it) }
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(55, 245, 243, 235)), shape, null)
    }
    private fun label(text: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        textDirection = View.TEXT_DIRECTION_LOCALE
        typeface = if (bold) semibold else regular
        includeFontPadding = false
        column.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun arabic(number: Int) = number.toString().map { if (it in '0'..'9') ('٠'.code + (it - '0')).toChar() else it }.joinToString("")
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
