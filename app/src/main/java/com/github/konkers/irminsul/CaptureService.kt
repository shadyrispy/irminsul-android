package com.github.konkers.irminsul

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CaptureService : LifecycleService() {

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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Capture service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (isCapturing) {
            Log.w(TAG, "Capture already running")
            return START_NOT_STICKY
        }

        isCapturing = true

        // Android 8+ 必须调用 startForeground()，且在 onCreate 后5秒内
        startForeground(NOTIFICATION_ID, createNotification("准备中..."))

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                startCapture()
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed", e)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startCapture() {
        Log.i(TAG, "Starting packet capture...")

        // 更新通知
        updateNotification("正在捕获原神数据包...")

        sendBroadcast(Intent(ACTION_CAPTURE_STARTED))

        // 安全调用原生方法
        try {
            val result = nativeStartCapture(0)
            if (!result) {
                Log.e(TAG, "Failed to start native capture")
                stopSelf()
                return
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native capture method not available", e)
            stopSelf()
            return
        }

        try {
            nativeSetFilter(
                true,
                true,
                intArrayOf(22101),
                false,
                0
            )
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native setFilter not available", e)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            while (isCapturing) {
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
                    Log.w(TAG, "Native getStats not available", e)
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling stats", e)
                }
            }
        }
    }

    @Suppress("unused")
    private fun nativeOnPacketsCaptured(packets: Array<ByteArray>) {
        if (packets.isEmpty()) return
        Log.d(TAG, "Batch received: ${packets.size} packets")

        for (packet in packets) {
            try {
                val parseResult = nativeParsePacket(packet)
                if (parseResult != null && parseResult.isNotEmpty() && parseResult != "{}") {
                    val packetIntent = Intent(ACTION_PACKET_CAPTURED).apply {
                        putExtra("parsed_data", parseResult)
                        putExtra("packet_size", packet.size)
                        putExtra("timestamp", System.currentTimeMillis())
                    }
                    sendBroadcast(packetIntent)
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native parsePacket not available", e)
            }
        }
    }

    @Suppress("unused")
    private fun nativeOnPacketCaptured(packet: ByteArray): Boolean {
        try {
            val parseResult = nativeParsePacket(packet)
            if (parseResult != null && parseResult.isNotEmpty() && parseResult != "{}") {
                val packetIntent = Intent(ACTION_PACKET_CAPTURED).apply {
                    putExtra("parsed_data", parseResult)
                    putExtra("packet_size", packet.size)
                    putExtra("timestamp", System.currentTimeMillis())
                }
                sendBroadcast(packetIntent)
                return true
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native parsePacket not available", e)
        }
        return false
    }

    override fun onDestroy() {
        Log.i(TAG, "Capture service destroying")
        isCapturing = false
        try {
            nativeStopCapture()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native stopCapture not available", e)
        }
        sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
        Log.i(TAG, "Capture service destroyed")
        super.onDestroy()
    }

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
        val notification = createNotification(contentText)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private external fun nativeStartCapture(vpnFd: Int): Boolean
    private external fun nativeStopCapture(): Boolean
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
