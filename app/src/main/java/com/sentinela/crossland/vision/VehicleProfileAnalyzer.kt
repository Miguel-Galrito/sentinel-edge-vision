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
        var roofDarkPixelCount = 0
        var roofNeutralColorCount = 0

        var bodyPixelCount = 0
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

                    // Preto: Luminância baixa (< 80)
                    if (yVal < 80) {
                        roofDarkPixelCount++
                    }

                    // Neutro (baixa saturação cromática: U e V próximos de 128)
                    val chromaDist = abs(uVal - 128) + abs(vVal - 128)
                    if (chromaDist < 35) {
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

                    // Cinzento claro: Luminância média-alta (entre 100 e 215)
                    if (yVal in 100..215) {
                        bodyGreyPixelCount++
                    }

                    // Cor neutra (sem tons saturados)
                    val chromaDist = abs(uVal - 128) + abs(vVal - 128)
                    if (chromaDist < 35) {
                        bodyNeutralColorCount++
                    }
                }
            }
        }

        val roofDarkRatio = if (roofPixelCount > 0) roofDarkPixelCount.toFloat() / roofPixelCount else 0f
        val roofNeutralRatio = if (roofPixelCount > 0) roofNeutralColorCount.toFloat() / roofPixelCount else 0f

        val bodyGreyRatio = if (bodyPixelCount > 0) bodyGreyPixelCount.toFloat() / bodyPixelCount else 0f
        val bodyNeutralRatio = if (bodyPixelCount > 0) bodyNeutralColorCount.toFloat() / bodyPixelCount else 0f

        val upperRoofScore = (roofDarkRatio * 0.7f + roofNeutralRatio * 0.3f)
        val lowerBodyScore = (bodyGreyRatio * 0.7f + bodyNeutralRatio * 0.3f)

        // Contraste característico: Zona superior significativamente mais escura que zona inferior
        val contrastDelta = (bodyGreyRatio - (1f - roofDarkRatio)).coerceAtLeast(0f)

        val overallScore = ((upperRoofScore * 0.45f) + (lowerBodyScore * 0.45f) + (contrastDelta * 0.1f)).coerceIn(0f, 1f)

        // Candidato válido se tejadilho escuro > 35% e corpo cinzento > 35%
        val isCandidate = roofDarkRatio >= 0.35f && bodyGreyRatio >= 0.35f && overallScore >= 0.45f

        return VehicleProfileResult(
            isBicolorCandidate = isCandidate,
            upperRoofDarkScore = upperRoofScore,
            lowerBodyGreyScore = lowerBodyScore,
            overallMatchScore = overallScore
        )
    }
}
