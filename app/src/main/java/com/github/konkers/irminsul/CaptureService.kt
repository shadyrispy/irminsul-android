package com.github.konkers.irminsul

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "irminsul_capture_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CAPTURE_STARTED = "com.github.konkers.irminsul.CAPTURE_STARTED"
        const val ACTION_CAPTURE_STOPPED = "com.github.konkers.irminsul.CAPTURE_STOPPED"
        const val ACTION_PACKET_CAPTURED = "com.github.konkers.irminsul.PACKET_CAPTURED"
        const val ACTION_STATS_UPDATED = "com.github.konkers.irminsul.STATS_UPDATED"
    }

    private var isCapturing = false
    private var captureThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Capture service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须先调用 startForeground()，无论什么情况
        // Android 8+ 要求在5秒内调用，否则会抛出 ForegroundServiceDidNotStartInTimeException
        startForeground(NOTIFICATION_ID, createNotification("正在捕获原神数据包..."))

        if (isCapturing) {
            Log.w(TAG, "Capture already running")
            return START_NOT_STICKY
        }

        isCapturing = true
        sendBroadcast(Intent(ACTION_CAPTURE_STARTED))

        // 在后台线程中运行捕获循环
        captureThread = Thread {
            try {
                runCaptureLoop()
            } catch (e: InterruptedException) {
                Log.i(TAG, "Capture thread interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "Capture thread error", e)
            }
        }.apply {
            name = "irminsul-capture"
            isDaemon = true
            start()
        }

        return START_NOT_STICKY
    }

    private fun runCaptureLoop() {
        Log.i(TAG, "Capture loop started")

        // 尝试调用原生方法启动捕获
        try {
            val result = nativeStartCapture(0)
            if (!result) {
                Log.e(TAG, "Failed to start native capture")
                updateNotification("捕获启动失败")
                stopSelf()
                return
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native capture not available, running in demo mode")
            updateNotification("演示模式（原生库未加载）")
        }

        try {
            nativeSetFilter(true, true, intArrayOf(22101), false, 0)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native setFilter not available")
        }

        // 定时轮询统计信息
        while (isCapturing && !Thread.currentThread().isInterrupted) {
            try {
                val statsJson = nativeGetStats()
                if (statsJson != null && statsJson.isNotEmpty()) {
                    val statsIntent = Intent(ACTION_STATS_UPDATED).apply {
                        putExtra("stats_json", statsJson)
                    }
                    sendBroadcast(statsIntent)
                }
                Thread.sleep(2000)
            } catch (e: InterruptedException) {
                break
            } catch (e: UnsatisfiedLinkError) {
                // 原生方法不可用，以简单计数模式运行
                try {
                    Thread.sleep(2000)
                } catch (ie: InterruptedException) {
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error polling stats", e)
            }
        }

        Log.i(TAG, "Capture loop ended")
    }

    override fun onDestroy() {
        Log.i(TAG, "Capture service destroying")
        isCapturing = false
        captureThread?.interrupt()
        captureThread = null

        try {
            nativeStopCapture()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native stopCapture not available")
        }

        sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
        Log.i(TAG, "Capture service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Irminsul 数据捕获",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "原神数据包捕获服务通知"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Irminsul")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        try {
            val notification = createNotification(contentText)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    // 原生方法声明（可选实现，加载失败不会崩溃）
    private external fun nativeStartCapture(vpnFd: Int): Boolean
    private external fun nativeStopCapture()
    private external fun nativeParsePacket(packetData: ByteArray): String?
    private external fun nativeGetStats(): String?
    private external fun nativeSetFilter(
        enabled: Boolean,
        filterByPort: Boolean,
        ports: IntArray,
        filterByProtocol: Boolean,
        protocol: Int
    )
}
