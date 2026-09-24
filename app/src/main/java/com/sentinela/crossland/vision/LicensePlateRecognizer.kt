package com.sentinela.crossland.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sentinela.crossland.data.LicensePlateResult
import kotlinx.coroutines.tasks.await
import java.util.Locale
import kotlin.math.min

/**
 * Reconhecedor de Matrículas On-Device (Nível 3) baseado no Google ML Kit Text Recognition.
 *
 * Regras Estritas de Validação (Sentinel Vision):
 * 1. Procura especificamente o padrão da matrícula portuguesa do alvo: 28-VE-91.
 * 2. Tolera no máximo 1 caracter de erro de OCR (distância de Levenshtein <= 1)
 *    APENAS para o par de letras/números centrais ("VE"), nunca para a matrícula inteira.
 * 3. O prefixo "28" e o sufixo "91" são OBRIGATÓRIOS e não admitem desvios.
 * 4. Se a matrícula não for identificada ou não corresponder, NUNCA disparar o alarme.
 */
class LicensePlateRecognizer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    companion object {
        const val TARGET_PLATE_DISPLAY = "28-VE-91"
        const val TARGET_PLATE_CANONICAL = "28VE91"
        const val TARGET_CENTRAL_PAIR = "VE"

        // Regex exato para 28-VE-91 com separadores comuns (hífen, traço, ponto, espaço)
        val EXACT_REGEX = Regex("(?i)(?:^|[^0-9A-Z])2\\s*8\\s*[-–—.]?\\s*VE\\s*[-–—.]?\\s*9\\s*1(?:$|[^0-9A-Z])")

        // Regex para candidatos estruturados: Prefixo estrito 28, par central de 1 a 3 caracteres, Sufixo estrito 91
        val CANDIDATE_REGEX = Regex("(?i)(?:^|[^0-9A-Z])(2\\s*8)\\s*[-–—.]?\\s*([A-Z0-9]{1,3})\\s*[-–—.]?\\s*(9\\s*1)(?:$|[^0-9A-Z])")

        /**
         * Avalia uma string de texto procurando a matrícula alvo segundo as regras estritas.
         * Retorna LicensePlateResult se for válido ou nulo caso contrário.
         */
        fun evaluateText(rawText: String): LicensePlateResult? {
            if (rawText.isBlank()) return null

            // 1. Verificação Regex Exata (28-VE-91)
            if (EXACT_REGEX.containsMatchIn(rawText)) {
                return LicensePlateResult(
                    detectedText = rawText.trim(),
                    normalizedText = TARGET_PLATE_DISPLAY,
                    isExactTarget = true,
                    isCloseCandidate = true,
                    confidence = 1.0f,
                    rawReadSnippet = rawText.trim()
                )
            }

            // 2. Análise Estruturada por Partes (Prefixo 28, Central ~ VE, Sufixo 91)
            val match = CANDIDATE_REGEX.find(rawText)
            if (match != null) {
                val prefix = match.groupValues[1].replace("\\s".toRegex(), "")
                val central = match.groupValues[2].replace("\\s".toRegex(), "").uppercase(Locale.ROOT)
                val suffix = match.groupValues[3].replace("\\s".toRegex(), "")

                // Prefixo e Sufixo têm de ser rigorosamente "28" e "91"
                if (prefix == "28" && suffix == "91") {
                    val dist = levenshteinDistance(central, TARGET_CENTRAL_PAIR)
                    if (dist <= 1) {
                        val isExact = dist == 0
                        val confidence = if (isExact) 1.0f else 0.88f
                        return LicensePlateResult(
                            detectedText = match.value.trim(),
                            normalizedText = TARGET_PLATE_DISPLAY,
                            isExactTarget = isExact,
                            isCloseCandidate = true,
                            confidence = confidence,
                            rawReadSnippet = rawText.trim()
                        )
                    }
                }
            }

            // 3. Verificação com string compactada (ex: "28VE91" ou "28UE91" sem delimitadores)
            val sanitized = rawText.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
            if (sanitized.contains(TARGET_PLATE_CANONICAL)) {
                return LicensePlateResult(
                    detectedText = sanitized,
                    normalizedText = TARGET_PLATE_DISPLAY,
                    isExactTarget = true,
                    isCloseCandidate = true,
                    confidence = 1.0f,
                    rawReadSnippet = rawText.trim()
                )
            }

            // Janela deslizante de 6 caracteres na string limpa
            if (sanitized.length >= 6) {
                for (i in 0..(sanitized.length - 6)) {
                    val sub = sanitized.substring(i, i + 6)
                    val p = sub.substring(0, 2)
                    val c = sub.substring(2, 4)
                    val s = sub.substring(4, 6)

                    // Prefixo tem de ser 28 e sufixo tem de ser 91
                    if (p == "28" && s == "91") {
                        val dist = levenshteinDistance(c, TARGET_CENTRAL_PAIR)
                        if (dist <= 1) {
                            return LicensePlateResult(
                                detectedText = sub,
                                normalizedText = TARGET_PLATE_DISPLAY,
                                isExactTarget = (dist == 0),
                                isCloseCandidate = true,
                                confidence = if (dist == 0) 1.0f else 0.88f,
                                rawReadSnippet = rawText.trim()
                            )
                        }
                    }
                }
            }

            return null
        }

        /**
         * Distância de Levenshtein entre duas strings.
         */
        fun levenshteinDistance(s1: String, s2: String): Int {
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
    }

    /**
     * Processa um Bitmap (crop da ROI ou do veículo) com o ML Kit Text Recognition.
     */
    suspend fun recognizePlateInBitmap(bitmap: Bitmap): LicensePlateResult? {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText: Text = recognizer.process(inputImage).await()

            var latestSnippet = ""

            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val text = line.text.trim()
                    if (text.isNotEmpty() && latestSnippet.isEmpty()) {
                        latestSnippet = text
                    }
                    val match = evaluateText(text)
                    if (match != null) {
                        return match
                    }
                }
            }

            // Também avalia blocos completos caso as partes estejam em linhas adjacentes
            for (block in visionText.textBlocks) {
                val blockText = block.text.replace("\n", " ").trim()
                val match = evaluateText(blockText)
                if (match != null) {
                    return match
                }
            }

            // Se nenhum match positivo foi encontrado, retorna objeto não correspondente
            if (latestSnippet.isNotEmpty()) {
                LicensePlateResult(
                    detectedText = latestSnippet,
                    normalizedText = "",
                    isExactTarget = false,
                    isCloseCandidate = false,
                    confidence = 0f,
                    rawReadSnippet = latestSnippet
                )
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun close() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
