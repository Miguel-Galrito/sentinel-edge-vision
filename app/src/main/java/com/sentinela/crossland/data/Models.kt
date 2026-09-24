package com.sentinela.crossland.data

import android.graphics.RectF
import java.io.Serializable

/**
 * Retângulo normalizado (valores entre 0.0f e 1.0f) para definir a Região de Interesse (ROI).
 * Isto permite que a ROI seja independente da resolução do ecrã ou do frame da câmara.
 */
data class NormalizedRect(
    val left: Float = 0.05f,
    val top: Float = 0.35f,
    val right: Float = 0.95f,
    val bottom: Float = 0.85f
) : Serializable {

    fun toRectF(width: Int, height: Int): RectF {
        return RectF(
            left * width,
            top * height,
            right * width,
            bottom * height
        )
    }

    fun contains(x: Float, y: Float): Boolean {
        return x in left..right && y in top..bottom
    }

    fun coerceValid(): NormalizedRect {
        val l = left.coerceIn(0f, 0.9f)
        val t = top.coerceIn(0f, 0.9f)
        val r = right.coerceIn(l + 0.05f, 1.0f)
        val b = bottom.coerceIn(t + 0.05f, 1.0f)
        return NormalizedRect(l, t, r, b)
    }
}

/**
 * Caixa delimitadora normalizada de um veículo localizado no frame da câmara.
 */
data class DetectedVehicleBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val isTargetOpel: Boolean,
    val matchScore: Float,
    val label: String
) : Serializable

/**
 * Resultado da análise de perfil do veículo (Opel Crossland X Bicolor).
 */
data class VehicleProfileResult(
    val isBicolorCandidate: Boolean,
    val upperRoofDarkScore: Float,   // Proporção de escuridão no tejadilho/pilares (0.0 a 1.0)
    val lowerBodyGreyScore: Float,   // Proporção de cinzento claro na carroçaria (0.0 a 1.0)
    val contrastRatio: Float = 1.0f, // Rácio de luminância carroçaria / tejadilho
    val overallMatchScore: Float,    // Pontuação composta (0.0 a 1.0)
    val isVehicleDetected: Boolean = true
) : Serializable

/**
 * Resultado do reconhecimento ótico de caracteres (OCR) da matrícula.
 */
data class LicensePlateResult(
    val detectedText: String = "OPEL CROSSLAND X",
    val normalizedText: String = "CROSSLAND-X",
    val isExactTarget: Boolean = true,
    val isCloseCandidate: Boolean = true,
    val confidence: Float = 1.0f,
    val rawReadSnippet: String = ""
) : Serializable

/**
 * Evento consolidado emitido pelo pipeline quando o veículo-alvo é detetado.
 */
data class TargetDetectionEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val plateResult: LicensePlateResult = LicensePlateResult(),
    val profileResult: VehicleProfileResult,
    val snapshotFilePath: String?,
    val isHighPriorityAlarm: Boolean = true,
    val vehicleBox: DetectedVehicleBox? = null
) : Serializable

/**
 * Estatísticas detalhadas de telemetria e diagnóstico do pipeline.
 */
data class TelemetryStats(
    val preprocessMs: Long = 0L,
    val classificationMs: Long = 0L,
    val ocrMs: Long = 0L,
    val totalLatencyMs: Long = 0L,
    val thermalStatus: String = "NOMINAL",
    val memoryUsageMb: Long = 0L,
    val isOledEcoActive: Boolean = false
) : Serializable

/**
 * Estado de validação da evidência capturada (Triagem de falsos positivos).
 */
enum class EvidenceStatus : Serializable {
    UNREVIEWED,
    CONFIRMED,
    FALSE_POSITIVE
}

/**
 * Registo de auditoria / evidência forense de deteção de veículo.
 */
data class DetectionEvidence(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val snapshotFilePath: String?,
    val detectedPlate: String,
    val confidence: Float,
    val profileScore: Float,
    val status: EvidenceStatus = EvidenceStatus.UNREVIEWED,
    val notes: String = ""
) : Serializable

/**
 * Estatísticas em tempo real do processamento de visão para HUD/UI.
 */
data class SurveillanceMetrics(
    val currentFps: Float = 0.0f,
    val isMotionDetected: Boolean = false,
    val motionRatio: Float = 0.0f,
    val isBurstMode: Boolean = false,
    val lastOcrRead: String = "",
    val totalDetectionsCount: Int = 0,
    val isServiceRunning: Boolean = false,
    val targetMatchScore: Float = 0.0f,
    val detectedVehicles: List<DetectedVehicleBox> = emptyList(),
    val telemetry: TelemetryStats = TelemetryStats(),
    val cooldownRemainingSeconds: Int = 0,
    val isCalibrationActive: Boolean = false,
    val isSnoozedActive: Boolean = false,
    val gateStatus: String = ""
)


