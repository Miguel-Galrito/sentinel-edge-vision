package com.sentinela.crossland.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sentinela.crossland.alarm.AlarmController
import com.sentinela.crossland.data.AppPreferences
import com.sentinela.crossland.data.EvidenceRepository
import com.sentinela.crossland.data.LicensePlateResult
import com.sentinela.crossland.data.NormalizedRect
import com.sentinela.crossland.data.SurveillanceMetrics
import com.sentinela.crossland.data.TargetDetectionEvent
import com.sentinela.crossland.data.VehicleProfileResult
import com.sentinela.crossland.service.CameraService
import com.sentinela.crossland.ui.components.AuditLogBottomSheet
import com.sentinela.crossland.ui.components.CameraPreviewView
import com.sentinela.crossland.ui.components.CyberActionChip
import com.sentinela.crossland.ui.components.GlassBadge
import com.sentinela.crossland.ui.components.GlassCard
import com.sentinela.crossland.ui.components.OledEcoOverlay
import com.sentinela.crossland.ui.components.RoiOverlayView
import com.sentinela.crossland.ui.components.TechMetricPill
import com.sentinela.crossland.ui.components.TelemetryModal
import com.sentinela.crossland.ui.theme.AlarmRed
import com.sentinela.crossland.ui.theme.CyberAmber
import com.sentinela.crossland.ui.theme.CyberBlack
import com.sentinela.crossland.ui.theme.CyberCyan
import com.sentinela.crossland.ui.theme.CyberEmerald
import com.sentinela.crossland.ui.theme.GlassBackgroundDeep
import com.sentinela.crossland.ui.theme.GlassBorderAccent
import com.sentinela.crossland.ui.theme.SentinelaTheme
import com.sentinela.crossland.ui.theme.SurveillanceDarkBg
import com.sentinela.crossland.ui.theme.TextMuted
import com.sentinela.crossland.ui.theme.TextPrimary
import com.sentinela.crossland.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

    private lateinit var appPreferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appPreferences = AppPreferences(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Ao pressionar retroceder para sair, encerra imediatamente a vigilância, liberta a câmara e fecha a tarefa
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                try {
                    CameraService.stopSurveillance(this@MainActivity)
                    AlarmController.getInstance(this@MainActivity).stopAlarm()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                finishAndRemoveTask()
            }
        })

        setContent {
            SentinelaTheme {
                MainScreen(
                    preferences = appPreferences,
                    onSetScreenBrightness = { brightness ->
                        val layoutParams = window.attributes
                        layoutParams.screenBrightness = brightness
                        window.attributes = layoutParams
                    }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Se a vigilância em segundo plano estiver desativada (padrão) e a app for minimizada,
        // desliga imediatamente a câmara e liberta a bateria (0% consumo em segundo plano).
        if (!appPreferences.allowBackgroundSurveillance && CameraService.isServiceRunning.value) {
            try {
                CameraService.stopSurveillance(this)
                AlarmController.getInstance(this).stopAlarm()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            CameraService.stopSurveillance(this)
            AlarmController.getInstance(this).stopAlarm()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Composable
fun MainScreen(
    preferences: AppPreferences,
    onSetScreenBrightness: (Float) -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { perms ->
            hasCameraPermission = perms[Manifest.permission.CAMERA] == true
        }
    )

    LaunchedEffect(Unit) {
        val neededPerms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            neededPerms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(neededPerms.toTypedArray())
    }

    val isServiceRunning by CameraService.isServiceRunning.collectAsState()
    val metrics by CameraService.metrics.collectAsState()
    val isTorchActive by CameraService.isTorchActive.collectAsState()

    val evidenceRepo = remember { EvidenceRepository.getInstance(context) }
    val evidences by evidenceRepo.evidences.collectAsState()

    var roi by remember { mutableStateOf(preferences.roi) }
    var isRoiEditMode by remember { mutableStateOf(false) }
    var isOledEcoMode by remember { mutableStateOf(preferences.isOledEcoMode) }
    var isCalibrationMode by remember { mutableStateOf(preferences.isCalibrationMode) }
    var sensitivity by remember { mutableFloatStateOf(preferences.motionSensitivity) }

    // Modais
    var showTelemetryModal by remember { mutableStateOf(false) }
    var showAuditSheet by remember { mutableStateOf(false) }

    // Atualiza brilho de hardware para OLED Eco
    LaunchedEffect(isOledEcoMode) {
        if (isOledEcoMode) {
            onSetScreenBrightness(0.01f)
        } else {
            onSetScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }
        preferences.isOledEcoMode = isOledEcoMode
    }

    DisposableEffect(Unit) {
        onDispose {
            onSetScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBlack)
    ) {
        if (hasCameraPermission) {
            // 1. Preview da Câmara CameraX
            CameraPreviewView()

            // Overlay de Standby quando a sentinela está desligada (evita imagem congelada)
            if (!isServiceRunning) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xF50A0A0C)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Color(0x22FFFFFF))
                                .border(1.dp, Color(0x44FFFFFF), androidx.compose.foundation.shape.CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "Standby",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "SENTINELA EM STANDBY",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Clica em 'ATIVAR SENTINELA' para iniciar a câmara",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // 2. Retículo Tático / Overlay da ROI e Caixas de Veículos em Tempo Real
            RoiOverlayView(
                roi = roi,
                isEditMode = isRoiEditMode,
                detectedVehicles = if (isServiceRunning) metrics.detectedVehicles else emptyList(),
                onRoiChanged = { updated ->
                    roi = updated
                    preferences.roi = updated
                }
            )

            // 3. HUD Cyber-Sentinel e Controlos
            if (!isOledEcoMode) {
                CyberSurveillanceHud(
                    metrics = metrics,
                    isRoiEditMode = isRoiEditMode,
                    isTorchActive = isTorchActive,
                    isCalibrationMode = isCalibrationMode,
                    evidencesCount = evidences.size,
                    onToggleRoiEdit = { isRoiEditMode = !isRoiEditMode },
                    onEnterEcoMode = { isOledEcoMode = true },
                    onToggleTorch = { CameraService.toggleTorch() },
                    onToggleCalibration = {
                        val next = !isCalibrationMode
                        isCalibrationMode = next
                        preferences.isCalibrationMode = next
                        Toast.makeText(
                            context,
                            if (next) "Modo Calibração (Sem Som) ATIVADO" else "Modo Normal (Som Ativo)",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onOpenTelemetry = { showTelemetryModal = true },
                    onOpenAuditLog = { showAuditSheet = true }
                )

                CyberSurveillanceControls(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    isServiceRunning = isServiceRunning,
                    isCalibrationMode = isCalibrationMode,
                    sensitivity = sensitivity,
                    onSensitivityChange = { newSens ->
                        sensitivity = newSens
                        preferences.motionSensitivity = newSens
                    },
                    onToggleCalibration = {
                        val next = !isCalibrationMode
                        isCalibrationMode = next
                        preferences.isCalibrationMode = next
                        Toast.makeText(
                            context,
                            if (next) "Modo Calibração (Sem Som) ATIVADO" else "Modo Normal (Som Ativo)",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onToggleService = {
                        if (isServiceRunning) {
                            CameraService.stopSurveillance(context)
                        } else {
                            CameraService.startSurveillance(context)
                        }
                    },
                    onTestAlarm = {
                        try {
                            if (isCalibrationMode) {
                                Toast.makeText(context, "Modo Calibração Ativo: Som e vibração desativados.", Toast.LENGTH_SHORT).show()
                            }
                            val testEvent = TargetDetectionEvent(
                                timestamp = System.currentTimeMillis(),
                                plateResult = LicensePlateResult(
                                    detectedText = "28-VE-91 (TESTE)",
                                    normalizedText = "28-VE-91",
                                    isExactTarget = true,
                                    isCloseCandidate = true,
                                    confidence = 1.0f
                                ),
                                profileResult = VehicleProfileResult(
                                    isBicolorCandidate = true,
                                    upperRoofDarkScore = 0.85f,
                                    lowerBodyGreyScore = 0.80f,
                                    overallMatchScore = 0.88f
                                ),
                                snapshotFilePath = null,
                                isHighPriorityAlarm = !isCalibrationMode
                            )
                            AlarmController.getInstance(context).triggerAlarm(testEvent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(context, "Erro no teste: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }

            // 4. Modal de Telemetria
            if (showTelemetryModal) {
                TelemetryModal(
                    metrics = metrics,
                    onDismiss = { showTelemetryModal = false }
                )
            }

            // 5. Histórico de Evidências (BottomSheet)
            if (showAuditSheet) {
                AuditLogBottomSheet(
                    onDismiss = { showAuditSheet = false }
                )
            }

            // 6. Modo Stealth Sentinel (OLED Eco)
            AnimatedVisibility(
                visible = isOledEcoMode,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                OledEcoOverlay(
                    fps = metrics.currentFps,
                    isMotionDetected = metrics.isMotionDetected,
                    onExitEcoMode = { isOledEcoMode = false }
                )
            }
        } else {
            CameraPermissionRequiredScreen(
                onRequestPermission = {
                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                }
            )
        }
    }
}

@Composable
fun CyberSurveillanceHud(
    metrics: SurveillanceMetrics,
    isRoiEditMode: Boolean,
    isTorchActive: Boolean,
    isCalibrationMode: Boolean,
    evidencesCount: Int,
    onToggleRoiEdit: () -> Unit,
    onEnterEcoMode: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleCalibration: () -> Unit,
    onOpenTelemetry: () -> Unit,
    onOpenAuditLog: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp)
    ) {
        // Barra Superior: Status Badge + Linha com Chips de Ação Rápida
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassBadge(
                    text = if (metrics.isServiceRunning) "SENTINELA ATIVA" else "STANDBY",
                    statusColor = if (metrics.isServiceRunning) CyberEmerald else Color.Gray,
                    isPulsing = metrics.isServiceRunning
                )
                if (isCalibrationMode) {
                    GlassBadge(
                        text = "SEM SOM",
                        statusColor = CyberAmber,
                        isPulsing = true
                    )
                }
            }

            // Chips de Ação rápida em carrossel horizontal compacto
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CyberActionChip(
                    icon = if (isCalibrationMode) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    label = if (isCalibrationMode) "CALIBRAÇÃO" else "SOM ATIVO",
                    isActive = isCalibrationMode,
                    activeColor = CyberAmber,
                    onClick = onToggleCalibration
                )

                CyberActionChip(
                    icon = if (isTorchActive) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    label = if (isTorchActive) "LUZ LIGADA" else "LANTERNA",
                    isActive = isTorchActive,
                    activeColor = Color(0xFFFFD600),
                    onClick = onToggleTorch
                )

                CyberActionChip(
                    icon = Icons.Default.CropFree,
                    label = if (isRoiEditMode) "CALIBRANDO" else "CALIBRAR ROI",
                    isActive = isRoiEditMode,
                    activeColor = CyberAmber,
                    onClick = onToggleRoiEdit
                )

                CyberActionChip(
                    icon = Icons.Default.Insights,
                    label = "TELEMETRIA",
                    isActive = false,
                    activeColor = CyberCyan,
                    onClick = onOpenTelemetry
                )

                CyberActionChip(
                    icon = Icons.Default.History,
                    label = if (evidencesCount > 0) "EVIDÊNCIAS ($evidencesCount)" else "EVIDÊNCIAS",
                    isActive = evidencesCount > 0,
                    activeColor = CyberCyan,
                    onClick = onOpenAuditLog
                )

                CyberActionChip(
                    icon = Icons.Default.DarkMode,
                    label = "STEALTH",
                    isActive = false,
                    activeColor = Color.White,
                    onClick = onEnterEcoMode
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Cartão Glassmorphism com Alvo e Métricas Técnicas
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = GlassBackgroundDeep,
            borderColor = if (metrics.isBurstMode) GlassBorderAccent else Color(0x2BFFFFFF)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Linha de Identificação do Alvo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ALVO: OPEL CROSSLAND X",
                        color = Color(0xFFFFD600),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )

                    Text(
                        text = "MATRÍCULA: 28-VE-91",
                        color = CyberEmerald,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Métricas Técnicas em Pílulas Monospace
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TechMetricPill(
                        label = "FPS",
                        value = "%.1f".format(metrics.currentFps),
                        accentColor = if (metrics.isBurstMode) CyberAmber else CyberEmerald
                    )

                    TechMetricPill(
                        label = "LATÊNCIA",
                        value = "${metrics.telemetry.totalLatencyMs}ms",
                        accentColor = CyberCyan
                    )

                    TechMetricPill(
                        label = "MATCH",
                        value = "${(metrics.targetMatchScore * 100).toInt()}%",
                        accentColor = if (metrics.targetMatchScore >= 0.80f) CyberEmerald else if (metrics.targetMatchScore > 0f) CyberAmber else TextMuted
                    )

                    if (metrics.cooldownRemainingSeconds > 0) {
                        TechMetricPill(
                            label = "COOLDOWN",
                            value = "${metrics.cooldownRemainingSeconds}s",
                            accentColor = Color(0xFFFF9100)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Stream de Estado do Reconhecimento e Gating
                val statusText = when {
                    metrics.gateStatus.isNotEmpty() -> metrics.gateStatus
                    metrics.lastOcrRead.isNotEmpty() -> metrics.lastOcrRead
                    else -> "Estrada em monitorização..."
                }
                val isTargetCandidate = metrics.targetMatchScore >= 0.80f

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x66000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = statusText,
                        color = if (isTargetCandidate) Color(0xFFFFEB3B) else if (metrics.detectedVehicles.isNotEmpty()) CyberCyan else TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isTargetCandidate) FontWeight.Black else FontWeight.Normal,
                        maxLines = 1
                    )

                    Text(
                        text = if (metrics.detectedVehicles.isNotEmpty()) "${metrics.detectedVehicles.size} VEÍCULO(S)" else "VIA LIVRE",
                        color = if (isTargetCandidate) CyberEmerald else if (metrics.detectedVehicles.isNotEmpty()) CyberCyan else TextMuted,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun CyberSurveillanceControls(
    modifier: Modifier = Modifier,
    isServiceRunning: Boolean,
    isCalibrationMode: Boolean,
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    onToggleCalibration: () -> Unit,
    onToggleService: () -> Unit,
    onTestAlarm: () -> Unit
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(14.dp),
        backgroundColor = GlassBackgroundDeep,
        shape = RoundedCornerShape(22.dp),
        borderColor = Color(0x33FFFFFF)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Slider de Sensibilidade de Movimento
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sensibilidade do Radar de Movimento",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${sensitivity.toInt()}%",
                    color = CyberEmerald,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Slider(
                value = sensitivity,
                onValueChange = onSensitivityChange,
                valueRange = 10f..60f,
                colors = SliderDefaults.colors(
                    thumbColor = CyberEmerald,
                    activeTrackColor = CyberEmerald,
                    inactiveTrackColor = Color(0x33FFFFFF)
                )
            )

            // Switch de Modo Calibração (Sem Som)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isCalibrationMode) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = if (isCalibrationMode) CyberAmber else CyberEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Modo Calibração (Sem Som)",
                        color = if (isCalibrationMode) CyberAmber else TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                androidx.compose.material3.Switch(
                    checked = isCalibrationMode,
                    onCheckedChange = { onToggleCalibration() },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = CyberAmber,
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color(0x33FFFFFF)
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Botões de Ação Cyber-Sentinel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Iniciar / Desativar Sentinela
                Button(
                    onClick = onToggleService,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServiceRunning) AlarmRed else CyberEmerald,
                        contentColor = if (isServiceRunning) Color.White else Color.Black
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                ) {
                    Icon(
                        imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isServiceRunning) "PARAR SENTINELA" else "ATIVAR SENTINELA",
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Botão de Disparo / Teste Imediato
                Button(
                    onClick = onTestAlarm,
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF222733),
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x40FFD600))
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = "Testar Alarme",
                        tint = Color(0xFFFFD600),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "TESTE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun CameraPermissionRequiredScreen(
    onRequestPermission: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Permissão de Câmara Necessária",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "A aplicação necessita da câmara para monitorizar a via e detetar o Opel Crossland X.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "CONCEDER PERMISSÃO", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}
