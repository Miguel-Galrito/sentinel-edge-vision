package com.sentinela.crossland

import com.sentinela.crossland.vision.LicensePlateRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LicensePlateMatcherTest {

    @Test
    fun testExactPlateFormats() {
        val validExacts = listOf(
            "28-VE-91",
            "28 VE 91",
            "28VE91",
            "28.VE.91",
            "28–VE–91",
            "P 28-VE-91",
            "CARRO: 28-VE-91 (PT)"
        )

        for (input in validExacts) {
            val result = LicensePlateRecognizer.evaluateText(input)
            assertNotNull("Deveria reconhecer como exato: $input", result)
            assertTrue("Deveria ser isExactTarget: $input", result!!.isExactTarget)
            assertEquals("28-VE-91", result.normalizedText)
        }
    }

    @Test
    fun testCentralPairSingleCharacterErrorTolerated() {
        // Levenshtein <= 1 APENAS para o par central "VE"
        val toleratedCentral = listOf(
            "28-UE-91", // V -> U
            "28-VF-91", // E -> F
            "28-TE-91", // V -> T
            "28-BE-91", // V -> B
            "28-V-91",  // E em falta (deleção)
            "28-VIE-91" // I inserido
        )

        for (input in toleratedCentral) {
            val result = LicensePlateRecognizer.evaluateText(input)
            assertNotNull("Deveria aceitar erro de 1 char no par central: $input", result)
            assertTrue("Deveria ser isCloseCandidate: $input", result!!.isCloseCandidate)
            assertEquals("28-VE-91", result.normalizedText)
        }
    }

    @Test
    fun testRejectInvalidPrefixOrSuffixStrictly() {
        // O prefixo tem de ser estritamente 28 e o sufixo 91
        val strictlyRejected = listOf(
            "35-VE-91", // Prefixo errado
            "28-VE-84", // Sufixo errado
            "12-VE-91", // Prefixo errado
            "28-VE-11", // Sufixo errado
            "AA-BB-CC", // Completamente diferente
            "12-34-56", // Numérica qualquer
            "OPEL CORSA",
            "LAPTOP LENOVO",
            "28-AB-91", // 2 erros no par central ("AB" vs "VE")
            "91-VE-28"  // Invertido
        )

        for (input in strictlyRejected) {
            val result = LicensePlateRecognizer.evaluateText(input)
            assertNull("Deveria REJEITAR categoricamente: $input", result)
        }
    }
}
