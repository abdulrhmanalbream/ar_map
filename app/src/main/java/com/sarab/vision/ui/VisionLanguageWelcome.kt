package com.sarab.vision.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

internal val visionLanguages = listOf("ar" to "العربية", "en" to "English", "ur" to "اردو", "id" to "Indonesia", "tr" to "Türkçe")

internal class VisionLanguagePreference(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("smart_mutawwif_language", Context.MODE_PRIVATE)
    val confirmed get() = prefs.getBoolean("confirmed", false)
    val language get() = prefs.getString("language", "ar").orEmpty().takeIf { value -> visionLanguages.any { it.first == value } } ?: "ar"
    fun confirm(language: String) {
        require(visionLanguages.any { it.first == language })
        prefs.edit().putString("language", language).putBoolean("confirmed", true).apply()
    }
}

@Composable
internal fun VisionLanguageWelcome(initialLanguage: String, onConfirm: (String) -> Unit) {
    var selected by remember { mutableStateOf(initialLanguage) }
    val copy = VisionCopy(selected)
    HedayahTheme {
        CompositionLocalProvider(LocalLayoutDirection provides if (selected in setOf("ar", "ur")) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Surface(Modifier.fillMaxSize().testTag("vision-language-welcome"), color = Hedayah.Ivory) {
                Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HedayahMasthead(compact = true)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(copy.chooseLanguage, style = MaterialTheme.typography.headlineSmall)
                        Text(copy.languageIntro, style = MaterialTheme.typography.bodyMedium, color = Hedayah.Muted)
                        visionLanguages.forEach { (code, label) ->
                            Surface(color = if (selected == code) Hedayah.Soft else Hedayah.Paper,
                                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()
                                    .selectable(selected == code, role = Role.RadioButton, onClick = { selected = code })
                                    .testTag("vision-language-$code")) {
                                Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected == code, onClick = null)
                                    Spacer(Modifier.width(12.dp))
                                    Text(label, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                    Button(onClick = { onConfirm(selected) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .testTag("vision-language-confirm")) { Text(copy.continueLabel) }
                }
            }
        }
    }
}

@Composable
internal fun VisionCameraAccess(copy: VisionCopy, blocked: Boolean, busy: Boolean, error: String?,
    onCamera: () -> Unit, onImage: () -> Unit) {
    HedayahTheme {
        Surface(Modifier.fillMaxSize(), color = Hedayah.Ivory) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HedayahMasthead(compact = true)
                Text(copy.cameraPermissionTitle, style = MaterialTheme.typography.headlineSmall)
                Text(copy.cameraPermissionNote, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onCamera, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (blocked) copy.openAppSettings else copy.allowCamera)
                }
                OutlinedButton(onClick = onImage, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .testTag("vision-choose-image")) { Text(if (busy) copy.imageLoading else copy.chooseImage) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
