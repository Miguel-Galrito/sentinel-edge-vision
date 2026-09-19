package com.sentinela.crossland.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sentinela.crossland.alarm.AlarmController
import com.sentinela.crossland.data.AppPreferences
import com.sentinela.crossland.data.LicensePlateResult
import com.sentinela.crossland.data.NormalizedRect
import com.sentinela.crossland.data.SurveillanceMetrics
import com.sentinela.crossland.data.TargetDetectionEvent
import com.sentinela.crossland.data.VehicleProfileResult
import com.sentinela.crossland.service.CameraService
import com.sentinela.crossland.ui.components.CameraPreviewView
import com.sentinela.crossland.ui.components.OledEcoOverlay
import com.sentinela.crossland.ui.components.RoiOverlayView
import com.sentinela.crossland.ui.theme.AlarmRed
import com.sentinela.crossland.ui.theme.SentinelaTheme
import com.sentinela.crossland.ui.theme.SurveillanceAccent
import com.sentinela.crossland.ui.theme.SurveillanceCard
import com.sentinela.crossland.ui.theme.SurveillanceDarkBg

class MainActivity : ComponentActivity() {

    private lateinit var appPreferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appPreferences = AppPreferences(this)

        // Mantém ecrã ligado enquanto em modo de configuração/vigilância ativa
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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

    var roi by remember { mutableStateOf(preferences.roi) }
    var isRoiEditMode by remember { mutableStateOf(false) }
    var isOledEcoMode by remember { mutableStateOf(preferences.isOledEcoMode) }
    var sensitivity by remember { mutableFloatStateOf(preferences.motionSensitivity) }

    // Atualiza brilho quando entra/sai do modo OLED Eco
    LaunchedEffect(isOledEcoMode) {
        if (isOledEcoMode) {
            onSetScreenBrightness(0.01f) // Brilho mínimo de hardware
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
            .background(SurveillanceDarkBg)
    ) {
        if (hasCameraPermission) {
            // 1. Preview da Câmara CameraX
            CameraPreviewView()

            // 2. Overlay Interativo da ROI
            RoiOverlayView(
                roi = roi,
                isEditMode = isRoiEditMode,
                onRoiChanged = { updated ->
                    roi = updated
                    preferences.roi = updated
                }
            )

            // 3. HUD e Painel de Controlo Superior / Inferior
            if (!isOledEcoMode) {
                SurveillanceHud(
                    metrics = metrics,
                    isRoiEditMode = isRoiEditMode,
                    isTorchActive = isTorchActive,
                    onToggleRoiEdit = { isRoiEditMode = !isRoiEditMode },
                    onEnterEcoMode = { isOledEcoMode = true },
                    onToggleTorch = { CameraService.toggleTorch() }
                )

                SurveillanceControls(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    isServiceRunning = isServiceRunning,
                    sensitivity = sensitivity,
                    onSensitivityChange = { newSens ->
                        sensitivity = newSens
                        preferences.motionSensitivity = newSens
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
                                isHighPriorityAlarm = true
                            )
                            AlarmController.getInstance(context).triggerAlarm(testEvent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(context, "Erro no teste: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }

            // 4. Modo OLED Eco (Ecrã Preto Puro com toque duplo para acordar)
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
            // Pedido de Permissão de Câmara
            CameraPermissionRequiredScreen(
                onRequestPermission = {
                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                }
            )
        }
    }
}

@Composable
fun SurveillanceHud(
    metrics: SurveillanceMetrics,
    isRoiEditMode: Boolean,
    isTorchActive: Boolean,
    onToggleRoiEdit: () -> Unit,
    onEnterEcoMode: () -> Unit,
    onToggleTorch: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Barra Superior com Status e Atalhos
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCard.copy(alpha = 0.85f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (metrics.isServiceRunning) SurveillanceAccent else Color.Gray,
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (metrics.isServiceRunning) "SENTINELA ATIVA" else "SENTINELA PARADA",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row {
                // Botão de Lanterna para iluminação noturna
                IconButton(
                    onClick = onToggleTorch,
                    modifier = Modifier
                        .background(
                            if (isTorchActive) Color(0xFFFFEB3B) else SurveillanceCard.copy(alpha = 0.85f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (isTorchActive) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Lanterna",
                        tint = if (isTorchActive) Color.Black else Color.White
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = onToggleRoiEdit,
                    modifier = Modifier
                        .background(
                            if (isRoiEditMode) Color(0xFFFFB300) else SurveillanceCard.copy(alpha = 0.85f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.CropFree,
                        contentDescription = "Ajustar ROI",
                        tint = if (isRoiEditMode) Color.Black else Color.White
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = onEnterEcoMode,
                    modifier = Modifier
                        .background(SurveillanceCard.copy(alpha = 0.85f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.DarkMode,
                        contentDescription = "Modo OLED Eco",
                        tint = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Cartão de Métricas em Tempo Real (FPS, Movimento, Alvo)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurveillanceCard.copy(alpha = 0.85f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "ALVO: OPEL CROSSLAND X",
                        color = Color(0xFFFFEB3B),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "MATRÍCULA: 28-VE-91",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (metrics.isBurstMode) "Taxa: ${"%.1f".format(metrics.currentFps)} FPS (BURST)" else "Taxa: ${"%.1f".format(metrics.currentFps)} FPS (Eco)",
                        color = if (metrics.isBurstMode) Color(0xFFFFB300) else Color.LightGray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (metrics.isBurstMode) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        text = if (metrics.isBurstMode) "RASTREAMENTO RÁPIDO!" else if (metrics.isMotionDetected) "MOVIMENTO NA VIA!" else "Via / Parado Monitorizado",
                        color = if (metrics.isBurstMode) Color(0xFFFF5252) else if (metrics.isMotionDetected) Color(0xFFFFB300) else Color(0xFF81C784),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                val ocrText = if (metrics.lastOcrRead.isNotEmpty()) metrics.lastOcrRead else "A escanear matrículas (dia/noite)..."
                Text(
                    text = "OCR / Leitura: $ocrText",
                    color = if (metrics.lastOcrRead.contains("28") || metrics.lastOcrRead.contains("VE", ignoreCase = true) || metrics.lastOcrRead.contains("91")) Color(0xFFFFEB3B) else Color(0xFF80DEEA),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "Modo: Parado (1.5s) + Movimento | Noite Adaptativo",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun SurveillanceControls(
    modifier: Modifier = Modifier,
    isServiceRunning: Boolean,
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    onToggleService: () -> Unit,
    onTestAlarm: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurveillanceCard.copy(alpha = 0.95f)),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Slider de Sensibilidade de Movimento
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sensibilidade Movimento",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${sensitivity.toInt()}%",
                    color = SurveillanceAccent,
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
                    thumbColor = SurveillanceAccent,
                    activeTrackColor = SurveillanceAccent
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Botões de Ação
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Iniciar / Parar Vigilância
                Button(
                    onClick = onToggleService,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServiceRunning) AlarmRed else SurveillanceAccent,
                        contentColor = if (isServiceRunning) Color.White else Color.Black
                    )
                ) {
                    Icon(
                        imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isServiceRunning) "PARAR" else "INICIAR SENTINELA",
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp
                    )
                }

                // Botão de Teste de Alarme
                Button(
                    onClick = onTestAlarm,
                    modifier = Modifier.height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF37474F),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = "Testar Alarme",
                        tint = Color(0xFFFFEB3B)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "TESTE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
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
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "A aplicação necessita da câmara para monitorizar a via e detetar o Opel Crossland X.",
                color = Color.LightGray,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = SurveillanceAccent)
            ) {
                Text(text = "CONCEDER PERMISSÃO", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}
