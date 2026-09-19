package com.sentinela.crossland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.min

class LicensePlateMatcherTest {

    private val targetCanonical = "28VE91"
    private val exactRegex = Regex("(?i)28\\s*[-–—.]?\\s*VE\\s*[-–—.]?\\s*91")

    private fun normalizePlateString(raw: String): String {
        return raw.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]"), "")
            .replace("2BVE91", targetCanonical)
            .replace("28UE91", targetCanonical)
            .replace("28VE9I", targetCanonical)
            .replace("28VE9L", targetCanonical)
    }

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

    @Test
    fun testExactPlateRegex() {
        assertTrue(exactRegex.containsMatchIn("28-VE-91"))
        assertTrue(exactRegex.containsMatchIn("28 VE 91"))
        assertTrue(exactRegex.containsMatchIn("P 28-VE-91"))
        assertTrue(exactRegex.containsMatchIn("28.VE.91"))
        assertTrue(exactRegex.containsMatchIn("28–VE–91"))
    }

    @Test
    fun testNormalizedLevenshteinCandidate() {
        val variations = listOf(
            "28-VE-91",
            "28 VE 91",
            "28VE91",
            "28-VE-9I",
            "2B-VE-91",
            "28-UE-91"
        )

        for (raw in variations) {
            val normalized = normalizePlateString(raw)
            val dist = levenshteinDistance(normalized, targetCanonical)
            assertTrue("Falha na variação $raw: dist=$dist", dist <= 1)
        }
    }
}
