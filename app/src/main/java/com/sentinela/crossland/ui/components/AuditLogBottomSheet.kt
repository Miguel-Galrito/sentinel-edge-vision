package com.sentinela.crossland.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.sentinela.crossland.data.DetectionEvidence
import com.sentinela.crossland.data.EvidenceRepository
import com.sentinela.crossland.data.EvidenceStatus
import com.sentinela.crossland.ui.theme.CyberAmber
import com.sentinela.crossland.ui.theme.CyberCyan
import com.sentinela.crossland.ui.theme.CyberEmerald
import com.sentinela.crossland.ui.theme.CyberObsidian
import com.sentinela.crossland.ui.theme.TextMuted
import com.sentinela.crossland.ui.theme.TextPrimary
import com.sentinela.crossland.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BottomSheet de Histórico e Auditoria de Evidências Capturadas.
 * Permite triagem de falsos positivos, inspeção visual de snapshots e exportação forense.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditLogBottomSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repository = EvidenceRepository.getInstance(context)
    val evidences by repository.evidences.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CyberObsidian,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(20.dp)
        ) {
            // Header do Log de Evidências
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "HISTÓRICO DE EVIDÊNCIAS",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "${evidences.size} eventos arquivados com snapshot",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (evidences.isNotEmpty()) {
                    IconButton(
                        onClick = { repository.clearAll() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Limpar Tudo",
                            tint = Color(0xFFFF5252)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (evidences.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Nenhuma evidência capturada",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Quando o Opel Crossland X passar na via, o snapshot e telemetria serão arquivados aqui.",
                            color = TextMuted,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(evidences, key = { it.id }) { evidence ->
                        EvidenceItemCard(
                            evidence = evidence,
                            onUpdateStatus = { newStatus ->
                                repository.updateStatus(evidence.id, newStatus)
                            },
                            onDelete = {
                                repository.deleteEvidence(evidence.id)
                            },
                            onShare = {
                                shareEvidenceSnapshot(context, evidence)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E222D),
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = "FECHAR REGISTO",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun EvidenceItemCard(
    evidence: DetectionEvidence,
    onUpdateStatus: (EvidenceStatus) -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    val formattedTime = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        .format(Date(evidence.timestamp))

    val statusBadgeColor = when (evidence.status) {
        EvidenceStatus.CONFIRMED -> CyberEmerald
        EvidenceStatus.FALSE_POSITIVE -> Color(0xFFFF5252)
        EvidenceStatus.UNREVIEWED -> CyberAmber
    }

    val statusLabel = when (evidence.status) {
        EvidenceStatus.CONFIRMED -> "CONFIRMADO"
        EvidenceStatus.FALSE_POSITIVE -> "FALSO POSITIVO"
        EvidenceStatus.UNREVIEWED -> "NÃO REVISADO"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x66161922))
            .border(1.dp, statusBadgeColor.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Snapshot Thumbnail
                val snapshotPath = evidence.snapshotFilePath
                if (snapshotPath != null && File(snapshotPath).exists()) {
                    AsyncImage(
                        model = File(snapshotPath),
                        contentDescription = "Snapshot",
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x33FFFFFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "SEM FOTO", color = TextMuted, fontSize = 9.sp)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = evidence.detectedPlate,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )

                        // Status Tag
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(statusBadgeColor.copy(alpha = 0.2f))
                                .border(1.dp, statusBadgeColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = statusLabel,
                                color = statusBadgeColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = formattedTime,
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "Confiança: ${(evidence.confidence * 100).toInt()}% | Bicolor: ${(evidence.profileScore * 100).toInt()}%",
                        color = CyberCyan,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Barra de Triagem e Ações
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Ação: Confirmar Deteção
                IconButton(
                    onClick = { onUpdateStatus(EvidenceStatus.CONFIRMED) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Confirmar",
                        tint = if (evidence.status == EvidenceStatus.CONFIRMED) CyberEmerald else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Ação: Marcar como Falso Positivo
                IconButton(
                    onClick = { onUpdateStatus(EvidenceStatus.FALSE_POSITIVE) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.HighlightOff,
                        contentDescription = "Falso Positivo",
                        tint = if (evidence.status == EvidenceStatus.FALSE_POSITIVE) Color(0xFFFF5252) else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Ação: Partilhar Foto
                IconButton(
                    onClick = onShare,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Partilhar",
                        tint = CyberCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Ação: Eliminar
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Eliminar",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private fun shareEvidenceSnapshot(context: Context, evidence: DetectionEvidence) {
    val path = evidence.snapshotFilePath
    if (path == null || !File(path).exists()) {
        Toast.makeText(context, "Ficheiro de imagem não disponível para partilha.", Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val file = File(path)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_TEXT,
                "🚨 Sentinela Vision: Deteção de Alvo Confirmada\n" +
                        "Veículo: Opel Crossland X (Bicolor)\n" +
                        "Matrícula: ${evidence.detectedPlate}\n" +
                        "Confiança: ${(evidence.confidence * 100).toInt()}%"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Partilhar Evidência"))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Erro ao partilhar: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}
