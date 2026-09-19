package com.sentinela.crossland.vision

import androidx.camera.core.ImageProxy
import com.sentinela.crossland.data.NormalizedRect
import com.sentinela.crossland.data.VehicleProfileResult
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Analisador de Perfil Visual do Opel Crossland X (1.ª geração).
 * Foco na pintura Bicolor (Tejadilho, pilares e espelhos pretos + Carroçaria em cinzento claro)
 * visto de um ângulo elevado/janela.
 */
class VehicleProfileAnalyzer {

    /**
     * Analisa o padrão de contraste e luminância vertical dentro da ROI.
     * Opel Crossland X Bicolor apresenta:
     * - Zona Superior (Tejadilho + Pilares): Luminância significativamente baixa (preto/escuro).
     * - Zona Média/Inferior (Portas/Painéis): Luminância média-alta (cinzento claro metálico).
     * - Saturação cromática baixa (neutra) em ambas as zonas (sem tons fortes de vermelho, azul ou amarelo).
     */
    fun analyzeBicolorProfile(
        image: ImageProxy,
        roi: NormalizedRect
    ): VehicleProfileResult {
        val planes = image.planes
        if (planes.size < 3) {
            return VehicleProfileResult(
                isBicolorCandidate = false,
                upperRoofDarkScore = 0f,
                lowerBodyGreyScore = 0f,
                overallMatchScore = 0f
            )
        }

        val yBuffer: ByteBuffer = planes[0].buffer
        val uBuffer: ByteBuffer = planes[1].buffer
        val vBuffer: ByteBuffer = planes[2].buffer

        val rowStrideY = planes[0].rowStride
        val pixelStrideY = planes[0].pixelStride
        val rowStrideU = planes[1].rowStride
        val pixelStrideU = planes[1].pixelStride

        val imageWidth = image.width
        val imageHeight = image.height

        val roiLeft = (roi.left * imageWidth).toInt().coerceIn(0, imageWidth - 1)
        val roiTop = (roi.top * imageHeight).toInt().coerceIn(0, imageHeight - 1)
        val roiRight = (roi.right * imageWidth).toInt().coerceIn(roiLeft + 1, imageWidth)
        val roiBottom = (roi.bottom * imageHeight).toInt().coerceIn(roiTop + 1, imageHeight)

        val roiHeight = roiBottom - roiTop
        val roiWidth = roiRight - roiLeft

        if (roiWidth < 20 || roiHeight < 20) {
            return VehicleProfileResult(false, 0f, 0f, 0f)
        }

        // Divide a ROI em Zona Superior (Tejadilho: top 0% a 35%) e Zona Inferior (Carroçaria: 40% a 85%)
        val roofBottom = roiTop + (roiHeight * 0.35f).toInt()
        val bodyTop = roiTop + (roiHeight * 0.40f).toInt()
        val bodyBottom = roiTop + (roiHeight * 0.85f).toInt()

        var roofPixelCount = 0
        var roofTotalY = 0L
        var roofDarkPixelCount = 0
        var roofNeutralColorCount = 0

        var bodyPixelCount = 0
        var bodyTotalY = 0L
        var bodyGreyPixelCount = 0
        var bodyNeutralColorCount = 0

        // Amostragem de pontos com passo para desempenho em tempo real
        val stepX = (roiWidth / 30).coerceAtLeast(2)
        val stepY = (roiHeight / 30).coerceAtLeast(2)

        // 1. Amostra Zona Superior (Tejadilho / Pilares Pretos)
        for (y in roiTop until roofBottom step stepY) {
            val yRow = y * rowStrideY
            val uvRow = (y / 2) * rowStrideU
            for (x in roiLeft until roiRight step stepX) {
                val yIndex = yRow + x * pixelStrideY
                val uvIndex = uvRow + (x / 2) * pixelStrideU

                if (yIndex < yBuffer.remaining() && uvIndex < uBuffer.remaining() && uvIndex < vBuffer.remaining()) {
                    val yVal = yBuffer.get(yIndex).toInt() and 0xFF
                    val uVal = uBuffer.get(uvIndex).toInt() and 0xFF
                    val vVal = vBuffer.get(uvIndex).toInt() and 0xFF

                    roofPixelCount++
                    roofTotalY += yVal

                    if (yVal < 90) {
                        roofDarkPixelCount++
                    }

                    // Neutro (baixa saturação cromática: U e V próximos de 128)
                    val chromaDist = abs(uVal - 128) + abs(vVal - 128)
                    if (chromaDist < 40) {
                        roofNeutralColorCount++
                    }
                }
            }
        }

        // 2. Amostra Zona Inferior (Carroçaria Cinzento Claro)
        for (y in bodyTop until bodyBottom step stepY) {
            val yRow = y * rowStrideY
            val uvRow = (y / 2) * rowStrideU
            for (x in roiLeft until roiRight step stepX) {
                val yIndex = yRow + x * pixelStrideY
                val uvIndex = uvRow + (x / 2) * pixelStrideU

                if (yIndex < yBuffer.remaining() && uvIndex < uBuffer.remaining() && uvIndex < vBuffer.remaining()) {
                    val yVal = yBuffer.get(yIndex).toInt() and 0xFF
                    val uVal = uBuffer.get(uvIndex).toInt() and 0xFF
                    val vVal = vBuffer.get(uvIndex).toInt() and 0xFF

                    bodyPixelCount++
                    bodyTotalY += yVal

                    // Cinzento claro (de dia: > 90; de noite: basta ser mais claro que o tejadilho)
                    if (yVal in 70..230) {
                        bodyGreyPixelCount++
                    }

                    val chromaDist = abs(uVal - 128) + abs(vVal - 128)
                    if (chromaDist < 40) {
                        bodyNeutralColorCount++
                    }
                }
            }
        }

        val roofAvgY = if (roofPixelCount > 0) roofTotalY.toFloat() / roofPixelCount else 0f
        val bodyAvgY = if (bodyPixelCount > 0) bodyTotalY.toFloat() / bodyPixelCount else 0f

        val roofDarkRatio = if (roofPixelCount > 0) roofDarkPixelCount.toFloat() / roofPixelCount else 0f
        val roofNeutralRatio = if (roofPixelCount > 0) roofNeutralColorCount.toFloat() / roofPixelCount else 0f

        val bodyGreyRatio = if (bodyPixelCount > 0) bodyGreyPixelCount.toFloat() / bodyPixelCount else 0f
        val bodyNeutralRatio = if (bodyPixelCount > 0) bodyNeutralColorCount.toFloat() / bodyPixelCount else 0f

        val isNightScene = bodyAvgY < 80f

        // Em cena noturna: analisa contraste relativo (corpo reflete mais luz pública que o tejadilho preto)
        val relativeContrastValid = if (isNightScene) {
            (bodyAvgY > roofAvgY * 1.12f) || (bodyAvgY - roofAvgY > 6f)
        } else {
            (bodyAvgY > roofAvgY * 1.25f) || (bodyAvgY - roofAvgY > 15f)
        }

        val upperRoofScore = (roofDarkRatio * 0.6f + roofNeutralRatio * 0.4f).coerceIn(0f, 1f)
        val lowerBodyScore = (bodyGreyRatio * 0.6f + bodyNeutralRatio * 0.4f).coerceIn(0f, 1f)

        val contrastScore = if (relativeContrastValid) 0.85f else 0.2f
        val overallScore = ((upperRoofScore * 0.35f) + (lowerBodyScore * 0.35f) + (contrastScore * 0.3f)).coerceIn(0f, 1f)

        // Candidato válido se contraste relativo for respeitado e pontuação composta for suficiente
        val isCandidate = relativeContrastValid && overallScore >= 0.42f

        return VehicleProfileResult(
            isBicolorCandidate = isCandidate,
            upperRoofDarkScore = upperRoofScore,
            lowerBodyGreyScore = lowerBodyScore,
            overallMatchScore = overallScore
        )
    }
}
