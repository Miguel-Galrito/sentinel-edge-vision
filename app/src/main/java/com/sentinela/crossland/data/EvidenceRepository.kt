package com.sentinela.crossland.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Repositório persistente para registo e auditoria forense de deteções.
 * Armazena as evidências em JSON no armazenamento local seguro da app.
 */
class EvidenceRepository private constructor(private val context: Context) {

    private val lock = ReentrantLock()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val evidenceFile = File(context.filesDir, "evidence_log.json")

    private val _evidences = MutableStateFlow<List<DetectionEvidence>>(emptyList())
    val evidences: StateFlow<List<DetectionEvidence>> = _evidences.asStateFlow()

    init {
        loadEvidencesFromFile()
    }

    private fun loadEvidencesFromFile() {
        lock.withLock {
            try {
                if (!evidenceFile.exists()) {
                    _evidences.value = emptyList()
                    return
                }

                val jsonStr = evidenceFile.readText()
                if (jsonStr.isBlank()) {
                    _evidences.value = emptyList()
                    return
                }

                val jsonArray = JSONArray(jsonStr)
                val list = mutableListOf<DetectionEvidence>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val statusStr = obj.optString("status", EvidenceStatus.UNREVIEWED.name)
                    val status = try {
                        EvidenceStatus.valueOf(statusStr)
                    } catch (e: Exception) {
                        EvidenceStatus.UNREVIEWED
                    }

                    list.add(
                        DetectionEvidence(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            snapshotFilePath = obj.optString("snapshotFilePath").takeIf { it.isNotEmpty() },
                            detectedPlate = obj.optString("detectedPlate", "28-VE-91"),
                            confidence = obj.optDouble("confidence", 0.0).toFloat(),
                            profileScore = obj.optDouble("profileScore", 0.0).toFloat(),
                            status = status,
                            notes = obj.optString("notes", "")
                        )
                    )
                }

                // Ordenar do mais recente para o mais antigo
                _evidences.value = list.sortedByDescending { it.timestamp }
            } catch (e: Exception) {
                e.printStackTrace()
                _evidences.value = emptyList()
            }
        }
    }

    private fun persistToFile() {
        scope.launch {
            lock.withLock {
                try {
                    val jsonArray = JSONArray()
                    _evidences.value.forEach { item ->
                        val obj = JSONObject().apply {
                            put("id", item.id)
                            put("timestamp", item.timestamp)
                            put("snapshotFilePath", item.snapshotFilePath ?: "")
                            put("detectedPlate", item.detectedPlate)
                            put("confidence", item.confidence.toDouble())
                            put("profileScore", item.profileScore.toDouble())
                            put("status", item.status.name)
                            put("notes", item.notes)
                        }
                        jsonArray.put(obj)
                    }
                    evidenceFile.writeText(jsonArray.toString(2))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun addEvidence(evidence: DetectionEvidence) {
        lock.withLock {
            val updated = listOf(evidence) + _evidences.value
            _evidences.value = updated
        }
        persistToFile()
    }

    fun updateStatus(evidenceId: String, newStatus: EvidenceStatus) {
        lock.withLock {
            val updated = _evidences.value.map { item ->
                if (item.id == evidenceId) {
                    item.copy(status = newStatus)
                } else {
                    item
                }
            }
            _evidences.value = updated
        }
        persistToFile()
    }

    fun deleteEvidence(evidenceId: String) {
        lock.withLock {
            val itemToDelete = _evidences.value.find { it.id == evidenceId }
            // Apaga snapshot do disco se existir
            itemToDelete?.snapshotFilePath?.let { path ->
                try {
                    val file = File(path)
                    if (file.exists()) file.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            _evidences.value = _evidences.value.filter { it.id != evidenceId }
        }
        persistToFile()
    }

    fun clearAll() {
        lock.withLock {
            _evidences.value.forEach { item ->
                item.snapshotFilePath?.let { path ->
                    try {
                        val file = File(path)
                        if (file.exists()) file.delete()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            _evidences.value = emptyList()
        }
        persistToFile()
    }

    companion object {
        @Volatile
        private var instance: EvidenceRepository? = null

        fun getInstance(context: Context): EvidenceRepository {
            return instance ?: synchronized(this) {
                instance ?: EvidenceRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
