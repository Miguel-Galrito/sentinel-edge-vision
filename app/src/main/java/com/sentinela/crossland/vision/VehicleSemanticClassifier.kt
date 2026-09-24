package com.sentinela.crossland.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.tasks.await

/**
 * Classificador Semântico On-Device (Nível 2) baseado no Google ML Kit Image Labeling.
 *
 * Regras Estritas de Validação (Sentinel Vision):
 * 1. O objeto detetado TEM de ser categorizado especificamente como veículo/carro com confiança > 80% (> 0.80f).
 * 2. Se for identificado como eletrónico (portáteis, ecrãs), mobília (mesas, camas), pessoa ou indeterminado:
 *    ABORTAR IMEDIATAMENTE o pipeline.
 * 3. Falhas ou exceções abortam imediatamente (sem fallbacks permissivos).
 */
class VehicleSemanticClassifier {

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.30f)
            .build()
    )

    data class ClassificationResult(
        val isVehicle: Boolean,
        val topLabel: String,
        val confidence: Float,
        val rejectionReason: String? = null
    )

    companion object {
        const val MIN_VEHICLE_CONFIDENCE = 0.80f

        private val vehicleKeywords = setOf(
            "Car", "Vehicle", "Motor vehicle", "Automobile",
            "Van", "Truck", "Land vehicle"
        )

        private val electronicKeywords = setOf(
            "Laptop", "Computer", "Computer keyboard", "Electronics",
            "Electronic device", "Screen", "Display", "Television",
            "Mobile phone", "Personal computer", "Desktop computer",
            "Computer hardware", "Gadget", "Office equipment"
        )

        private val furnitureAndIndoorKeywords = setOf(
            "Furniture", "Table", "Desk", "Chair", "Bed", "Room",
            "Living room", "Bedroom", "Floor", "Flooring", "Ceiling",
            "Interior design", "Houseplant", "Couch", "Sofa",
            "Luggage and bags", "Suitcase", "Textile"
        )

        private val personKeywords = setOf(
            "Person", "Human", "Face", "Man", "Woman", "People"
        )
    }

    suspend fun classify(bitmap: Bitmap): ClassificationResult {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val labels: List<ImageLabel> = labeler.process(inputImage).await()

            if (labels.isEmpty()) {
                return ClassificationResult(
                    isVehicle = false,
                    topLabel = "Nenhum objeto",
                    confidence = 0f,
                    rejectionReason = "Nenhum objeto classificado"
                )
            }

            var bestVehicleConf = 0f
            var bestVehicleLabel = ""

            // 1. Deteção de objetos não-veículo prioritários (eletrónicos, mobília, pessoas)
            for (l in labels) {
                val labelText = l.text
                val conf = l.confidence

                // Se for eletrónico (computador, portátil, ecrã)
                if (conf >= 0.50f && electronicKeywords.any { labelText.contains(it, ignoreCase = true) }) {
                    return ClassificationResult(
                        isVehicle = false,
                        topLabel = labelText,
                        confidence = conf,
                        rejectionReason = "Eletrónico detetado ($labelText ${(conf * 100).toInt()}%) - ABORTADO"
                    )
                }

                // Se for pessoa / humano
                if (conf >= 0.50f && personKeywords.any { labelText.contains(it, ignoreCase = true) }) {
                    return ClassificationResult(
                        isVehicle = false,
                        topLabel = labelText,
                        confidence = conf,
                        rejectionReason = "Pessoa detetada ($labelText ${(conf * 100).toInt()}%) - ABORTADO"
                    )
                }

                // Se for mobília / interior
                if (conf >= 0.50f && furnitureAndIndoorKeywords.any { labelText.contains(it, ignoreCase = true) }) {
                    return ClassificationResult(
                        isVehicle = false,
                        topLabel = labelText,
                        confidence = conf,
                        rejectionReason = "Mobília/Interior detetado ($labelText ${(conf * 100).toInt()}%) - ABORTADO"
                    )
                }

                // Verifica se é veículo
                if (vehicleKeywords.any { labelText.contains(it, ignoreCase = true) }) {
                    if (conf > bestVehicleConf) {
                        bestVehicleConf = conf
                        bestVehicleLabel = labelText
                    }
                }
            }

            // 2. Validação Estrita de Veículo com Confiança > 80%
            if (bestVehicleConf > MIN_VEHICLE_CONFIDENCE) {
                ClassificationResult(
                    isVehicle = true,
                    topLabel = bestVehicleLabel,
                    confidence = bestVehicleConf,
                    rejectionReason = null
                )
            } else {
                val topLabel = if (bestVehicleConf > 0f) bestVehicleLabel else labels[0].text
                val topConf = if (bestVehicleConf > 0f) bestVehicleConf else labels[0].confidence
                ClassificationResult(
                    isVehicle = false,
                    topLabel = topLabel,
                    confidence = topConf,
                    rejectionReason = if (bestVehicleConf > 0f) {
                        "Confiança insuficiente ($topLabel ${(bestVehicleConf * 100).toInt()}% <= 80%)"
                    } else {
                        "Objeto não é veículo ($topLabel)"
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Em caso de erro, ABORTAR imediatamente - nunca permitir falso positivo por falha
            ClassificationResult(
                isVehicle = false,
                topLabel = "Erro",
                confidence = 0f,
                rejectionReason = "Erro na inferência do classificador"
            )
        }
    }

    fun close() {
        try {
            labeler.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
