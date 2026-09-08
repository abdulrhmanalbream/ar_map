package com.sarab.vision.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.CampusState
import com.sarab.vision.core.GuidanceMode
import com.sarab.vision.core.distanceMeters
import com.sarab.vision.core.formatDistanceAr
import com.sarab.vision.glasses.camera.EyeCameraState
import com.sarab.vision.glasses.motion.GlassesMotionState

private val EyeSurface = Color(0xFF102028)
private val EyeAccent = Color(0xFF69E8D7)
private val EyeMuted = Color(0xFFD4E0E5)
private val EyePanel = Color(0xE6102028)

/** The image is edge-to-edge; insets and controls never change its projection rectangle. */
@Composable
fun GlassesCameraScreen(
    campus: CampusState,
    cameraState: EyeCameraState,
    frame: Bitmap?,
    motion: GlassesMotionState,
    calibrated: Boolean,
    displayConnected: Boolean,
    horizontalFov: Float,
    onFovChange: (Float) -> Unit,
    onAlign: () -> Unit,
    onRetry: () -> Unit,
    onPhoneCamera: () -> Unit,
    onPickDestination: () -> Unit,
    onExportLog: () -> Unit,
    exportingLog: Boolean,
    onOpenTools: () -> Unit,
    onOpenCompanion: () -> Unit = {},
    locationGranted: Boolean,
    onEnableLocation: () -> Unit,
) {
    var settingsOpen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    val previewLive = cameraState.streaming && frame != null
    val poseReady = campus.externalTrackingEnabled && previewLive && motion.tracking && calibrated &&
        campus.headingDegrees?.isFinite() == true && motion.pitchDegrees?.isFinite() == true &&
        motion.rollDegrees?.isFinite() == true
    val target = campus.target
    val position = campus.fix?.position
    val distance = if (target != null && position != null) distanceMeters(position, target.approachPoint(position)) else null
    val ambiguous = campus.guidance as? GuidanceMode.Ambiguous

    Box(Modifier.fillMaxSize().background(EyeSurface).clipToBounds()) {
        if (previewLive) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "بث مباشر من كاميرا النظارة",
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().clickable(
                    onClickLabel = if (controlsVisible) "إخفاء التحكم" else "إظهار التحكم",
                ) { controlsVisible = !controlsVisible },
            )
            if (poseReady && position != null && campus.guidance !is GuidanceMode.Arrived && ambiguous == null) {
                GlassesRouteOverlay(
                    userPosition = position,
                    routePoints = campus.route?.points.orEmpty(),
                    targetPosition = target?.approachPoint(position),
                    headingDegrees = campus.headingDegrees,
                    pitchDegrees = motion.pitchDegrees ?: 0.0,
                    rollDegrees = motion.rollDegrees ?: 0.0,
                    horizontalFovDegrees = horizontalFov.toDouble(),
                    imageAspectRatio = frame.width.toDouble() / frame.height,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("بانتظار صورة النظارة", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(cameraState.status, color = EyeMuted, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Text("ثبّت Eye واسمح بالوصول إلى USB عند الطلب.", color = EyeMuted, fontSize = 14.sp)
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("إعادة الاتصال", color = EyeAccent)
                }
            }
        }

        if (controlsVisible || !previewLive || ambiguous != null || campus.guidance is GuidanceMode.Arrived) {
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        Surface(color = EyePanel, shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(6.dp).background(if (previewLive) EyeAccent else EyeMuted, RoundedCornerShape(3.dp)))
                                Text(if (poseReady) "Eye · تتبّع الرأس" else if (previewLive) "Eye · مباشر" else "XREAL Eye",
                                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Surface(color = EyePanel, shape = RoundedCornerShape(12.dp)) {
                        TextButton(onClick = onExportLog, enabled = !exportingLog, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (exportingLog) "حفظ…" else "حفظ السجل", color = EyeMuted, fontSize = 13.sp)
                        }
                    }
                    Surface(color = EyePanel, shape = RoundedCornerShape(12.dp)) {
                        TextButton(onClick = { settingsOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("الإعدادات", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
                if (previewLive && !calibrated) {
                    Spacer(Modifier.height(8.dp))
                    Surface(color = EyePanel, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (motion.tracking) "عاير اتجاه الرأس لإظهار المسار" else "بانتظار حساسات النظارة",
                                color = EyeMuted, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2)
                            TextButton(onClick = { settingsOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("المعايرة", color = EyeAccent)
                            }
                        }
                    }
                }
            }

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (ambiguous != null) {
                    Surface(color = EyePanel, shape = RoundedCornerShape(12.dp)) {
                        Box(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                            AmbiguityPrompt(candidates = ambiguous.candidates, onPick = { campus.selectTarget(it) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Surface(color = EyePanel, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp)) {
                            Text(target?.name ?: "اختر وجهتك", color = Color.White, fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when {
                                campus.guidance is GuidanceMode.Arrived -> "وصلت إلى الوجهة"
                                campus.demoActive -> "موقع تجريبي · الكاميرا والحركة حقيقيتان"
                                distance != null && poseReady -> "${formatDistanceAr(distance)} · مسار تقديري"
                                !previewLive -> "الصورة من كاميرا Eye"
                                !motion.tracking -> "${motion.status}"
                                !calibrated -> "معايرة الاتجاه مطلوبة للأسهم"
                                else -> "الصورة: Eye · الحركة: النظارة"
                            }, color = EyeMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!locationGranted || !campus.isGpsEnabled()) {
                            TextButton(onClick = onEnableLocation, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("الموقع", color = EyeAccent, fontSize = 13.sp)
                            }
                        }
                        TextButton(onClick = onOpenCompanion, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("المساعد والمجموعة", color = EyeAccent)
                        }
                        TextButton(onClick = onPickDestination, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("الوجهة", color = EyeAccent, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        if (settingsOpen) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClickLabel = "إغلاق الإعدادات") { settingsOpen = false })
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp).widthIn(max = 380.dp).fillMaxHeight(),
                color = EyeSurface, shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("العرض والاتجاه", color = Color.White, fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { settingsOpen = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("إغلاق", color = EyeAccent)
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                        Text(motion.status, color = EyeAccent, fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("انظر إلى نقطة بعيدة بمستوى العين، ورأسك مستقيم دون ميل. وجّه كاميرا الجوال الخلفية في الاتجاه نفسه ثم اضغط المعايرة؛ يضبط ذلك الشمال والأفق معاً.",
                            color = EyeMuted, fontSize = 15.sp)
                        if (!campus.phoneHeadingReliable) {
                            Spacer(Modifier.height(8.dp))
                            Text("ارفع الجوال نحو الأفق وحرّكه بشكل ٨ حتى تستقر البوصلة.", color = Color(0xFFFFD180), fontSize = 13.sp)
                        }
                        if (!campus.hasNorthReference && !campus.demoActive) {
                            Text("بانتظار الموقع الحقيقي لتحديد الشمال.", color = Color(0xFFFFD180), fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { onAlign(); settingsOpen = false },
                            enabled = motion.tracking && campus.phoneHeadingReliable &&
                                (campus.demoActive || (locationGranted && campus.hasNorthReference)),
                            colors = ButtonDefaults.buttonColors(containerColor = EyeAccent, contentColor = EyeSurface),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(if (calibrated) "إعادة ضبط الاتجاه والأفق" else "معايرة الاتجاه والأفق") }
                        Spacer(Modifier.height(20.dp))
                        Text("زاوية الكاميرا: ${horizontalFov.toInt()}°", color = Color.White, fontSize = 15.sp)
                        Slider(value = horizontalFov, onValueChange = onFovChange, valueRange = 40f..110f)
                        Text("عرض بملء الشاشة مع قصّ الأطراف. اضبط الزاوية إذا انزاحت الأسهم عند الحواف؛ موضع الأرض تقديري.",
                            color = EyeMuted, fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))
                        Text(if (displayConnected) "شاشة النظارة متصلة. اختر عكس الشاشة في Samsung بدلاً من DeX."
                            else "وصّل النظارة واختر عكس الشاشة في الجوال.", color = EyeMuted, fontSize = 13.sp)
                        TextButton(onClick = onOpenTools, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("الخريطة والأدوات", color = EyeAccent)
                        }
                        if (!locationGranted || !campus.isGpsEnabled()) {
                            TextButton(onClick = onEnableLocation, modifier = Modifier.heightIn(min = 48.dp)) { Text("تفعيل الموقع", color = EyeAccent) }
                        }
                        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("إعادة اتصال الكاميرا والحساسات", color = EyeAccent) }
                        TextButton(onClick = onPhoneCamera, modifier = Modifier.heightIn(min = 48.dp)) { Text("استخدام كاميرا الجوال", color = EyeAccent) }
                    }
                }
            }
        }
    }
}
