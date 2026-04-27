package com.github.konkers.irminsul

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CaptureService : LifecycleService() {
    
    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_CAPTURE_STARTED = "com.github.konkers.irminsul.CAPTURE_STARTED"
        const val ACTION_CAPTURE_STOPPED = "com.github.konkers.irminsul.CAPTURE_STOPPED"
        const val ACTION_PACKET_CAPTURED = "com.github.konkers.irminsul.PACKET_CAPTURED"
    }
    
    private var isCapturing = false
    private var captureThread: Thread? = null
    
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
        
        // 启动捕获协程
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
    
    private suspend fun startCapture() {
        Log.i(TAG, "Starting packet capture...")
        
        // 发送广播通知捕获开始
        val startIntent = Intent(ACTION_CAPTURE_STARTED)
        sendBroadcast(startIntent)
        
        // 启动原生捕获
        val result = nativeStartCapture(0) // TODO: 获取实际的 VPN fd
        
        if (!result) {
            Log.e(TAG, "Failed to start native capture")
            stopSelf()
            return
        }
        
        // 模拟捕获循环（实际应由原生代码回调）
        captureThread = Thread {
            var packetCount = 0
            
            while (isCapturing) {
                try {
                    // 模拟数据包捕获
                    Thread.sleep(100) // 100ms
                    
                    // 模拟偶尔捕获到数据包
                    if (Math.random() < 0.1) { // 10% 概率
                        packetCount++
                        
                        // 发送广播通知捕获到数据包
                        val packetIntent = Intent(ACTION_PACKET_CAPTURED).apply {
                            putExtra("packet_count", packetCount)
                            putExtra("timestamp", System.currentTimeMillis())
                        }
                        sendBroadcast(packetIntent)
                        
                        Log.d(TAG, "Packet captured: $packetCount")
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in capture loop", e)
                }
            }
        }
        
        captureThread?.start()
    }
    
    override fun onDestroy() {
        Log.i(TAG, "Capture service destroying")
        
        isCapturing = false
        
        // 停止原生捕获
        nativeStopCapture()
        
        // 等待捕获线程结束
        captureThread?.interrupt()
        captureThread?.join(1000)
        
        // 发送广播通知捕获停止
        val stopIntent = Intent(ACTION_CAPTURE_STOPPED)
        sendBroadcast(stopIntent)
        
        Log.i(TAG, "Capture service destroyed")
        super.onDestroy()
    }
    
    // JNI 方法
    private external fun nativeStartCapture(vpnFd: Int): Boolean
    private external fun nativeStopCapture(): Boolean
    private external fun nativeParsePacket(packetData: ByteArray): String?
}
