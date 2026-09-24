package com.sentinela.crossland.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.sentinela.crossland.data.SurveillanceMetrics
import com.sentinela.crossland.ui.theme.CyberAmber
import com.sentinela.crossland.ui.theme.CyberBlue
import com.sentinela.crossland.ui.theme.CyberCyan
import com.sentinela.crossland.ui.theme.CyberEmerald
import com.sentinela.crossland.ui.theme.CyberObsidian
import com.sentinela.crossland.ui.theme.TextMuted
import com.sentinela.crossland.ui.theme.TextPrimary
import com.sentinela.crossland.ui.theme.TextSecondary

/**
 * Modal de Telemetria e Diagnóstico em Tempo Real do Pipeline de Visão Computacional.
 * Permite a demonstração técnica das fases de latência, estado térmico e consumo de memória.
 */
@Composable
fun TelemetryModal(
    metrics: SurveillanceMetrics,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(CyberObsidian)
                .border(1.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header com Título e Fechar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(CyberCyan, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TELEMETRIA DE ENGENHARIA",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fechar",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Seção 1: Decomposição de Latência do Pipeline
                Text(
                    text = "DECOMPOSIÇÃO DE LATÊNCIA (ON-DEVICE)",
                    color = CyberCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(10.dp))

                val telemetry = metrics.telemetry
                val totalMs = telemetry.totalLatencyMs.coerceAtLeast(1L).toFloat()

                LatencyBarRow(
                    label = "Pré-processamento (Plano Y)",
                    ms = telemetry.preprocessMs,
                    fraction = (telemetry.preprocessMs / totalMs).coerceIn(0f, 1f),
                    color = CyberEmerald
                )

                Spacer(modifier = Modifier.height(6.dp))

                LatencyBarRow(
                    label = "Classificador Bicolor Opel",
                    ms = telemetry.classificationMs,
                    fraction = (telemetry.classificationMs / totalMs).coerceIn(0f, 1f),
                    color = CyberAmber
                )

                Spacer(modifier = Modifier.height(6.dp))

                LatencyBarRow(
                    label = "ML Kit OCR (Matrícula)",
                    ms = telemetry.ocrMs,
                    fraction = (telemetry.ocrMs / totalMs).coerceIn(0f, 1f),
                    color = CyberBlue
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x66161922))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LATÊNCIA TOTAL CICLO",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${telemetry.totalLatencyMs} ms",
                        color = if (telemetry.totalLatencyMs < 45) CyberEmerald else CyberAmber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Seção 2: Hardware & Sistema
                Text(
                    text = "HARDWARE & ESTADO DO DISPOSITIVO",
                    color = CyberCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HardwareCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Thermostat,
                        title = "TÉRMICO",
                        value = telemetry.thermalStatus,
                        valueColor = when (telemetry.thermalStatus) {
                            "NOMINAL", "LIGHT" -> CyberEmerald
                            "MODERATE" -> CyberAmber
                            else -> Color(0xFFFF1744)
                        }
                    )

                    HardwareCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Memory,
                        title = "JVM HEAP",
                        value = "${telemetry.memoryUsageMb} MB",
                        valueColor = if (telemetry.memoryUsageMb < 120) CyberEmerald else CyberAmber
                    )

                    HardwareCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Speed,
                        title = "FPS ATUAL",
                        value = "%.1f".format(metrics.currentFps),
                        valueColor = if (metrics.isBurstMode) CyberAmber else CyberEmerald
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E222D),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "CONCLUIR DIAGNÓSTICO",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun LatencyBarRow(
    label: String,
    ms: Long,
    fraction: Float,
    color: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "$ms ms",
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = color,
            trackColor = Color(0x33FFFFFF)
        )
    }
}

@Composable
private fun HardwareCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    value: String,
    valueColor: Color
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x59161922))
            .border(1.dp, Color(0x2BFFFFFF), RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = valueColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                color = TextMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                color = valueColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
