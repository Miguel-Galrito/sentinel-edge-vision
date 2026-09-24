package com.sentinela.crossland.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.sentinela.crossland.data.DetectedVehicleBox
import com.sentinela.crossland.data.NormalizedRect
import com.sentinela.crossland.ui.theme.CyberAmber
import com.sentinela.crossland.ui.theme.CyberCyan
import com.sentinela.crossland.ui.theme.CyberEmerald

/**
 * Overlay de Mira Tática / Retículo de Vigilância (Cyber-Sentinel Viewfinder).
 * Fornece colimação visual da ROI com cantos militares ⌜ ⌝ ⌞ ⌟, mira central,
 * ajuste tátil da ROI e renderização em tempo real das caixas de veículos detetados.
 */
@Composable
fun RoiOverlayView(
    modifier: Modifier = Modifier,
    roi: NormalizedRect,
    onRoiChanged: (NormalizedRect) -> Unit,
    detectedVehicles: List<DetectedVehicleBox> = emptyList(),
    isEditMode: Boolean = false
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        var currentRoi by remember(roi) { mutableStateOf(roi) }
        var activeHandle by remember { mutableStateOf<DragHandle?>(null) }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isEditMode, width, height) {
                    if (!isEditMode) return@pointerInput

                    detectDragGestures(
                        onDragStart = { offset ->
                            val left = currentRoi.left * width
                            val top = currentRoi.top * height
                            val right = currentRoi.right * width
                            val bottom = currentRoi.bottom * height

                            val handleRadius = 60f

                            activeHandle = when {
                                (offset - Offset(left, top)).getDistance() < handleRadius -> DragHandle.TOP_LEFT
                                (offset - Offset(right, top)).getDistance() < handleRadius -> DragHandle.TOP_RIGHT
                                (offset - Offset(left, bottom)).getDistance() < handleRadius -> DragHandle.BOTTOM_LEFT
                                (offset - Offset(right, bottom)).getDistance() < handleRadius -> DragHandle.BOTTOM_RIGHT
                                offset.x in left..right && offset.y in top..bottom -> DragHandle.CENTER
                                else -> null
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val dx = dragAmount.x / width
                            val dy = dragAmount.y / height

                            val updated = when (activeHandle) {
                                DragHandle.TOP_LEFT -> currentRoi.copy(
                                    left = (currentRoi.left + dx).coerceAtMost(currentRoi.right - 0.1f),
                                    top = (currentRoi.top + dy).coerceAtMost(currentRoi.bottom - 0.1f)
                                )
                                DragHandle.TOP_RIGHT -> currentRoi.copy(
                                    right = (currentRoi.right + dx).coerceAtLeast(currentRoi.left + 0.1f),
                                    top = (currentRoi.top + dy).coerceAtMost(currentRoi.bottom - 0.1f)
                                )
                                DragHandle.BOTTOM_LEFT -> currentRoi.copy(
                                    left = (currentRoi.left + dx).coerceAtMost(currentRoi.right - 0.1f),
                                    bottom = (currentRoi.bottom + dy).coerceAtLeast(currentRoi.top + 0.1f)
                                )
                                DragHandle.BOTTOM_RIGHT -> currentRoi.copy(
                                    right = (currentRoi.right + dx).coerceAtLeast(currentRoi.left + 0.1f),
                                    bottom = (currentRoi.bottom + dy).coerceAtLeast(currentRoi.top + 0.1f)
                                )
                                DragHandle.CENTER -> {
                                    val roiW = currentRoi.right - currentRoi.left
                                    val roiH = currentRoi.bottom - currentRoi.top
                                    val newLeft = (currentRoi.left + dx).coerceIn(0f, 1f - roiW)
                                    val newTop = (currentRoi.top + dy).coerceIn(0f, 1f - roiH)
                                    currentRoi.copy(
                                        left = newLeft,
                                        top = newTop,
                                        right = newLeft + roiW,
                                        bottom = newTop + roiH
                                    )
                                }
                                null -> currentRoi
                            }.coerceValid()

                            currentRoi = updated
                            onRoiChanged(updated)
                        },
                        onDragEnd = {
                            activeHandle = null
                        }
                    )
                }
        ) {
            val left = currentRoi.left * size.width
            val top = currentRoi.top * size.height
            val right = currentRoi.right * size.width
            val bottom = currentRoi.bottom * size.height
            val roiWidth = right - left
            val roiHeight = bottom - top

            // 1. Sombra periférica / Máscara fora da ROI para foco na via
            val overlayMaskColor = Color.Black.copy(alpha = if (isEditMode) 0.60f else 0.35f)
            drawRect(overlayMaskColor, Offset(0f, 0f), Size(size.width, top))
            drawRect(overlayMaskColor, Offset(0f, bottom), Size(size.width, size.height - bottom))
            drawRect(overlayMaskColor, Offset(0f, top), Size(left, roiHeight))
            drawRect(overlayMaskColor, Offset(right, top), Size(size.width - right, roiHeight))

            // 2. Contorno do Retângulo da ROI
            val baseColor = if (isEditMode) CyberAmber else CyberEmerald
            drawRect(
                color = baseColor.copy(alpha = if (isEditMode) 0.8f else 0.4f),
                topLeft = Offset(left, top),
                size = Size(roiWidth, roiHeight),
                style = Stroke(
                    width = 1.5f,
                    pathEffect = if (isEditMode) PathEffect.dashPathEffect(floatArrayOf(16f, 12f)) else null
                )
            )

            // 3. Cantos Militares de Colimação Tática ⌜ ⌝ ⌞ ⌟
            val bracketLength = (roiWidth * 0.12f).coerceIn(24f, 48f)
            val bracketStroke = Stroke(width = 4.5f, cap = StrokeCap.Square)
            val bracketColor = if (isEditMode) CyberAmber else CyberCyan

            // ⌜
            drawPath(
                path = Path().apply {
                    moveTo(left, top + bracketLength)
                    lineTo(left, top)
                    lineTo(left + bracketLength, top)
                },
                color = bracketColor,
                style = bracketStroke
            )
            // ⌝
            drawPath(
                path = Path().apply {
                    moveTo(right - bracketLength, top)
                    lineTo(right, top)
                    lineTo(right, top + bracketLength)
                },
                color = bracketColor,
                style = bracketStroke
            )
            // ⌞
            drawPath(
                path = Path().apply {
                    moveTo(left, bottom - bracketLength)
                    lineTo(left, bottom)
                    lineTo(left + bracketLength, bottom)
                },
                color = bracketColor,
                style = bracketStroke
            )
            // ⌟
            drawPath(
                path = Path().apply {
                    moveTo(right - bracketLength, bottom)
                    lineTo(right, bottom)
                    lineTo(right, bottom - bracketLength)
                },
                color = bracketColor,
                style = bracketStroke
            )

            // 4. Retículo Central (+)
            val centerX = left + roiWidth / 2f
            val centerY = top + roiHeight / 2f
            val crossSize = 12f
            drawLine(
                color = bracketColor.copy(alpha = 0.5f),
                start = Offset(centerX - crossSize, centerY),
                end = Offset(centerX + crossSize, centerY),
                strokeWidth = 2f
            )
            drawLine(
                color = bracketColor.copy(alpha = 0.5f),
                start = Offset(centerX, centerY - crossSize),
                end = Offset(centerX, centerY + crossSize),
                strokeWidth = 2f
            )

            // 5. Renderização das Bounding Boxes de Veículos Detetados em Tempo Real
            for (vBox in detectedVehicles) {
                val vLeft = vBox.left * size.width
                val vTop = vBox.top * size.height
                val vWidth = (vBox.right - vBox.left) * size.width
                val vHeight = (vBox.bottom - vBox.top) * size.height

                val boxColor = if (vBox.isTargetOpel) CyberEmerald else Color(0x9900E5FF)
                val strokeWidth = if (vBox.isTargetOpel) 3.5f else 2.0f

                drawRect(
                    color = boxColor,
                    topLeft = Offset(vLeft, vTop),
                    size = Size(vWidth, vHeight),
                    style = Stroke(width = strokeWidth)
                )

                // Fundo translúcido na caixa do alvo
                if (vBox.isTargetOpel) {
                    drawRect(
                        color = CyberEmerald.copy(alpha = 0.12f),
                        topLeft = Offset(vLeft, vTop),
                        size = Size(vWidth, vHeight)
                    )
                }

                // Rótulo militar com Matrícula OCR e Confiança
                if (vBox.label.isNotEmpty()) {
                    val textPaint = android.graphics.Paint().apply {
                        color = if (vBox.isTargetOpel) android.graphics.Color.parseColor("#00E676") else android.graphics.Color.WHITE
                        textSize = 28f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                        isFakeBoldText = true
                    }
                    val bgPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(220, 10, 14, 20)
                        style = android.graphics.Paint.Style.FILL
                    }
                    val borderPaint = android.graphics.Paint().apply {
                        color = if (vBox.isTargetOpel) android.graphics.Color.parseColor("#00E676") else android.graphics.Color.parseColor("#00E5FF")
                        style = android.graphics.Paint.Style.STROKE
                        this.strokeWidth = 2f
                    }

                    val textBounds = android.graphics.Rect()
                    textPaint.getTextBounds(vBox.label, 0, vBox.label.length, textBounds)
                    val padX = 14f
                    val padY = 8f
                    val bLeft = vLeft
                    val bBottom = (vTop - 6f).coerceAtLeast(textBounds.height() + padY * 2)
                    val bTop = bBottom - (textBounds.height() + padY * 2)
                    val bRight = bLeft + textBounds.width() + padX * 2

                    drawContext.canvas.nativeCanvas.drawRoundRect(
                        bLeft, bTop, bRight, bBottom, 8f, 8f, bgPaint
                    )
                    drawContext.canvas.nativeCanvas.drawRoundRect(
                        bLeft, bTop, bRight, bBottom, 8f, 8f, borderPaint
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        vBox.label,
                        bLeft + padX,
                        bBottom - padY - 2f,
                        textPaint
                    )
                }
            }

            // 6. Manípulos circulares de arrasto no modo de Edição/Calibração
            if (isEditMode) {
                val handleRadius = 24f
                val handleHalo = 32f

                listOf(
                    Offset(left, top),
                    Offset(right, top),
                    Offset(left, bottom),
                    Offset(right, bottom)
                ).forEach { pos ->
                    drawCircle(CyberAmber.copy(alpha = 0.35f), handleHalo, pos)
                    drawCircle(CyberAmber, handleRadius, pos)
                    drawCircle(Color.White, handleRadius * 0.45f, pos)
                }

                drawCircle(CyberAmber.copy(alpha = 0.25f), 36f, Offset(centerX, centerY))
            }
        }
    }
}

private enum class DragHandle {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER
}
