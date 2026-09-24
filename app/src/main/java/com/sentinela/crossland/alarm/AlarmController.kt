package com.sentinela.crossland.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.sentinela.crossland.data.TargetDetectionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AlarmController private constructor(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val notificationHelper = NotificationHelper(context)
    private val appPreferences = com.sentinela.crossland.data.AppPreferences(context)

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var alarmWakeLock: PowerManager.WakeLock? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoStopRunnable = Runnable {
        stopAlarm()
    }

    private val _isAlarmActive = MutableStateFlow(false)
    val isAlarmActive: StateFlow<Boolean> = _isAlarmActive.asStateFlow()

    private val _currentEvent = MutableStateFlow<TargetDetectionEvent?>(null)
    val currentEvent: StateFlow<TargetDetectionEvent?> = _currentEvent.asStateFlow()

    companion object {
        @Volatile
        private var instance: AlarmController? = null

        fun getInstance(context: Context): AlarmController {
            return instance ?: synchronized(this) {
                instance ?: AlarmController(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        initVibrator()
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Dispara o alarme em alta prioridade (Áudio no volume máximo + Vibração insistente + FullScreenIntent).
     * No Modo Calibração ou Snooze, NÃO emite som nem vibração.
     */
    @Synchronized
    fun triggerAlarm(event: TargetDetectionEvent) {
        _currentEvent.value = event

        // Se o Modo Calibração estiver ativo ou estiver em Pausa/Snooze, silenciar completamente
        if (appPreferences.isCalibrationMode || !appPreferences.isAudioAlarmEnabled || appPreferences.isSnoozed()) {
            return
        }

        _isAlarmActive.value = true

        // 1. Acorda o processador e o ecrã com WakeLock temporário
        acquireAlarmWakeLock()

        // 2. Dispara a notificação de chamada em ecrã inteiro
        notificationHelper.showCriticalAlarmNotification(event)

        // 3. Toca o som de alarme contínuo em volume máximo
        playAlarmAudio()

        // 4. Inicia vibração insistente
        startVibration()

        // 5. Inicia diretamente a AlarmActivity para visualização imediata
        try {
            val alarmIntent = android.content.Intent(context, com.sentinela.crossland.ui.AlarmActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(com.sentinela.crossland.ui.AlarmActivity.EXTRA_DETECTION_EVENT, event)
            }
            context.startActivity(alarmIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 6. Agenda auto-timeout de segurança (30s) para não tocar indefinidamente se o telemóvel estiver sem vigilância
        mainHandler.removeCallbacks(autoStopRunnable)
        mainHandler.postDelayed(autoStopRunnable, 30_000L)
    }

    private fun playAlarmAudio() {
        try {
            stopAudio()

            // Tenta definir volume máximo de forma segura
            try {
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri)
                setAudioAttributes(audioAttributes)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startVibration() {
        try {
            val pattern = longArrayOf(0, 800, 300, 800, 300)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, 0) // 0 para repetir infinitamente
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Pára imediatamente o áudio, vibração, notificação e liberta o WakeLock.
     */
    @Synchronized
    fun stopAlarm() {
        mainHandler.removeCallbacks(autoStopRunnable)
        stopAudio()
        stopVibration()
        releaseAlarmWakeLock()
        notificationHelper.cancelAlarmNotification()
        _isAlarmActive.value = false
        _currentEvent.value = null
        try {
            com.sentinela.crossland.service.CameraService.resetCooldown()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAudio() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaPlayer = null
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun acquireAlarmWakeLock() {
        try {
            if (alarmWakeLock == null) {
                @Suppress("DEPRECATION")
                alarmWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "sentinela:critical_alarm_wakelock"
                )
            }
            if (alarmWakeLock?.isHeld == false) {
                alarmWakeLock?.acquire(60_000L) // Timeout máximo de segurança de 60s
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback seguro caso flags de screen bright falhem em certos aparelhos
            try {
                if (alarmWakeLock == null) {
                    alarmWakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "sentinela:critical_alarm_partial_wakelock"
                    )
                }
                if (alarmWakeLock?.isHeld == false) {
                    alarmWakeLock?.acquire(60_000L)
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private fun releaseAlarmWakeLock() {
        try {
            if (alarmWakeLock?.isHeld == true) {
                alarmWakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
