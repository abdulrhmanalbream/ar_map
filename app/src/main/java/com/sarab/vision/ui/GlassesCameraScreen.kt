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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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

private val EyeSurface = Hedayah.Hud
private val EyeAccent = Hedayah.HudGold
private val EyeMuted = Hedayah.HudMuted
private val EyePanel = Hedayah.Hud.copy(alpha = 0xE6 / 255f)

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
    onReligiousHome: () -> Unit = {},
    religiousHud: (@Composable BoxScope.(openCameraSettings: () -> Unit) -> Unit)? = null,
    phoneCamera: Boolean = false,
    onEyeCamera: () -> Unit = {},
    cameraPreview: (@Composable BoxScope.() -> Unit)? = null,
    onChooseImage: (() -> Unit)? = null,
    imageActionLabel: String = "اختيار صورة",
    imageBusy: Boolean = false,
    imageError: String? = null,
) {
    var settingsOpen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    val density = LocalDensity.current
    val compactHeight = LocalConfiguration.current.screenHeightDp < 480
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var footerHeightPx by remember { mutableIntStateOf(0) }
    val previewLive = cameraState.streaming && frame != null
    val poseReady = !phoneCamera && campus.externalTrackingEnabled && previewLive && motion.tracking && calibrated &&
        campus.headingDegrees?.isFinite() == true && motion.pitchDegrees?.isFinite() == true &&
        motion.rollDegrees?.isFinite() == true
    val target = campus.target
    val position = campus.fix?.position
    val distance = if (target != null && position != null) distanceMeters(position, target.approachPoint(position)) else null
    val ambiguous = campus.guidance as? GuidanceMode.Ambiguous

    Box(Modifier.fillMaxSize().background(EyeSurface).clipToBounds()) {
        if (phoneCamera && cameraPreview != null) {
            // Camera2 needs its preview surface before it can publish the first frame.
            Box(Modifier.fillMaxSize()) { cameraPreview.invoke(this) }
            Box(Modifier.fillMaxSize().clickable(
                onClickLabel = if (controlsVisible) "إخفاء التحكم" else "إظهار التحكم",
            ) { controlsVisible = !controlsVisible })
        }
        if (previewLive) {
            if (!phoneCamera || cameraPreview == null) {
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = if (phoneCamera) "بث مباشر من كاميرا الجوال" else "بث مباشر من كاميرا النظارة",
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().clickable(
                        onClickLabel = if (controlsVisible) "إخفاء التحكم" else "إظهار التحكم",
                    ) { controlsVisible = !controlsVisible },
                )
            }
            if (religiousHud == null && poseReady && position != null && campus.guidance !is GuidanceMode.Arrived && ambiguous == null) {
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
            // Reserve the measured controls before creating the scroll viewport.
            // The live image above deliberately keeps the full projection rectangle.
            val topSpace = if (religiousHud != null) 76.dp else with(density) { headerHeightPx.toDp() }
            val bottomSpace = if (religiousHud != null) 116.dp else with(density) { footerHeightPx.toDp() }
            Column(
                Modifier.fillMaxSize()
                    .padding(start = 24.dp, end = 24.dp, top = topSpace + 8.dp, bottom = bottomSpace + 8.dp)
                    .clipToBounds().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (!compactHeight) {
                    HedayahLogo(Modifier.width(112.dp).height(70.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("المطوف الذكي", color = Hedayah.HudGold, fontSize = 16.sp)
                    Spacer(Modifier.height(16.dp))
                }
                Text(if (phoneCamera) "بانتظار صورة الجوال" else "بانتظار صورة النظارة", color = Color.White,
                    fontSize = if (compactHeight) 20.sp else 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(if (compactHeight) 4.dp else 8.dp))
                Text(cameraState.status, color = EyeMuted, fontSize = if (compactHeight) 13.sp else 15.sp)
                Spacer(Modifier.height(if (compactHeight) 4.dp else 8.dp))
                Text(if (phoneCamera) "اسمح باستخدام الكاميرا عند الطلب، ووجّه كاميرا الجوال الخلفية إلى المشهد."
                    else "ثبّت Eye واسمح بالوصول إلى USB عند الطلب.", color = EyeMuted,
                    fontSize = if (compactHeight) 12.sp else 14.sp)
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (phoneCamera) "إعادة المحاولة" else "إعادة الاتصال", color = EyeAccent)
                }
                Button(onClick = if (phoneCamera) onEyeCamera else onPhoneCamera,
                    colors = ButtonDefaults.buttonColors(containerColor = EyeAccent, contentColor = EyeSurface),
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (phoneCamera) "استخدام كاميرا النظارة" else "استخدام كاميرا الجوال")
                }
                if (onChooseImage != null) TextButton(onClick = onChooseImage, enabled = !imageBusy,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(imageActionLabel, color = EyeAccent) }
                imageError?.let { Text(it, color = Hedayah.HudGold) }
            }
        }

        religiousHud?.invoke(this) { settingsOpen = true }

        if (religiousHud == null && (controlsVisible || !previewLive || ambiguous != null || campus.guidance is GuidanceMode.Arrived)) {
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .onSizeChanged { headerHeightPx = it.height }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        Surface(color = EyePanel, shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HedayahLogo(Modifier.width(40.dp).height(26.dp))
                                Text(if (phoneCamera) { if (previewLive) "الجوال · مباشر" else "كاميرا الجوال" }
                                    else if (poseReady) "Eye · تتبّع الرأس" else if (previewLive) "Eye · مباشر" else "XREAL Eye",
                                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                if (!phoneCamera && previewLive && !calibrated) {
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
                .onSizeChanged { footerHeightPx = it.height }
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
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
                            Text(target?.name ?: "اختر وجهتك", color = Color.White, fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when {
                                campus.guidance is GuidanceMode.Arrived -> "وصلت إلى الوجهة"
                                phoneCamera -> "الصورة من كاميرا الجوال"
                                campus.demoActive -> "موقع تجريبي · الكاميرا والحركة حقيقيتان"
                                distance != null && poseReady -> "${formatDistanceAr(distance)} · مسار تقديري"
                                !previewLive -> "الصورة من كاميرا Eye"
                                !motion.tracking -> "${motion.status}"
                                !calibrated -> "معايرة الاتجاه مطلوبة للأسهم"
                                else -> "الصورة: Eye · الحركة: النظارة"
                            }, color = EyeMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onReligiousHome, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("المرافق الديني", color = EyeAccent, fontSize = 13.sp)
                            }
                            if (!locationGranted || !campus.isGpsEnabled()) {
                                TextButton(onClick = onEnableLocation, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("الموقع", color = EyeAccent, fontSize = 13.sp)
                                }
                            }
                            TextButton(onClick = onOpenCompanion, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text("المساعد والمجموعة", color = EyeAccent)
                            }
                            TextButton(onClick = onPickDestination, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text("الوجهة", color = EyeAccent, fontSize = 13.sp)
                            }
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
                    HedayahMasthead(Modifier.padding(top = 12.dp), compact = true)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (phoneCamera) "الكاميرا والسجلات" else "العرض والاتجاه", color = Color.White, fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { settingsOpen = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("إغلاق", color = EyeAccent)
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                        Text("مصدر الكاميرا", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        FilterChip(selected = !phoneCamera,
                            onClick = { if (phoneCamera) { settingsOpen = false; onEyeCamera() } },
                            label = { Text(if (phoneCamera) "نظارة XREAL Eye · المصدر الافتراضي" else "نظارة XREAL Eye · المصدر الحالي والافتراضي") },
                            colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent,
                                labelColor = Color.White, selectedContainerColor = EyeAccent, selectedLabelColor = EyeSurface),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp))
                        FilterChip(selected = phoneCamera,
                            onClick = { if (!phoneCamera) { settingsOpen = false; onPhoneCamera() } },
                            label = { Text(if (phoneCamera) "كاميرا الجوال · المصدر الحالي" else "استخدام كاميرا الجوال") },
                            colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent,
                                labelColor = Color.White, selectedContainerColor = EyeAccent, selectedLabelColor = EyeSurface),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp))
                        Text("تغيير الكاميرا لا يفعّل إرسال الصور. فعّل المرافق ووافق على مشاركة لقطات المصدر المختار عندما ترغب.",
                            color = EyeMuted, fontSize = 13.sp)
                        Spacer(Modifier.height(20.dp))
                        if (!phoneCamera) {
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
                        } else {
                            Text("وجّه كاميرا الجوال الخلفية إلى المعلم أو اللوحة وثبّت الهاتف لحظات. هذا الوضع يستخدم المرافق الديني والعدّاد نفسه.",
                                color = EyeMuted, fontSize = 14.sp)
                        }
                        TextButton(onClick = onOpenTools, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("الخريطة والأدوات", color = EyeAccent)
                        }
                        TextButton(onClick = onExportLog, enabled = !exportingLog, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (exportingLog) "حفظ…" else "حفظ سجل التشخيص", color = EyeAccent)
                        }
                        if (!locationGranted || !campus.isGpsEnabled()) {
                            TextButton(onClick = onEnableLocation, modifier = Modifier.heightIn(min = 48.dp)) { Text("تفعيل الموقع", color = EyeAccent) }
                        }
                        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (phoneCamera) "إعادة محاولة كاميرا الجوال" else "إعادة اتصال الكاميرا والحساسات", color = EyeAccent)
                        }
                    }
                }
            }
        }
    }
}
