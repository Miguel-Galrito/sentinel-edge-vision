package com.sentinela.crossland.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.sentinela.crossland.data.DetectedVehicleBox
import com.sentinela.crossland.data.DetectionEvidence
import com.sentinela.crossland.data.EvidenceRepository
import com.sentinela.crossland.data.EvidenceStatus
import com.sentinela.crossland.data.NormalizedRect
import com.sentinela.crossland.data.SurveillanceMetrics
import com.sentinela.crossland.data.TargetDetectionEvent
import com.sentinela.crossland.data.TelemetryStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pipeline de Deteção Sequencial em 4 Níveis (Sentinel Vision Gating System - Perfil Lateral).
 *
 * Regras Obrigatórias de Validação:
 * - Regra de Ouro: Proibição estrita de disparo por cor isolada.
 * - Nível 1: Deteção de Movimento relevante na ROI (descarta frames sem trânsito ou com alterações globais).
 * - Nível 2: Classificação Semântica On-Device (ML Kit Image Labeling): objeto TEM de ser Veículo com confiança > 80%.
 *            Se for eletrónico, mobília, pessoa ou indeterminado -> ABORTAR imediatamente.
 * - Nível 3: Validação Biométrica Lateral 3-Tier do Opel Crossland X Bicolor (tejadilho flutuante preto,
 *            carroçaria prata metálica acromática, proteções inferiores SUV em plástico preto mate e proporções de perfil).
 *            OCR de Matrícula (28-VE-91) executado em segundo plano como bónus imediato (100%), mas não bloqueador.
 * - Nível 4: Persistência Temporal: validação positiva em pelo menos 2 frames consecutivos (intervalo <= 1.5s).
 *
 * Mecanismos Anti-Loop (Fail-Safes):
 * - Cooldown de 120s configurável após disparo.
 * - Suporte a Modo Silencioso / Calibração (sem som/vibração).
 * - Suporte a Pausa / Snooze de 10 minutos.
 */
class DetectionPipeline(
    private val context: Context,
    private val getRoi: () -> NormalizedRect,
    private val getSensitivity: () -> Float,
    private val getCooldownSeconds: () -> Int = { 120 },
    private val isCalibrationMode: () -> Boolean = { false },
    private val isSnoozed: () -> Boolean = { false }
) : ImageAnalysis.Analyzer {

    private val pipelineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val evidenceRepository = EvidenceRepository.getInstance(context)

    private val motionDetector = MotionDetector()
    private val semanticClassifier = VehicleSemanticClassifier()
    private val plateRecognizer = LicensePlateRecognizer()
    private val profileAnalyzer = VehicleProfileAnalyzer()

    // Fluxos reativos para atualização da UI e disparo de alarmes
    private val _detectionEvents = MutableSharedFlow<TargetDetectionEvent>(extraBufferCapacity = 5)
    val detectionEvents: SharedFlow<TargetDetectionEvent> = _detectionEvents.asSharedFlow()

    private val _metrics = MutableStateFlow(SurveillanceMetrics())
    val metrics: StateFlow<SurveillanceMetrics> = _metrics.asStateFlow()

    // Métricas e controlo de frequência
    private var frameCount = 0
    private var lastFpsCalculationTime = System.currentTimeMillis()
    private var lastAlarmTriggerTimestamp = 0L
    private var lastValidMatchTimestamp = 0L
    private var consecutiveMatchCount = 0
    private var isBusyProcessing = false

    fun resetAlarmCooldown() {
        lastAlarmTriggerTimestamp = 0L
        consecutiveMatchCount = 0
    }

    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        val startFrameTime = SystemClock.elapsedRealtime()
        frameCount++

        val cooldownDurationMs = getCooldownSeconds() * 1000L
        val remainingCooldownSec = if (lastAlarmTriggerTimestamp > 0L) {
            ((lastAlarmTriggerTimestamp + cooldownDurationMs - currentTime) / 1000L).coerceAtLeast(0L).toInt()
        } else {
            0
        }

        // Cálculo de FPS a cada 1 segundo
        if (currentTime - lastFpsCalculationTime >= 1000L) {
            val elapsed = (currentTime - lastFpsCalculationTime) / 1000f
            val currentFps = frameCount / elapsed
            _metrics.value = _metrics.value.copy(
                currentFps = currentFps,
                cooldownRemainingSeconds = remainingCooldownSec,
                isCalibrationActive = isCalibrationMode(),
                isSnoozedActive = isSnoozed()
            )
            frameCount = 0
            lastFpsCalculationTime = currentTime
        }

        val currentRoi = getRoi()
        val sensitivity = getSensitivity()

        // =========================================================================
        // NÍVEL 1: DETEÇÃO DE MOVIMENTO NA ROI
        // =========================================================================
        val startMotion = SystemClock.elapsedRealtime()
        val motionResult = motionDetector.processFrame(imageProxy, currentRoi, sensitivity)
        val motionDuration = SystemClock.elapsedRealtime() - startMotion

        // O frame SÓ é processado se houver movimento relevante dentro da ROI.
        // Varreduras em imagens estáticas foram terminantemente removidas.
        if (motionResult.frameSkipped || !motionResult.isMotionDetected) {
            _metrics.value = _metrics.value.copy(
                isMotionDetected = false,
                motionRatio = motionResult.changedCellsRatio,
                isBurstMode = false,
                cooldownRemainingSeconds = remainingCooldownSec,
                isCalibrationActive = isCalibrationMode(),
                isSnoozedActive = isSnoozed(),
                gateStatus = if (remainingCooldownSec > 0) "COOLDOWN (${remainingCooldownSec}s)" else "ESTRADA LIMPA"
            )
            imageProxy.close()
            return
        }

        _metrics.value = _metrics.value.copy(
            isMotionDetected = true,
            motionRatio = motionResult.changedCellsRatio,
            isBurstMode = motionResult.isBurstModeActive
        )

        if (isBusyProcessing) {
            imageProxy.close()
            return
        }

        isBusyProcessing = true

        pipelineScope.launch {
            try {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                // Converte frame para Bitmap vertical
                val rawBitmap = imageProxy.toBitmap()
                val uprightBitmap = if (rotationDegrees != 0) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                } else {
                    rawBitmap
                }

                val frameW = uprightBitmap.width
                val frameH = uprightBitmap.height
                val roiL = (currentRoi.left * frameW).toInt().coerceIn(0, frameW - 1)
                val roiT = (currentRoi.top * frameH).toInt().coerceIn(0, frameH - 1)
                val roiR = (currentRoi.right * frameW).toInt().coerceIn(roiL + 20, frameW)
                val roiB = (currentRoi.bottom * frameH).toInt().coerceIn(roiT + 20, frameH)

                val roiW = (roiR - roiL).coerceAtLeast(20)
                val roiH = (roiB - roiT).coerceAtLeast(20)

                val roiBitmap = Bitmap.createBitmap(uprightBitmap, roiL, roiT, roiW, roiH)

                // =========================================================================
                // NÍVEL 2: CLASSIFICAÇÃO OBRIGATÓRIA DO OBJETO (VEÍCULO > 80%)
                // =========================================================================
                val startSemantic = SystemClock.elapsedRealtime()
                val semanticResult = semanticClassifier.classify(roiBitmap)
                val semanticDuration = SystemClock.elapsedRealtime() - startSemantic

                if (!semanticResult.isVehicle) {
                    consecutiveMatchCount = 0
                    val rejectMsg = semanticResult.rejectionReason
                        ?: "Objeto não é veículo (${semanticResult.topLabel})"
                    _metrics.value = _metrics.value.copy(
                        targetMatchScore = 0f,
                        detectedVehicles = emptyList(),
                        lastOcrRead = rejectMsg,
                        gateStatus = "N2 REJEITADO: $rejectMsg",
                        telemetry = TelemetryStats(
                            preprocessMs = motionDuration,
                            classificationMs = semanticDuration,
                            ocrMs = 0L,
                            totalLatencyMs = SystemClock.elapsedRealtime() - startFrameTime,
                            thermalStatus = queryThermalStatus(),
                            memoryUsageMb = queryMemoryUsageMb()
                        )
                    )
                    return@launch
                }

                // =========================================================================
                // NÍVEL 3: VALIDAÇÃO BIOMÉTRICA LATERAL DO OPEL CROSSLAND X BICOLOR
                // =========================================================================
                val startProfile = SystemClock.elapsedRealtime()
                val outcome = profileAnalyzer.analyzeRoiForOpelCrossland(
                    uprightBitmap,
                    currentRoi,
                    isVehicleSemanticConfirmed = true
                )
                val profileDuration = SystemClock.elapsedRealtime() - startProfile

                if (!outcome.profileResult.isBicolorCandidate || outcome.profileResult.overallMatchScore < VehicleProfileAnalyzer.MIN_MATCH_SCORE) {
                    consecutiveMatchCount = 0
                    val minScorePercent = (VehicleProfileAnalyzer.MIN_MATCH_SCORE * 100).toInt()
                    val rejectReason = "Perfil não corresponde (${(outcome.profileResult.overallMatchScore * 100).toInt()}% < ${minScorePercent}%)"
                    _metrics.value = _metrics.value.copy(
                        targetMatchScore = 0f,
                        detectedVehicles = emptyList(),
                        lastOcrRead = rejectReason,
                        gateStatus = "N3 REJEITADO: NÃO É CROSSLAND X",
                        telemetry = TelemetryStats(
                            preprocessMs = motionDuration,
                            classificationMs = semanticDuration,
                            ocrMs = profileDuration,
                            totalLatencyMs = SystemClock.elapsedRealtime() - startFrameTime,
                            thermalStatus = queryThermalStatus(),
                            memoryUsageMb = queryMemoryUsageMb()
                        )
                    )
                    return@launch
                }

                // OCR de Matrícula Secundário / Bónus (Não bloqueia se não ler de lado)
                val startOcr = SystemClock.elapsedRealtime()
                val plateResult = plateRecognizer.recognizePlateInBitmap(roiBitmap)
                val ocrDuration = SystemClock.elapsedRealtime() - startOcr

                val isPlateMatched = plateResult != null && plateResult.isCloseCandidate
                val finalScore = if (isPlateMatched) 1.0f else outcome.profileResult.overallMatchScore
                val displayLabel = if (isPlateMatched) {
                    "OPEL 28-VE-91 [100%]"
                } else {
                    "OPEL CROSSLAND X [${(outcome.profileResult.overallMatchScore * 100).toInt()}%]"
                }
                val ocrDisplayText = if (isPlateMatched) {
                    "MATRÍCULA CONFIRMADA: 28-VE-91"
                } else {
                    "PERFIL LATERAL CONFIRMADO [${(outcome.profileResult.overallMatchScore * 100).toInt()}%]"
                }

                // =========================================================================
                // NÍVEL 4: PERSISTÊNCIA TEMPORAL (ANTI-FLICKER - MÍNIMO 2 FRAMES)
                // =========================================================================
                val timeSinceLastValid = currentTime - lastValidMatchTimestamp
                if (timeSinceLastValid <= 1500L) {
                    consecutiveMatchCount++
                } else {
                    consecutiveMatchCount = 1
                }
                lastValidMatchTimestamp = currentTime

                val displayBox = outcome.vehicleBox?.copy(
                    label = displayLabel,
                    isTargetOpel = true,
                    matchScore = finalScore
                ) ?: DetectedVehicleBox(
                    left = currentRoi.left,
                    top = currentRoi.top,
                    right = currentRoi.right,
                    bottom = currentRoi.bottom,
                    isTargetOpel = true,
                    matchScore = finalScore,
                    label = displayLabel
                )

                val telemetry = TelemetryStats(
                    preprocessMs = motionDuration,
                    classificationMs = semanticDuration,
                    ocrMs = ocrDuration + profileDuration,
                    totalLatencyMs = SystemClock.elapsedRealtime() - startFrameTime,
                    thermalStatus = queryThermalStatus(),
                    memoryUsageMb = queryMemoryUsageMb()
                )

                if (consecutiveMatchCount < 2) {
                    _metrics.value = _metrics.value.copy(
                        targetMatchScore = finalScore,
                        detectedVehicles = listOf(displayBox),
                        lastOcrRead = "$ocrDisplayText [1/2 FRAMES]",
                        gateStatus = "N4: A AGUARDAR 2.º FRAME DE CONFIRMAÇÃO",
                        telemetry = telemetry
                    )
                    return@launch
                }

                // =========================================================================
                // VERIFICAÇÃO DE FAIL-SAFES (COOLDOWN, SNOOZE, CALIBRAÇÃO)
                // =========================================================================

                // 1. Snooze (Pausa 10 min)
                if (isSnoozed()) {
                    _metrics.value = _metrics.value.copy(
                        targetMatchScore = finalScore,
                        detectedVehicles = listOf(displayBox),
                        lastOcrRead = "ALVO DETETADO (PAUSA DE 10 MIN ATIVA)",
                        gateStatus = "SENTINELA EM PAUSA",
                        isSnoozedActive = true,
                        telemetry = telemetry
                    )
                    return@launch
                }

                // 2. Cooldown após alarme (120s)
                val timeSinceLastAlarm = currentTime - lastAlarmTriggerTimestamp
                if (timeSinceLastAlarm < cooldownDurationMs) {
                    val remainingSec = ((cooldownDurationMs - timeSinceLastAlarm) / 1000L).coerceAtLeast(1L).toInt()
                    _metrics.value = _metrics.value.copy(
                        targetMatchScore = finalScore,
                        detectedVehicles = listOf(displayBox),
                        lastOcrRead = "ALVO DETETADO (COOLDOWN ATIVO: ${remainingSec}s)",
                        gateStatus = "COOLDOWN ATIVO (${remainingSec}s)",
                        cooldownRemainingSeconds = remainingSec,
                        telemetry = telemetry
                    )
                    return@launch
                }

                val detectedPlateText = if (isPlateMatched) plateResult!!.normalizedText else "OPEL CROSSLAND X"
                val evidenceNotes = if (isPlateMatched) {
                    "VALIDAÇÃO COMPLETA: Perfil ${(outcome.profileResult.overallMatchScore * 100).toInt()}% + Matrícula 28-VE-91"
                } else {
                    "VALIDAÇÃO LATERAL: Perfil Opel Crossland X Bicolor ${(outcome.profileResult.overallMatchScore * 100).toInt()}%"
                }

                // 3. Modo Calibração (Sem Som)
                if (isCalibrationMode()) {
                    lastAlarmTriggerTimestamp = currentTime
                    consecutiveMatchCount = 0

                    val snapshotPath = saveBitmapSnapshotToFile(uprightBitmap)
                    val evidence = DetectionEvidence(
                        timestamp = currentTime,
                        snapshotFilePath = snapshotPath,
                        detectedPlate = detectedPlateText,
                        confidence = semanticResult.confidence,
                        profileScore = outcome.profileResult.overallMatchScore,
                        status = EvidenceStatus.UNREVIEWED,
                        notes = "MODO CALIBRAÇÃO (Sem som): $evidenceNotes"
                    )
                    evidenceRepository.addEvidence(evidence)

                    _metrics.value = _metrics.value.copy(
                        targetMatchScore = finalScore,
                        detectedVehicles = listOf(displayBox),
                        lastOcrRead = "CALIBRAÇÃO: $displayLabel VALIDADO (SEM SOM)",
                        gateStatus = "CALIBRAÇÃO ATIVA (SEM SOM)",
                        isCalibrationActive = true,
                        totalDetectionsCount = _metrics.value.totalDetectionsCount + 1,
                        telemetry = telemetry
                    )
                    return@launch
                }

                // =========================================================================
                // DISPARO CONFIRMADO DO ALARME (TODOS OS 4 NÍVEIS CUMPRIDOS)
                // =========================================================================
                lastAlarmTriggerTimestamp = currentTime
                consecutiveMatchCount = 0

                val snapshotPath = saveBitmapSnapshotToFile(uprightBitmap)

                val event = TargetDetectionEvent(
                    timestamp = currentTime,
                    plateResult = plateResult ?: com.sentinela.crossland.data.LicensePlateResult(
                        detectedText = "OPEL CROSSLAND X",
                        normalizedText = "CROSSLAND-X",
                        isExactTarget = false,
                        isCloseCandidate = true,
                        confidence = outcome.profileResult.overallMatchScore
                    ),
                    profileResult = outcome.profileResult,
                    snapshotFilePath = snapshotPath,
                    isHighPriorityAlarm = true,
                    vehicleBox = displayBox
                )

                val evidence = DetectionEvidence(
                    timestamp = currentTime,
                    snapshotFilePath = snapshotPath,
                    detectedPlate = detectedPlateText,
                    confidence = semanticResult.confidence,
                    profileScore = outcome.profileResult.overallMatchScore,
                    status = EvidenceStatus.UNREVIEWED,
                    notes = evidenceNotes
                )
                evidenceRepository.addEvidence(evidence)

                _detectionEvents.emit(event)
                _metrics.value = _metrics.value.copy(
                    targetMatchScore = finalScore,
                    detectedVehicles = listOf(displayBox),
                    lastOcrRead = "ALARME! ALVO DETETADO: $displayLabel",
                    gateStatus = "ALARME ATIVO (ALVO CONFIRMADO)",
                    totalDetectionsCount = _metrics.value.totalDetectionsCount + 1,
                    cooldownRemainingSeconds = getCooldownSeconds(),
                    telemetry = telemetry
                )
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isBusyProcessing = false
                imageProxy.close()
            }
        }
    }

    private fun saveBitmapSnapshotToFile(bitmap: Bitmap): String? {
        return try {
            val snapshotDir = File(context.filesDir, "snapshots").apply { mkdirs() }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(snapshotDir, "OPEL_CROSSLAND_${timeStamp}.jpg")
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun queryThermalStatus(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            when (pm?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "NOMINAL"
                PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                else -> "NOMINAL"
            }
        } else {
            "NOMINAL"
        }
    }

    private fun queryMemoryUsageMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }

    fun release() {
        semanticClassifier.close()
        plateRecognizer.close()
        motionDetector.reset()
    }
}
