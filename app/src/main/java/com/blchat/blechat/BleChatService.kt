package com.blchat.blechat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/**
 * 前台服务：保持 BLE 广播 + 扫描在后台常驻，通知栏常驻「正在接收」通知，
 * 并在收到新消息时弹出消息通知。
 */
class BleChatService : Service(), BleChatManager.Listener {

    private val manager: BleChatManager
        get() = (application as BleChatApp).manager

    private val nameStore = NameStore(this)

    companion object {
        const val CHANNEL_PERSISTENT = "ble_chat_persistent"
        const val CHANNEL_MESSAGE = "ble_chat_message"
        const val NOTIF_PERSISTENT_ID = 1
        const val NOTIF_MESSAGE_ID = 2

        fun start(context: Context) {
            val i = Intent(context, BleChatService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createChannels()
        } catch (e: Exception) {
            android.util.Log.e("BleChatService", "createChannels failed", e)
        }
        manager.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // connectedDevice 类型自 API 29 起可用，作为编译期常量直接内联，无需版本判断
            ServiceCompat.startForeground(
                this,
                NOTIF_PERSISTENT_ID,
                buildPersistentNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: Exception) {
            android.util.Log.e("BleChatService", "startForeground failed, stopping", e)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            manager.start()
        } catch (e: Exception) {
            android.util.Log.e("BleChatService", "manager.start failed", e)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onMessage(message: ChatMessage) {
        if (message.isSelf) return
        val name = nameStore.getName(message.id) ?: message.id
        val content = "$name: ${message.content}"
        val n = NotificationCompat.Builder(this, CHANNEL_MESSAGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("收到新消息")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_MESSAGE_ID, n)
        } catch (e: SecurityException) {
            // 无通知权限时忽略
        }
    }

    override fun onDevicesChanged(devices: List<DeviceSignal>) {}
    override fun onNamesChanged() {}
    override fun onMappingReceived(fromId: String, entries: Map<String, String>) {}
    override fun onVersionHigher(version: Int) {}
    override fun onState(active: Boolean, reason: String) {}

    override fun onDestroy() {
        manager.removeListener(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val persistent = NotificationChannel(
            CHANNEL_PERSISTENT, "常驻接收", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "广播聊天常驻接收状态"
            setShowBadge(false)
        }
        val message = NotificationChannel(
            CHANNEL_MESSAGE, "新消息", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "收到的新广播消息" }
        nm.createNotificationChannel(persistent)
        nm.createNotificationChannel(message)
    }

    private fun buildPersistentNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_PERSISTENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("蓝牙广播聊天")
            .setContentText("正在接收附近广播…（ID: ${manager.myId}）")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(launchIntent())
            .build()
    }

    private fun launchIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
