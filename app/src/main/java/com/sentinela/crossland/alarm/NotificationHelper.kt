package com.sentinela.crossland.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sentinela.crossland.R
import com.sentinela.crossland.data.TargetDetectionEvent
import com.sentinela.crossland.ui.AlarmActivity
import com.sentinela.crossland.ui.MainActivity

class NotificationHelper(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_SERVICE_ID = "channel_sentinela_service"
        const val CHANNEL_ALARM_ID = "channel_sentinela_alarm_critical"

        const val NOTIFICATION_SERVICE_ID = 1001
        const val NOTIFICATION_ALARM_ID = 9999
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 1. Canal do Serviço em Primeiro Plano (Visível e afixado na barra superior)
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                context.getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_service_desc)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // 2. Canal de Alarme Crítico (Prioridade Máxima com som de alarme e vibração)
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM_ID,
                context.getString(R.string.channel_alarm_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_alarm_desc)
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 300, 800, 300)
                setSound(alarmSound, audioAttributes)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (notificationManager.isNotificationPolicyAccessGranted) {
                            setBypassDnd(true)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            notificationManager.createNotificationChannel(alarmChannel)
        }
    }

    /**
     * Constrói a notificação persistente do Foreground Service afixada na barra superior
     * com ações de Kill-Switch ("Encerrar Serviço") e Snooze ("Silenciar / Pausar 10 min").
     */
    fun buildServiceNotification(isPaused: Boolean = false, pauseMinutesRemaining: Int = 0): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Ação 1: Silenciar / Pausar 10 min (ou Retomar)
        val pauseActionTitle = if (isPaused) {
            "RETOMAR SENTINELA"
        } else {
            "SILENCIAR / PAUSAR 10 MIN"
        }
        val pauseActionIntent = Intent(context, com.sentinela.crossland.service.CameraService::class.java).apply {
            action = if (isPaused) {
                com.sentinela.crossland.service.CameraService.ACTION_RESUME_SURVEILLANCE
            } else {
                com.sentinela.crossland.service.CameraService.ACTION_PAUSE_10_MIN
            }
        }
        val pausePendingIntent = PendingIntent.getService(
            context, 1, pauseActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Ação 2: Kill-Switch Imediato ("Encerrar Serviço")
        val stopIntent = Intent(context, com.sentinela.crossland.service.CameraService::class.java).apply {
            action = com.sentinela.crossland.service.CameraService.ACTION_STOP_SURVEILLANCE
        }
        val stopPendingIntent = PendingIntent.getService(
            context, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isPaused) {
            "⏸️ Sentinela em Pausa ($pauseMinutesRemaining min)"
        } else {
            "🛡️ Sentinela Crossland Ativa"
        }

        val content = if (isPaused) {
            "Alarmes silenciados temporariamente."
        } else {
            "A monitorizar via: Opel Crossland X (28-VE-91)"
        }

        return NotificationCompat.Builder(context, CHANNEL_SERVICE_ID)
            .setSmallIcon(R.drawable.ic_camera_service)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_camera_service, pauseActionTitle, pausePendingIntent)
            .addAction(R.drawable.ic_camera_service, "ENCERRAR SERVIÇO", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun updateServiceNotification(isPaused: Boolean = false, pauseMinutesRemaining: Int = 0) {
        notificationManager.notify(
            NOTIFICATION_SERVICE_ID,
            buildServiceNotification(isPaused, pauseMinutesRemaining)
        )
    }

    /**
     * Dispara a notificação de chamada em ecrã inteiro (FullScreenIntent).
     */
    fun showCriticalAlarmNotification(event: TargetDetectionEvent) {
        try {
            val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(AlarmActivity.EXTRA_DETECTION_EVENT, event)
            }

            val fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ALARM_ID,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ALARM_ID)
                .setSmallIcon(R.drawable.ic_alarm_alert)
                .setContentTitle(context.getString(R.string.alarm_title))
                .setContentText("OPEL CROSSLAND X DETETADO (Perfil Bicolor)")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)
                .setAutoCancel(true)
                .setOngoing(true)
                .build()

            notificationManager.notify(NOTIFICATION_ALARM_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelServiceNotification() {
        notificationManager.cancel(NOTIFICATION_SERVICE_ID)
    }

    fun cancelAlarmNotification() {
        notificationManager.cancel(NOTIFICATION_ALARM_ID)
    }
}
