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

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var alarmWakeLock: PowerManager.WakeLock? = null

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
     */
    @Synchronized
    fun triggerAlarm(event: TargetDetectionEvent) {
        _currentEvent.value = event
        _isAlarmActive.value = true

        // 1. Acorda o processador e o ecrã com WakeLock temporário
        acquireAlarmWakeLock()

        // 2. Dispara a notificação de chamada em ecrã inteiro
        notificationHelper.showCriticalAlarmNotification(event)

        // 3. Toca o som de alarme contínuo em volume máximo
        playAlarmAudio()

        // 4. Inicia vibração insistente
        startVibration()
    }

    private fun playAlarmAudio() {
        try {
            stopAudio()

            // Define volume no máximo do stream de alarme
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

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
        stopAudio()
        stopVibration()
        releaseAlarmWakeLock()
        notificationHelper.cancelAlarmNotification()
        _isAlarmActive.value = false
        _currentEvent.value = null
    }

    private fun stopAudio() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
    }

    private fun stopVibration() {
        vibrator?.cancel()
    }

    private fun acquireAlarmWakeLock() {
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
    }

    private fun releaseAlarmWakeLock() {
        if (alarmWakeLock?.isHeld == true) {
            alarmWakeLock?.release()
        }
    }
}
