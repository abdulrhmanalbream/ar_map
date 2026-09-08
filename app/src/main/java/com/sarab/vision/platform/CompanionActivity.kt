package com.sarab.vision.platform

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sarab.vision.ArNavActivity
import com.sarab.vision.CampusApp
import com.sarab.vision.core.distanceMeters
import com.sarab.vision.wear.PhoneWearBridge
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private data class ChatLine(val role: String, val text: String)
private val languages = listOf("ar" to "العربية", "en" to "English", "ur" to "اردو", "id" to "Indonesia", "tr" to "Türkçe")

class CompanionActivity : ComponentActivity() {
    private lateinit var store: PlatformStore
    private lateinit var client: PlatformClient
    private var speech: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var input by mutableStateOf("")
    private var notice by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var listening by mutableStateOf(false)
    private var speakingEnabled by mutableStateOf(true)
    private var proposedId by mutableStateOf<String?>(null)
    private var localReply by mutableStateOf(false)
    private var page by mutableStateOf(0)
    private val history = mutableStateListOf<ChatLine>()
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else notice = "الميكروفون غير متاح؛ يمكنك الكتابة للمساعد"
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) { store.sharing(true); startSync() }
        else notice = "مشاركة الموقع تحتاج إذن الموقع؛ تنبيهات المجموعة تبقى متاحة"
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) notice = "أذونات الإشعارات مغلقة؛ تابع التنبيهات داخل التطبيق والساعة"
        startSync()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PlatformStore.get(this)
        client = PlatformClient(store)
        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF006B5D), onPrimary = Color.White,
                background = Color(0xFFF5F4EF), surface = Color(0xFFFFFEF9), onSurface = Color(0xFF17312D))) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Screen() }
            }
        }
    }
    @Composable private fun Screen() {
        val state by store.state.collectAsState()
        val watch by PhoneWearBridge.get(this).state.collectAsState()
        val scroll = rememberScrollState()
        LaunchedEffect(state.enrolled) { if (!state.enrolled) { history.clear(); proposedId = null; localReply = false } }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { finish() }) { Text("العودة للكاميرا") }
                    Text("المطوف الذكي", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 2, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = page == 0, onClick = { page = 0 }, label = { Text("المساعد") })
                    FilterChip(selected = page == 1, onClick = { page = 1 }, label = { Text("مجموعتي والساعة") })
                }
                Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                    if (!state.enrolled) Enrollment()
                    else if (page == 0) {
                        Text("معك في الطريق", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("تحدث بلغتك، واختر وجهتك من الأماكن المحفوظة. سأعرض الوجهة قبل بدء الملاحة.")
                        LanguageChoice(state.language)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("قراءة الرد بصوت")
                            Switch(checked = speakingEnabled, onCheckedChange = { speakingEnabled = it; if (!it) tts?.stop() })
                        }
                        if (history.isEmpty()) {
                            Text("جرّب: وين أقرب وجهة؟ أو اكتب اسم المكان اللي تبيه.", modifier = Modifier.padding(vertical = 24.dp))
                        }
                        history.forEach { line ->
                            Surface(color = if (line.role == "user") Color(0xFFE1ECE6) else Color(0xFFF0F0E9), shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(if (line.role == "user") "أنت" else "المطوف الذكي", style = MaterialTheme.typography.labelMedium)
                                    Text(line.text, fontSize = 18.sp)
                                }
                            }
                        }
                        if (localReply) Text("الرد الحالي من المساعد المحلي المحدود؛ الذكاء الاصطناعي السحابي غير متاح الآن.", style = MaterialTheme.typography.bodySmall)
                        val target = CampusApp.state(this@CompanionActivity).landmarks.firstOrNull { it.id == proposedId }
                        if (target != null) {
                            Button(onClick = {
                                CampusApp.state(this@CompanionActivity).selectTarget(target)
                                store.destinationName = target.name
                                startActivity(Intent(this@CompanionActivity, ArNavActivity::class.java).putExtra(ArNavActivity.EXTRA_TARGET_ID, target.id)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                                finish()
                            }, modifier = Modifier.fillMaxWidth()) { Text("ابدأ الملاحة إلى ${target.name}") }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        Text(state.groupName, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(state.message)
                        state.deliveryIssue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (state.lastSync > 0) Text("آخر اتصال: ${java.text.DateFormat.getTimeInstance().format(java.util.Date(state.lastSync))}")
                        Button(onClick = {
                            if (state.running) startService(Intent(this@CompanionActivity, CompanionSyncService::class.java).setAction(CompanionSyncService.STOP))
                            else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@CompanionActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else startSync()
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (state.running) "إنهاء جلسة المجموعة" else "تشغيل تنبيهات المجموعة") }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("مشاركة موقعي مع المشرف", fontWeight = FontWeight.SemiBold)
                                Text("آخر موقع فقط، دون سجل تحركات.", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = state.sharing, onCheckedChange = { enabled ->
                                if (enabled) {
                                    if (ContextCompat.checkSelfPermission(this@CompanionActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                        ContextCompat.checkSelfPermission(this@CompanionActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                        store.sharing(true); startSync()
                                    } else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                } else {
                                    store.sharing(false)
                                    lifecycleScope.launch {
                                        runCatching { client.request("/device/privacy", JSONObject().put("sharingEnabled", false)); store.privacySynced() }
                                            .onFailure { notice = "الموقع متوقف على الجوال؛ حذفه من اللوحة بانتظار الاتصال" }
                                    }
                                    if (state.running) startSync()
                                }
                            })
                        }
                        HorizontalDivider()
                        Text("Galaxy Watch", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(if (watch.connected) "الساعة متصلة${watch.batteryPercent?.let { " · البطارية $it٪" }.orEmpty()}" else "افتح المطوف الذكي على الساعة وتأكد من اقترانها بالجوال")
                        watch.lap?.let { lap ->
                            Text("${if (lap.optString("mode") == "sai") "السعي" else "الطواف"}: ${lap.optInt("count")} / 7", fontSize = 24.sp)
                            Text("العدد مؤكد يدوياً من الساعة، ويمكن التراجع عن آخر شوط.", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { queueHelp() }, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C3D2C))) {
                            Text("أحتاج مساعدة · تنبيه مجموعتي")
                        }
                        if (state.helpPending) TextButton(onClick = { store.clearHelp() }) { Text("أنا بخير الآن · إلغاء حالة طلب المساعدة") }
                        Text("التنبيهات", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        if (state.alerts.isEmpty()) Text("لا توجد تنبيهات مستلمة بعد.")
                        state.alerts.asReversed().forEach { raw ->
                            val alert = JSONObject(raw)
                            Surface(color = Color(0xFFF0F0E9), shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text(alert.optString("sourceName", "المجموعة"), fontWeight = FontWeight.Bold)
                                    Text(alert.optString("message"))
                                    TextButton(onClick = { store.acknowledge(alert.getString("id")); startSync() }, enabled = !alert.optBoolean("ackPending") && !alert.optBoolean("ackSynced")) {
                                        Text(if (alert.optBoolean("ackSynced")) "وصل تأكيدك للمجموعة" else if (alert.optBoolean("ackPending")) "تم التأكيد · بانتظار المزامنة" else "تم الاستلام")
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (state.enrolled && page == 0) {
                    OutlinedTextField(value = input, onValueChange = { input = it.take(2000) }, label = { Text("اكتب للمساعد") },
                        enabled = !busy, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { ask() }, enabled = !busy && input.isNotBlank(), modifier = Modifier.weight(1f)) { Text("إرسال") }
                        OutlinedButton(onClick = {
                            if (listening) { speech?.stopListening(); listening = false }
                            else if (ContextCompat.checkSelfPermission(this@CompanionActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
                            else microphone.launch(Manifest.permission.RECORD_AUDIO)
                        }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (listening) "إنهاء الاستماع" else "تحدث") }
                    }
                }
            }
        }
    }

    @Composable private fun Enrollment() {
        var url by remember { mutableStateOf(store.server) }
        var code by remember { mutableStateOf("") }
        var name by remember { mutableStateOf(store.snapshot.name) }
        Text("اربط مجموعتك", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("خذ رمز الربط من المشرف في لوحة المطوف الذكي. مشاركة موقعك تحتاج تفعيلك بعد الربط.")
        OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("اسمك لدى المجموعة") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = code, onValueChange = { code = it.trim().take(64) }, label = { Text("رمز المجموعة") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        LanguageChoice(store.snapshot.language)
        OutlinedTextField(value = url, onValueChange = { url = it.take(240) }, label = { Text("عنوان خادم المطوف الذكي") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = {
            busy = true; notice = ""
            lifecycleScope.launch {
                try {
                    val normalized = PlatformStore.validatedServer(url)
                    val result = client.request("/devices/enroll", JSONObject().put("code", code).put("name", name.trim()).put("language", store.snapshot.language), normalized, false)
                    store.enroll(normalized, name.trim(), store.snapshot.language, result)
                    page = 1
                } catch (e: Exception) { notice = e.message ?: "تعذر الربط؛ راجع الرمز والاتصال" }
                finally { busy = false }
            }
        }, enabled = !busy && name.isNotBlank() && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "جارٍ الربط…" else "ربط المجموعة") }
    }
    @Composable private fun LanguageChoice(language: String) {
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }) { Text("لغة الحديث: ${languages.firstOrNull { it.first == language }?.second ?: language}") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                languages.forEach { (code, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { store.language(code); open = false }) }
            }
        }
    }
    private fun startSync() {
        runCatching { CompanionSyncService.start(this) }.onFailure { notice = "تعذر بدء المزامنة؛ افتح أذونات التطبيق وحاول مجدداً" }
    }
    private fun queueHelp() {
        runCatching { store.queue("help", JSONObject().put("message", "أحتاج مساعدة من المجموعة")); startSync(); notice = "طلب المساعدة محفوظ ويُرسل عند الاتصال" }
            .onFailure { notice = it.message.orEmpty() }
    }
    private fun ask() {
        val message = input.trim()
        if (message.isEmpty() || busy) return
        val campus = CampusApp.state(this@CompanionActivity)
        val destinations = JSONArray(campus.landmarks.take(150).map { target ->
            JSONObject().put("id", target.id.take(120)).put("name", target.name.take(160))
                .put("aliases", JSONArray(target.signTexts.take(10).map { it.take(120) })).apply {
                if (!campus.demoActive) campus.fix?.let { put("distanceMeters", distanceMeters(it.position, target.approachPoint(it.position))) }
            }
        })
        val previous = JSONArray(history.takeLast(8).map { JSONObject().put("role", it.role).put("content", it.text.take(1500)) })
        history.add(ChatLine("user", message)); input = ""; busy = true; notice = ""; proposedId = null
        lifecycleScope.launch {
            try {
                val request = JSONObject().put("message", message).put("language", store.snapshot.language)
                    .put("context", JSONObject().put("destinationId", campus.target?.id ?: JSONObject.NULL)
                        .put("destinationName", campus.target?.name ?: JSONObject.NULL).put("lap", platformLap(PhoneWearBridge.get(this@CompanionActivity).snapshot.value.lap) ?: JSONObject.NULL))
                    .put("destinations", destinations).put("history", previous)
                while (request.toString().toByteArray(Charsets.UTF_8).size > 60_000 && destinations.length() > 0) destinations.remove(destinations.length() - 1)
                val result = client.request("/assistant", request)
                val reply = result.getString("reply").take(5000)
                history.add(ChatLine("assistant", reply))
                while (history.size > 24) history.removeAt(0)
                localReply = result.optString("provider") == "local"
                val action = result.optJSONObject("action")
                proposedId = action?.optString("destinationId")?.takeIf { action.optString("type") == "navigate" && campus.landmarks.any { target -> target.id == it } }
                if (speakingEnabled && ttsReady) {
                    val supported = tts?.setLanguage(Locale.forLanguageTag(result.optString("language", store.snapshot.language))) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    if (supported >= TextToSpeech.LANG_AVAILABLE) tts?.speak(reply.take(3900), TextToSpeech.QUEUE_FLUSH, null, "sarab_reply")
                    else notice = "حزمة النطق لهذه اللغة غير مثبتة؛ الرد متاح كتابةً"
                }
            } catch (e: Exception) { notice = e.message ?: "تعذر الاتصال بالمساعد"; input = message }
            finally { busy = false }
        }
    }
    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { notice = "التعرف على الصوت غير مثبت على الجوال؛ استخدم الكتابة"; return }
        tts?.stop()
        speech?.destroy()
        speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listening = true; notice = "أسمعك…" }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { listening = false }
                override fun onError(error: Int) { listening = false; notice = "لم ألتقط الكلام؛ أعد المحاولة أو اكتب الرسالة" }
                override fun onResults(results: Bundle?) {
                    listening = false; notice = ""
                    input = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().take(2000)
                    if (input.isNotBlank()) ask()
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, store.snapshot.language).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
        }
    }
    override fun onStop() { speech?.cancel(); listening = false; tts?.stop(); super.onStop() }
    override fun onDestroy() { speech?.destroy(); tts?.shutdown(); super.onDestroy() }
}
