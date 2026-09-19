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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.sentinela.crossland.data.NormalizedRect

@Composable
fun RoiOverlayView(
    modifier: Modifier = Modifier,
    roi: NormalizedRect,
    onRoiChanged: (NormalizedRect) -> Unit,
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

                            val handleRadius = 50f

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

            // 1. Zona de exclusão (Sombra escura semi-transparente fora da ROI)
            val overlayMaskColor = Color.Black.copy(alpha = if (isEditMode) 0.65f else 0.45f)

            // Top
            drawRect(overlayMaskColor, Offset(0f, 0f), Size(size.width, top))
            // Bottom
            drawRect(overlayMaskColor, Offset(0f, bottom), Size(size.width, size.height - bottom))
            // Left
            drawRect(overlayMaskColor, Offset(0f, top), Size(left, roiHeight))
            // Right
            drawRect(overlayMaskColor, Offset(right, top), Size(size.width - right, roiHeight))

            // 2. Borda Neon da ROI de vigilância
            val borderColor = if (isEditMode) Color(0xFFFFB300) else Color(0xFF00E676)
            drawRect(
                color = borderColor,
                topLeft = Offset(left, top),
                size = Size(roiWidth, roiHeight),
                style = Stroke(
                    width = if (isEditMode) 4f else 2.5f,
                    pathEffect = if (isEditMode) PathEffect.dashPathEffect(floatArrayOf(20f, 10f)) else null
                )
            )

            // 3. Manípulos de canto se em modo de edição
            if (isEditMode) {
                val handleRadius = 22f
                val handleColor = Color(0xFFFFB300)
                drawCircle(handleColor, handleRadius, Offset(left, top))
                drawCircle(handleColor, handleRadius, Offset(right, top))
                drawCircle(handleColor, handleRadius, Offset(left, bottom))
                drawCircle(handleColor, handleRadius, Offset(right, bottom))
            }
        }
    }
}

private enum class DragHandle {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER
}
