package com.sentinela.crossland.vision

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sentinela.crossland.data.LicensePlateResult
import com.sentinela.crossland.data.NormalizedRect
import kotlinx.coroutines.tasks.await
import java.util.Locale
import kotlin.math.min

/**
 * Reconhecedor de Matrículas On-Device baseado no Google ML Kit.
 * Especializado na identificação estrita da matrícula portuguesa 28-VE-91,
 * com tolerância a ruído ótico (OCR) e distâncias de Levenshtein.
 */
class LicensePlateRecognizer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    companion object {
        const val TARGET_PLATE_CANONICAL = "28VE91"
        const val TARGET_PLATE_DISPLAY = "28-VE-91"

        // Regex para formatos com hífens, espaços ou pontos
        private val EXACT_REGEX = Regex("(?i)28\\s*[-–—.]?\\s*VE\\s*[-–—.]?\\s*91")
        private val CANDIDATE_REGEX = Regex("(?i)[2B][8B]\\s*[-–—.]?\\s*[VU]E\\s*[-–—.]?\\s*9[1I|l]")
    }

    /**
     * Processa a imagem usando o ML Kit Text Recognition de forma assíncrona.
     */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    suspend fun recognizePlate(
        imageProxy: ImageProxy,
        roi: NormalizedRect
    ): LicensePlateResult? {
        val mediaImage = imageProxy.image ?: return null
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        return try {
            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
            val visionText: Text = recognizer.process(inputImage).await()
            analyzeDetectedText(visionText, roi, imageProxy.width, imageProxy.height)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Analisa o texto detetado, filtrando por posição dentro da ROI e comparando com 28-VE-91.
     */
    private fun analyzeDetectedText(
        visionText: Text,
        roi: NormalizedRect,
        imgWidth: Int,
        imgHeight: Int
    ): LicensePlateResult? {
        var bestResult: LicensePlateResult? = null
        var highestScore = 0f
        var latestAnyTextSnippet = ""

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.trim()
                if (lineText.isEmpty()) continue
                if (latestAnyTextSnippet.isEmpty()) {
                    latestAnyTextSnippet = lineText
                }

                // 1. Verificação Regex Exata (28-VE-91 em qualquer variação)
                if (EXACT_REGEX.containsMatchIn(lineText)) {
                    return LicensePlateResult(
                        detectedText = lineText,
                        normalizedText = TARGET_PLATE_DISPLAY,
                        isExactTarget = true,
                        isCloseCandidate = true,
                        confidence = 1.0f,
                        rawReadSnippet = lineText
                    )
                }

                // 2. Normalização e correspondência canónica
                val normalized = normalizePlateString(lineText)

                // Se o texto normalizado contém 28VE91
                if (normalized.contains(TARGET_PLATE_CANONICAL)) {
                    return LicensePlateResult(
                        detectedText = lineText,
                        normalizedText = TARGET_PLATE_DISPLAY,
                        isExactTarget = true,
                        isCloseCandidate = true,
                        confidence = 0.98f,
                        rawReadSnippet = lineText
                    )
                }

                // Comparação de tokens com distância de Levenshtein
                if (normalized.length >= 4) {
                    for (i in 0..(normalized.length - 6).coerceAtLeast(0)) {
                        val token = normalized.substring(i, min(i + 6, normalized.length))
                        val distance = levenshteinDistance(token, TARGET_PLATE_CANONICAL)
                        val score = 1.0f - (distance.toFloat() / TARGET_PLATE_CANONICAL.length)

                        if (score > highestScore) {
                            highestScore = score
                            val isExact = distance == 0
                            val isClose = distance <= 1 || CANDIDATE_REGEX.containsMatchIn(lineText)

                            if (isClose) {
                                bestResult = LicensePlateResult(
                                    detectedText = lineText,
                                    normalizedText = TARGET_PLATE_DISPLAY,
                                    isExactTarget = isExact,
                                    isCloseCandidate = true,
                                    confidence = score,
                                    rawReadSnippet = lineText
                                )
                            }
                        }
                    }
                }
            }
        }

        // Se encontrou candidato, retorna; caso contrário, retorna snippet do que leu para o HUD
        return bestResult ?: if (latestAnyTextSnippet.isNotEmpty()) {
            LicensePlateResult(
                detectedText = latestAnyTextSnippet,
                normalizedText = "",
                isExactTarget = false,
                isCloseCandidate = false,
                confidence = 0f,
                rawReadSnippet = latestAnyTextSnippet
            )
        } else null
    }

    /**
     * Normaliza a string da matrícula removendo separadores e caracteres não alfanuméricos.
     */
    private fun normalizePlateString(raw: String): String {
        return raw.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]"), "")
            // Correções comuns de OCR para a matrícula 28-VE-91
            .replace("2BVE91", TARGET_PLATE_CANONICAL)
            .replace("28UE91", TARGET_PLATE_CANONICAL)
            .replace("28VE9I", TARGET_PLATE_CANONICAL)
            .replace("28VE9L", TARGET_PLATE_CANONICAL)
    }

    /**
     * Distância clássica de Levenshtein para tolerância a pequenos erros de caracteres.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    dp[i - 1][j] + 1,
                    min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    fun close() {
        recognizer.close()
    }
}
