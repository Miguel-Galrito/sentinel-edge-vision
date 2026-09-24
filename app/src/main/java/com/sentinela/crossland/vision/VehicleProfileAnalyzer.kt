package com.sentinela.crossland.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.sentinela.crossland.data.DetectedVehicleBox
import com.sentinela.crossland.data.NormalizedRect
import com.sentinela.crossland.data.VehicleProfileResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Analisador Biométrico Lateral de Perfil do Opel Crossland X Bicolor (1.ª geração).
 *
 * Características específicas do perfil lateral:
 * 1. Topologia Vertical em 3 Patamares (Sandwich Crossover Bicolor):
 *    - Patamar Superior (0% a 30% da altura da viatura): Tejadilho flutuante preto metalizado.
 *      Diurno: verniz preto refletor de luz do céu aberto produz forte componente azul (B - R >= +16)
 *      ou luminância significativamente inferior à carroçaria (L_corpo - L_tejadilho >= 12).
 *      Noturno: tejadilho escuro sob luz pública (L_tejadilho <= 125 e L_corpo >= 105) ou rácio L_corpo / L_tejadilho >= 1.15.
 *    - Patamar Intermédio (30% a 75% da altura): Carroçaria em cinzento/prata metalizado acromático (chroma < 46).
 *      Rejeição estrita de viaturas pretas: píxeis escuros na carroçaria não podem ultrapassar 32%.
 *    - Patamar Inferior (75% a 100% da altura): Cavas das rodas e embaladeiras inferiores em plástico preto mate (SUV Cladding).
 * 2. Validação Geométrica de Perfil:
 *    - Aspect ratio horizontal (largura / altura) típico de crossover lateral: 1.35f a 3.6f.
 *    - Envergadura horizontal da viatura de pelo menos 30% da largura da ROI.
 * 3. Rejeição Imediata de viaturas fortemente coloridas (azul, vermelho, amarelo, verde).
 */
class VehicleProfileAnalyzer {

    data class AnalysisOutcome(
        val profileResult: VehicleProfileResult,
        val vehicleBox: DetectedVehicleBox?
    )

    private data class SamplePoint(
        val x: Int,
        val y: Int,
        val r: Int,
        val g: Int,
        val b: Int,
        val lum: Double,
        val sat: Float,
        val chroma: Int,
        val bMinusR: Int
    )

    companion object {
        const val MIN_MATCH_SCORE = 0.72f
    }

    /**
     * Analisa a região de interesse (ROI) calibrada pelo utilizador procurando a assinatura biométrica do alvo.
     */
    fun analyzeRoiForOpelCrossland(
        frameBitmap: Bitmap,
        roi: NormalizedRect,
        isVehicleSemanticConfirmed: Boolean = true
    ): AnalysisOutcome {
        val emptyResult = VehicleProfileResult(
            isBicolorCandidate = false,
            upperRoofDarkScore = 0f,
            lowerBodyGreyScore = 0f,
            contrastRatio = 1f,
            overallMatchScore = 0f,
            isVehicleDetected = false
        )

        // Se a validação semântica on-device não confirmou veículo, abortar imediatamente
        if (!isVehicleSemanticConfirmed) {
            return AnalysisOutcome(emptyResult, null)
        }

        val frameW = frameBitmap.width
        val frameH = frameBitmap.height

        val left = (roi.left * frameW).toInt().coerceIn(0, frameW - 1)
        val top = (roi.top * frameH).toInt().coerceIn(0, frameH - 1)
        val right = (roi.right * frameW).toInt().coerceIn(left + 20, frameW)
        val bottom = (roi.bottom * frameH).toInt().coerceIn(top + 20, frameH)

        val roiW = right - left
        val roiH = bottom - top

        val stepX = max(2, roiW / 40)
        val stepY = max(2, roiH / 40)

        val samples = ArrayList<SamplePoint>((roiW / stepX + 1) * (roiH / stepY + 1))

        for (y in top until bottom step stepY) {
            for (x in left until right step stepX) {
                val pixel = frameBitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val lum = 0.299 * r + 0.587 * g + 0.114 * b
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val sat = if (maxC > 0) (maxC - minC).toFloat() / maxC.toFloat() else 0f
                val chroma = abs(r - g) + abs(g - b) + abs(r - b)

                samples.add(
                    SamplePoint(
                        x = x,
                        y = y,
                        r = r,
                        g = g,
                        b = b,
                        lum = lum,
                        sat = sat,
                        chroma = chroma,
                        bMinusR = b - r
                    )
                )
            }
        }

        if (samples.isEmpty()) {
            return AnalysisOutcome(emptyResult, null)
        }

        // 1. Rejeição Imediata de Viaturas Fortemente Coloridas (vermelho, azul, amarelo, verde)
        val coloredCount = samples.count { it.sat > 0.38f && it.chroma > 48 }
        val coloredRatio = coloredCount.toFloat() / samples.size
        if (coloredRatio > 0.25f) {
            return AnalysisOutcome(emptyResult, null)
        }

        // 2. Determinação de Iluminação Diurna vs Noturna (usando espectro B-R e luminância)
        val meanLum = samples.sumOf { it.lum } / samples.size
        val meanBR = samples.sumOf { it.bMinusR }.toDouble() / samples.size
        val isDaytime = (meanLum >= 95.0 && meanBR >= -2.0) || (meanBR >= 5.0)

        // 3. Varredura Adaptativa em Patamares com Janelas Deslizantes
        var bestScore = 0.0f
        var bestRoofScore = 0.0f
        var bestBodyScore = 0.0f
        var bestContrastRatio = 1.0f
        var bestWindowYTop = top
        var bestWindowYBottom = bottom
        var bestBoxLeft = left
        var bestBoxRight = right

        val windowFractions = floatArrayOf(0.35f, 0.42f, 0.50f, 0.58f, 0.68f)

        for (frac in windowFractions) {
            val winH = (roiH * frac).toInt().coerceAtLeast(25)
            val stepScan = max(3, winH / 7)

            for (yStart in top..(bottom - winH) step stepScan) {
                val yEnd = yStart + winH
                val winSamples = samples.filter { it.y in yStart until yEnd }
                if (winSamples.size < 20) continue

                // Divisão dos 3 patamares verticais da viatura:
                // 1. Tejadilho (0% a 30% da altura da janela)
                // 2. Carroçaria (30% a 75% da altura da janela)
                // 3. Embaladeiras / Base (75% a 100% da altura da janela)
                val yRoofSplit = yStart + (winH * 0.30f).toInt()
                val yBaseSplit = yStart + (winH * 0.75f).toInt()

                val roofSamples = winSamples.filter { it.y < yRoofSplit }
                val bodySamples = winSamples.filter { it.y in yRoofSplit until yBaseSplit }
                val baseSamples = winSamples.filter { it.y >= yBaseSplit }

                if (roofSamples.size < 5 || bodySamples.size < 10) continue

                // Análise do Patamar Intermédio: Pintura Prata Metálica
                val silverBodySamples: List<SamplePoint>
                val darkBodySamples: List<SamplePoint>

                if (isDaytime) {
                    silverBodySamples = bodySamples.filter { it.lum in 105.0..185.0 && it.chroma < 46 }
                    darkBodySamples = bodySamples.filter { it.lum < 95.0 }
                } else {
                    silverBodySamples = bodySamples.filter { it.lum in 75.0..170.0 && (it.chroma < 48 || it.sat < 0.28f) }
                    darkBodySamples = bodySamples.filter { it.lum < 65.0 }
                }

                val silverRatio = silverBodySamples.size.toFloat() / bodySamples.size
                val darkRatio = darkBodySamples.size.toFloat() / bodySamples.size

                // O corpo do veículo tem de ser predominantemente prata metálico (>= 30%)
                // e rejeita viaturas pretas/escuras (onde > 32% dos píxeis da carroçaria são pretos)
                if (silverRatio < 0.30f || darkRatio > 0.32f) continue

                val avgBodyLum = bodySamples.sumOf { it.lum } / bodySamples.size
                val avgRoofLum = roofSamples.sumOf { it.lum } / roofSamples.size
                val avgRoofBR = roofSamples.sumOf { it.bMinusR }.toDouble() / roofSamples.size
                val avgBodyBR = bodySamples.sumOf { it.bMinusR }.toDouble() / bodySamples.size
                val avgBaseLum = if (baseSamples.isNotEmpty()) {
                    baseSamples.sumOf { it.lum } / baseSamples.size
                } else {
                    avgBodyLum
                }

                val contrast = (avgBodyLum / max(1.0, avgRoofLum)).toFloat()

                // Assinatura do Tejadilho Preto Bicolor
                var roofScore = 0.0f
                if (isDaytime) {
                    val hasSkyReflection = (avgRoofBR >= 16.0) && (avgRoofBR > avgBodyBR + 4.0)
                    val isRoofDarker = avgBodyLum - avgRoofLum >= 12.0

                    if (hasSkyReflection) {
                        roofScore = (avgRoofBR.toFloat() / 28f).coerceIn(0f, 1f)
                    } else if (isRoofDarker) {
                        roofScore = ((avgBodyLum - avgRoofLum).toFloat() / 25f).coerceIn(0f, 1f)
                    }
                } else {
                    val isDarkRoof = (avgRoofLum <= 125.0) && (avgBodyLum >= 105.0)
                    val isContrastOk = contrast >= 1.15f

                    if (isDarkRoof) {
                        roofScore = 0.85f
                    } else if (isContrastOk) {
                        roofScore = ((contrast - 1.12f) / 0.25f).coerceIn(0f, 1f)
                    }
                }

                if (roofScore <= 0.0f) continue

                // Assinatura das Embaladeiras em Plástico Preto Mate (SUV Cladding)
                var baseScore = 0.80f
                if (baseSamples.isNotEmpty() && avgBaseLum < avgBodyLum) {
                    baseScore = (0.80f + ((avgBodyLum - avgBaseLum).toFloat() / 40f)).coerceIn(0f, 1f)
                } else if (baseSamples.isNotEmpty() && avgBaseLum > avgBodyLum + 12.0) {
                    baseScore = 0.25f // Penaliza se as embaladeiras forem muito mais claras que a carroçaria
                }

                // Verificação Geométrica: Aspect Ratio e Envergadura Horizontal
                val silverXs = silverBodySamples.map { it.x }
                if (silverXs.isEmpty()) continue
                val minX = silverXs.minOrNull() ?: left
                val maxX = silverXs.maxOrNull() ?: right
                val boxW = maxX - minX
                val boxH = winH

                // Altura mínima da viatura na imagem da câmara (elimina artefactos minúsculos distantes)
                if (boxH < 35) continue

                val aspectRatio = boxW.toFloat() / boxH.toFloat()

                // Um perfil lateral de crossover/SUV na via pública tem proporções 1.35 a 3.6
                if (aspectRatio !in 1.35f..3.6f) continue

                // A viatura deve ocupar pelo menos 30% da largura da ROI
                if (boxW < roiW * 0.30f) continue

                val bodyScore = (silverRatio / 0.48f).coerceIn(0f, 1f)
                val combinedScore = 0.45f * bodyScore + 0.40f * roofScore + 0.15f * baseScore

                if (combinedScore > bestScore) {
                    bestScore = combinedScore
                    bestRoofScore = roofScore
                    bestBodyScore = bodyScore
                    bestContrastRatio = contrast
                    bestWindowYTop = yStart
                    bestWindowYBottom = yEnd
                    bestBoxLeft = minX
                    bestBoxRight = maxX
                }
            }
        }

        val isCandidate = bestScore >= MIN_MATCH_SCORE

        var targetBox: DetectedVehicleBox? = null
        if (isCandidate) {
            val boxL = (bestBoxLeft.toFloat() / frameW).coerceIn(0f, 1f)
            val boxT = (bestWindowYTop.toFloat() / frameH).coerceIn(0f, 1f)
            val boxR = (bestBoxRight.toFloat() / frameW).coerceIn(boxL, 1f)
            val boxB = (bestWindowYBottom.toFloat() / frameH).coerceIn(boxT, 1f)

            targetBox = DetectedVehicleBox(
                left = boxL,
                top = boxT,
                right = boxR,
                bottom = boxB,
                isTargetOpel = true,
                matchScore = bestScore,
                label = "OPEL CROSSLAND X [${(bestScore * 100).toInt()}%]"
            )
        }

        val profileResult = VehicleProfileResult(
            isBicolorCandidate = isCandidate,
            upperRoofDarkScore = bestRoofScore,
            lowerBodyGreyScore = bestBodyScore,
            contrastRatio = bestContrastRatio,
            overallMatchScore = if (isCandidate) bestScore else 0f,
            isVehicleDetected = isCandidate
        )

        return AnalysisOutcome(profileResult, targetBox)
    }
}
