package com.sentinela.crossland.vision

import androidx.camera.core.ImageProxy
import com.sentinela.crossland.data.NormalizedRect
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Detetor de Movimento de Baixo Consumo (Nível 1).
 * Opera diretamente no canal de luminância (Plano Y) do ImageProxy sem conversões para Bitmap.
 * Utiliza amostragem em grelha pré-alocada para zero pressão no Garbage Collector.
 */
class MotionDetector(
    private val gridCols: Int = 24,
    private val gridRows: Int = 16
) {
    // Buffers pré-alocados para reutilização contínua
    private val totalCells = gridCols * gridRows
    private val currentGrid = IntArray(totalCells)
    private val previousGrid = IntArray(totalCells)
    private var hasPreviousFrame = false

    // Throttling de frames: Limita a análise a ~4 FPS (intervalo mínimo de 250ms)
    private var lastAnalysisTimestamp: Long = 0L
    private val minFrameIntervalMs: Long = 250L

    data class MotionResult(
        val isMotionDetected: Boolean,
        val changedCellsRatio: Float,
        val frameSkipped: Boolean
    )

    /**
     * Analisa o frame e determina se houve movimento significativo dentro da ROI.
     */
    fun processFrame(
        image: ImageProxy,
        roi: NormalizedRect,
        sensitivityThreshold: Float = 25f
    ): MotionResult {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnalysisTimestamp < minFrameIntervalMs) {
            return MotionResult(isMotionDetected = false, changedCellsRatio = 0f, frameSkipped = true)
        }
        lastAnalysisTimestamp = currentTime

        val planes = image.planes
        if (planes.isEmpty()) {
            return MotionResult(isMotionDetected = false, changedCellsRatio = 0f, frameSkipped = false)
        }

        val yBuffer: ByteBuffer = planes[0].buffer
        val rowStride = planes[0].rowStride
        val pixelStride = planes[0].pixelStride
        val imageWidth = image.width
        val imageHeight = image.height

        // Coordenadas absolutas da ROI
        val roiLeft = (roi.left * imageWidth).toInt().coerceIn(0, imageWidth - 1)
        val roiTop = (roi.top * imageHeight).toInt().coerceIn(0, imageHeight - 1)
        val roiRight = (roi.right * imageWidth).toInt().coerceIn(roiLeft + 1, imageWidth)
        val roiBottom = (roi.bottom * imageHeight).toInt().coerceIn(roiTop + 1, imageHeight)

        val roiWidth = roiRight - roiLeft
        val roiHeight = roiBottom - roiTop

        val cellWidth = (roiWidth / gridCols).coerceAtLeast(1)
        val cellHeight = (roiHeight / gridRows).coerceAtLeast(1)

        // Calcula a média de luminância de cada célula da grelha dentro da ROI
        var cellIndex = 0
        for (r in 0 until gridRows) {
            val cellStartY = roiTop + r * cellHeight
            for (c in 0 until gridCols) {
                val cellStartX = roiLeft + c * cellWidth

                // Amostra o ponto central de cada célula (ultra-rápido)
                val sampleX = (cellStartX + cellWidth / 2).coerceIn(0, imageWidth - 1)
                val sampleY = (cellStartY + cellHeight / 2).coerceIn(0, imageHeight - 1)

                val bufferIndex = sampleY * rowStride + sampleX * pixelStride
                val luminance = if (bufferIndex < yBuffer.remaining()) {
                    yBuffer.get(bufferIndex).toInt() and 0xFF
                } else {
                    0
                }

                currentGrid[cellIndex] = luminance
                cellIndex++
            }
        }

        if (!hasPreviousFrame) {
            System.arraycopy(currentGrid, 0, previousGrid, 0, totalCells)
            hasPreviousFrame = true
            return MotionResult(isMotionDetected = false, changedCellsRatio = 0f, frameSkipped = false)
        }

        // Compara com a grelha do frame anterior
        var changedCellsCount = 0
        val thresholdInt = sensitivityThreshold.toInt().coerceIn(10, 60)

        for (i in 0 until totalCells) {
            val diff = abs(currentGrid[i] - previousGrid[i])
            if (diff > thresholdInt) {
                changedCellsCount++
            }
        }

        val changedRatio = changedCellsCount.toFloat() / totalCells.toFloat()

        // Atualiza a grelha anterior
        System.arraycopy(currentGrid, 0, previousGrid, 0, totalCells)

        // Critério de movimento de viatura: entre 6% e 75% da ROI alterada
        // (evita disparo por insetos ou ruído de sensor < 6%, e ignora mudanças abruptas globais de luz > 75%)
        val isVehicleMotion = changedRatio in 0.06f..0.75f

        return MotionResult(
            isMotionDetected = isVehicleMotion,
            changedCellsRatio = changedRatio,
            frameSkipped = false
        )
    }

    fun reset() {
        hasPreviousFrame = false
        lastAnalysisTimestamp = 0L
    }
}
