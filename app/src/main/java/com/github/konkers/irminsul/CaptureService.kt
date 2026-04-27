package com.github.konkers.irminsul

import android.content.Intent
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

@AndroidEntryPoint
class CaptureService : LifecycleService() {

    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_CAPTURE_STARTED = "com.github.konkers.irminsul.CAPTURE_STARTED"
        const val ACTION_CAPTURE_STOPPED = "com.github.konkers.irminsul.CAPTURE_STOPPED"
        const val ACTION_PACKET_CAPTURED = "com.github.konkers.irminsul.PACKET_CAPTURED"
        const val ACTION_STATS_UPDATED = "com.github.konkers.irminsul.STATS_UPDATED"
    }

    private var isCapturing = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Capture service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (isCapturing) {
            Log.w(TAG, "Capture already running")
            return START_NOT_STICKY
        }

        isCapturing = true

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
