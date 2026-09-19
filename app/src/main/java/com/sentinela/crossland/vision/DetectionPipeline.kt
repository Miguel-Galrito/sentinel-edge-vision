package com.sentinela.crossland.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.sentinela.crossland.data.NormalizedRect
import com.sentinela.crossland.data.SurveillanceMetrics
import com.sentinela.crossland.data.TargetDetectionEvent
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orquestrador do Pipeline de Deteção em Dois Níveis com foco em eficiência de bateria.
 */
class DetectionPipeline(
    private val context: Context,
    private val getRoi: () -> NormalizedRect,
    private val getSensitivity: () -> Float
) : ImageAnalysis.Analyzer {

    private val pipelineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val motionDetector = MotionDetector()
    private val profileAnalyzer = VehicleProfileAnalyzer()
    private val plateRecognizer = LicensePlateRecognizer()

    // Fluxos reativos para atualização da UI e disparo de alarmes
    private val _detectionEvents = MutableSharedFlow<TargetDetectionEvent>(extraBufferCapacity = 5)
    val detectionEvents: SharedFlow<TargetDetectionEvent> = _detectionEvents.asSharedFlow()

    private val _metrics = MutableStateFlow(SurveillanceMetrics())
    val metrics: StateFlow<SurveillanceMetrics> = _metrics.asStateFlow()

    // Controle de FPS, Cooldown de alarme e Varredura Periódica de Veículo Parado
    private var frameCount = 0
    private var lastFpsCalculationTime = System.currentTimeMillis()
    private var lastAlarmTriggerTimestamp = 0L
    private var lastStationaryScanTimestamp = 0L
    private val alarmCooldownMs = 15_000L // 15 segundos entre alarmes repetidos do mesmo veículo
    private val stationaryScanIntervalMs = 1_500L // A cada 1.5s mesmo parado!

    private var isBusyProcessingLevel2 = false

    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        frameCount++

        // Cálculo periódico de FPS a cada 1 segundo
        if (currentTime - lastFpsCalculationTime >= 1000L) {
            val elapsed = (currentTime - lastFpsCalculationTime) / 1000f
            val currentFps = frameCount / elapsed
            _metrics.value = _metrics.value.copy(currentFps = currentFps)
            frameCount = 0
            lastFpsCalculationTime = currentTime
        }

        val currentRoi = getRoi()
        val sensitivity = getSensitivity()

        // -------------------------------------------------------------
        // NÍVEL 1: Motion / Light check (Ultraleve, plano Y)
        // -------------------------------------------------------------
        val motionResult = motionDetector.processFrame(imageProxy, currentRoi, sensitivity)

        val isStationaryScanDue = (currentTime - lastStationaryScanTimestamp) > stationaryScanIntervalMs

        if (motionResult.frameSkipped && !isStationaryScanDue) {
            imageProxy.close()
            return
        }

        _metrics.value = _metrics.value.copy(
            isMotionDetected = motionResult.isMotionDetected,
            motionRatio = motionResult.changedCellsRatio,
            isBurstMode = motionResult.isBurstModeActive
        )

        // Aciona Nível 2 se houver movimento OU se estiver na hora da varredura periódica do veículo parado
        val shouldRunLevel2 = motionResult.isMotionDetected || isStationaryScanDue

        if (!shouldRunLevel2) {
            imageProxy.close()
            return
        }

        if (isStationaryScanDue && !motionResult.isMotionDetected) {
            lastStationaryScanTimestamp = currentTime
        }

        // Se já estivermos a processar um frame de Nível 2 em corrotina, ignora este para não acumular fila
        if (isBusyProcessingLevel2) {
            imageProxy.close()
            return
        }

        isBusyProcessingLevel2 = true

        // -------------------------------------------------------------
        // NÍVEL 2: Inferência Completa (Perfil Bicolor + ML Kit OCR)
        // -------------------------------------------------------------
        pipelineScope.launch {
            try {
                // 1. Verificação de Perfil Bicolor Opel Crossland X (adaptativo dia/noite)
                val profileResult = profileAnalyzer.analyzeBicolorProfile(imageProxy, currentRoi)

                // 2. Reconhecimento OCR de Matrícula (ML Kit)
                val plateResult = plateRecognizer.recognizePlate(imageProxy, currentRoi)

                if (plateResult != null && plateResult.detectedText.isNotEmpty()) {
                    _metrics.value = _metrics.value.copy(lastOcrRead = plateResult.detectedText)
                }

                // 3. Regra de Decisão do Alarme Crítico
                val isTargetPlateFound = plateResult?.isExactTarget == true
                val isCloseCandidate = plateResult?.isCloseCandidate == true
                val isBicolor = profileResult.isBicolorCandidate

                val hasPlateFragment = plateResult?.detectedText?.let { text ->
                    text.contains("28") || text.contains("VE", ignoreCase = true) || text.contains("91")
                } ?: false

                val shouldTriggerAlarm = isTargetPlateFound ||
                        (isCloseCandidate && isBicolor) ||
                        (profileResult.overallMatchScore > 0.65f && hasPlateFragment) ||
                        (profileResult.overallMatchScore > 0.78f)

                if (shouldTriggerAlarm && (currentTime - lastAlarmTriggerTimestamp > alarmCooldownMs)) {
                    lastAlarmTriggerTimestamp = currentTime

                    // Salva snapshot imediatamente do frame
                    val snapshotPath = saveSnapshotToFile(imageProxy)

                    val event = TargetDetectionEvent(
                        timestamp = currentTime,
                        plateResult = plateResult ?: com.sentinela.crossland.data.LicensePlateResult(
                            detectedText = "28-VE-91 (Visual Match)",
                            normalizedText = "28-VE-91",
                            isExactTarget = true,
                            isCloseCandidate = true,
                            confidence = profileResult.overallMatchScore
                        ),
                        profileResult = profileResult,
                        snapshotFilePath = snapshotPath,
                        isHighPriorityAlarm = true
                    )

                    _detectionEvents.emit(event)
                    _metrics.value = _metrics.value.copy(
                        totalDetectionsCount = _metrics.value.totalDetectionsCount + 1
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isBusyProcessingLevel2 = false
                imageProxy.close()
            }
        }
    }

    /**
     * Converte o ImageProxy YUV para JPEG e grava no armazenamento interno da app.
     */
    private fun saveSnapshotToFile(imageProxy: ImageProxy): String? {
        return try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out)
            val imageBytes = out.toByteArray()

            var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            // Corrige rotação do sensor se necessário
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

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

    fun release() {
        plateRecognizer.close()
        motionDetector.reset()
    }
}
