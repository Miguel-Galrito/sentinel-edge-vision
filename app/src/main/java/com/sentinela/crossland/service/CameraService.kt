package com.sentinela.crossland.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sentinela.crossland.alarm.AlarmController
import com.sentinela.crossland.alarm.NotificationHelper
import com.sentinela.crossland.data.AppPreferences
import com.sentinela.crossland.data.SurveillanceMetrics
import com.sentinela.crossland.vision.DetectionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Foreground Service com tipo 'camera' (obrigatório Android 14+ / API 34).
 * Mantém a análise contínua de frames mesmo com o ecrã escuro através de PARTIAL_WAKE_LOCK.
 */
class CameraService : LifecycleService() {

    private lateinit var appPreferences: AppPreferences
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var alarmController: AlarmController

    private var detectionPipeline: DetectionPipeline? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var partialWakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START_SURVEILLANCE = "ACTION_START_SURVEILLANCE"
        const val ACTION_STOP_SURVEILLANCE = "ACTION_STOP_SURVEILLANCE"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _metrics = MutableStateFlow(SurveillanceMetrics())
        val metrics: StateFlow<SurveillanceMetrics> = _metrics.asStateFlow()

        var previewSurfaceProvider: Preview.SurfaceProvider? = null

        fun startSurveillance(context: Context) {
            val intent = Intent(context, CameraService::class.java).apply {
                action = ACTION_START_SURVEILLANCE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopSurveillance(context: Context) {
            val intent = Intent(context, CameraService::class.java).apply {
                action = ACTION_STOP_SURVEILLANCE
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appPreferences = AppPreferences(this)
        notificationHelper = NotificationHelper(this)
        alarmController = AlarmController.getInstance(this)

        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP_SURVEILLANCE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_SURVEILLANCE, null -> {
                startForegroundWithNotification()
                initCameraAndPipeline()
            }
        }

        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = notificationHelper.buildServiceNotification()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NotificationHelper.NOTIFICATION_SERVICE_ID,
            notification,
            foregroundType
        )
        _isServiceRunning.value = true
    }

    private fun initCameraAndPipeline() {
        if (detectionPipeline != null) return

        detectionPipeline = DetectionPipeline(
            context = this,
            getRoi = { appPreferences.roi },
            getSensitivity = { appPreferences.motionSensitivity }
        )

        // Observa métricas do pipeline e repassa para a UI
        lifecycleScope.launch(Dispatchers.Default) {
            detectionPipeline?.metrics?.collect { currentMetrics ->
                _metrics.value = currentMetrics.copy(isServiceRunning = true)
            }
        }

        // Observa eventos de deteção do veículo e dispara o alarme
        lifecycleScope.launch(Dispatchers.Main) {
            detectionPipeline?.detectionEvents?.collect { event ->
                appPreferences.incrementDetections()
                alarmController.triggerAlarm(event)
            }
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        // 1. UseCase de Análise de Imagem (720p - Equilíbrio ótimo entre precisão de OCR e baixo consumo)
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        detectionPipeline?.let { pipeline ->
            imageAnalysis.setAnalyzer(cameraExecutor, pipeline)
        }

        // 2. UseCase de Preview para o ecrã se SurfaceProvider estiver configurado
        val preview = Preview.Builder().build()
        previewSurfaceProvider?.let {
            preview.setSurfaceProvider(it)
        }

        try {
            provider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        partialWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "sentinela:camera_service_wakelock"
        ).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        partialWakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        partialWakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceRunning.value = false
        _metrics.value = _metrics.value.copy(isServiceRunning = false)

        detectionPipeline?.release()
        detectionPipeline = null

        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()

        releaseWakeLock()
    }
}
