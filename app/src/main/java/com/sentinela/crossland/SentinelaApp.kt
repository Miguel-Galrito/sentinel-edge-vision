package com.sentinela.crossland

import android.app.Application
import com.sentinela.crossland.alarm.NotificationHelper

class SentinelaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Inicializa os canais de notificação na inicialização do processo
        NotificationHelper(this)
    }
}
