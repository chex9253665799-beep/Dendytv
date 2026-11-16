package com.swordfish.lemuroid.app.tv.phonepad

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.swordfish.lemuroid.app.mobile.shared.NotificationsManager
import timber.log.Timber

/** Держит сервер «телефон как джойстик» живым, пока идёт игра (основной процесс уходит в фон). */
class PhonePadService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NotificationsManager.PHONE_PAD_NOTIFICATION_ID,
                NotificationsManager(applicationContext).phonePadNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.onFailure { Timber.w(it, "PhonePad: без фоновой службы") }
        PhonePadServer.start(applicationContext)
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    companion object {
        fun start(context: Context) {
            PhonePadServer.start(context.applicationContext)
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, PhonePadService::class.java))
            }.onFailure { Timber.w(it) }
        }
    }
}
