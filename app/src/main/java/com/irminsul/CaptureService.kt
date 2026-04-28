package com.irminsul

import android.content.Intent
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class CaptureService : LifecycleService() {
    
    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_CAPTURE_STARTED = "com.irminsul.CAPTURE_STARTED"
        const val ACTION_CAPTURE_STOPPED = "com.irminsul.CAPTURE_STOPPED"
        const val ACTION_PACKET_CAPTURED = "com.irminsul.PACKET_CAPTURED"
        const val ACTION_STATS_UPDATED = "com.irminsul.STATS_UPDATED"
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
        
        // 启动原生捕获
        val result = nativeStartCapture(0)
        
        if (!result) {
            Log.e(TAG, "Failed to start native capture")
            stopSelf()
            return
        }
        
        // 设置默认过滤规则（仅原神流量）
        nativeSetFilter(
            true,           // enabled
            true,           // filterByPort
            intArrayOf(22101), // ports
            false,          // filterByProtocol
            0               // protocol (unused)
        )
        
        // 定时查询统计信息
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
                    Thread.sleep(2000) // 每 2 秒更新一次统计
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling stats", e)
                }
            }
        }
    }
    
    /**
     * 批量数据包回调（C++ 层通过 epoll+batch 回调）
     * 签名: nativeOnPacketsCaptured([[B)V
     */
    @Suppress("unused")
    private fun nativeOnPacketsCaptured(packets: Array<ByteArray>) {
        if (packets.isEmpty()) return
        
        Log.d(TAG, "Batch received: ${packets.size} packets")
        
        for (packet in packets) {
            // 调用 Rust 解析器解析每个数据包
            val parseResult = nativeParsePacket(packet)
            
            if (parseResult != null && parseResult.isNotEmpty() && parseResult != "{}") {
                // 发送广播通知 UI 层
                val packetIntent = Intent(ACTION_PACKET_CAPTURED).apply {
                    putExtra("parsed_data", parseResult)
                    putExtra("packet_size", packet.size)
                    putExtra("timestamp", System.currentTimeMillis())
                }
                sendBroadcast(packetIntent)
            }
        }
    }
    
    /**
     * 单包回调（兼容旧版，C++ 层回退使用）
     * 签名: nativeOnPacketCaptured([B)Z
     */
    @Suppress("unused")
    private fun nativeOnPacketCaptured(packet: ByteArray): Boolean {
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
        return false
    }
    
    override fun onDestroy() {
        Log.i(TAG, "Capture service destroying")
        
        isCapturing = false
        nativeStopCapture()
        
        sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
        
        Log.i(TAG, "Capture service destroyed")
        super.onDestroy()
    }
    
    // ──────────────────────────────────────────
    // JNI 方法
    // ──────────────────────────────────────────
    
    // 启动捕获
    private external fun nativeStartCapture(vpnFd: Int): Boolean
    
    // 停止捕获
    private external fun nativeStopCapture(): Boolean
    
    // 解析数据包
    private external fun nativeParsePacket(packetData: ByteArray): String?
    
    // 获取捕获统计（JSON 格式）
    private external fun nativeGetStats(): String?
    
    // 设置过滤规则
    private external fun nativeSetFilter(
        enabled: Boolean,
        filterByPort: Boolean,
        ports: IntArray,
        filterByProtocol: Boolean,
        protocol: Int
    )
}
