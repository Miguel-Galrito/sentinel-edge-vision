package com.sentinela.crossland.ui.components

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.sentinela.crossland.service.CameraService

@Composable
fun CameraPreviewView(
    modifier: Modifier = Modifier,
    onPreviewViewCreated: (PreviewView) -> Unit = {}
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            PreviewView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE

                // Regista o SurfaceProvider no serviço
                CameraService.previewSurfaceProvider = this.surfaceProvider
                onPreviewViewCreated(this)
            }
        }
    )
}
