package com.github.konkers.irminsul

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.konkers.irminsul.ui.theme.IrminsulTheme

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private var isCapturing by mutableStateOf(false)
    private var captureStats by mutableStateOf(CaptureStatsData())
    
    data class CaptureStatsData(
        val totalPackets: Long = 0,
        val genshinPackets: Long = 0,
        val filteredPackets: Long = 0,
        val bytesCaptured: Long = 0,
        val elapsedSeconds: Double = 0.0
    )
    
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startCaptureService()
        } else {
            Toast.makeText(this, "需要 VPN 权限才能捕获数据", Toast.LENGTH_SHORT).show()
        }
    }
    
    // 统计广播接收器
    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CaptureService.ACTION_CAPTURE_STARTED -> {
                    isCapturing = true
                }
                CaptureService.ACTION_CAPTURE_STOPPED -> {
                    isCapturing = false
                    captureStats = CaptureStatsData()
                }
                CaptureService.ACTION_STATS_UPDATED -> {
                    val json = intent.getStringExtra("stats_json") ?: return
                    try {
                        val obj = org.json.JSONObject(json)
                        captureStats = CaptureStatsData(
                            totalPackets = obj.optLong("total_packets", 0),
                            genshinPackets = obj.optLong("genshin_packets", 0),
                            filteredPackets = obj.optLong("filtered_packets", 0),
                            bytesCaptured = obj.optLong("bytes_captured", 0),
                            elapsedSeconds = obj.optDouble("elapsed_seconds", 0.0)
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse stats", e)
                    }
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 注册广播接收器
        val filter = IntentFilter().apply {
            addAction(CaptureService.ACTION_CAPTURE_STARTED)
            addAction(CaptureService.ACTION_CAPTURE_STOPPED)
            addAction(CaptureService.ACTION_STATS_UPDATED)
        }
        registerReceiver(statsReceiver, filter)
        
        // 初始化原生库
        try {
            System.loadLibrary("capture_engine")
            nativeInitParser()
            Log.i(TAG, "Native libraries loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load native libraries", e)
            Toast.makeText(this, "加载原生库失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
        
        setContent {
            IrminsulTheme {
                MainScreen(
                    isCapturing = isCapturing,
                    stats = captureStats,
                    onStartCapture = { checkVpnPermissionAndStart() },
                    onStopCapture = { stopCaptureService() }
                )
            }
        }
    }
    
    @Composable
    fun MainScreen(
        isCapturing: Boolean,
        stats: CaptureStatsData,
        onStartCapture: () -> Unit,
        onStopCapture: () -> Unit
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "irminsul-android",
                    style = MaterialTheme.typography.headlineLarge
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Genshin Impact Packet Capture",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // 状态卡片
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isCapturing) "● 正在捕获数据" else "○ 准备就绪",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isCapturing) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        if (isCapturing) {
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // 统计信息
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                StatItem("总包数", "${stats.totalPackets}")
                                StatItem("原神包", "${stats.genshinPackets}")
                                StatItem("过滤", "${stats.filteredPackets}")
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                StatItem("流量", formatBytes(stats.bytesCaptured))
                                StatItem("时间", formatTime(stats.elapsedSeconds))
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // 控制按钮
                Button(
                    onClick = if (isCapturing) onStopCapture else onStartCapture,
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text(if (isCapturing) "停止捕获" else "开始捕获")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedButton(
                    onClick = {
                        Toast.makeText(this@MainActivity, "数据查看功能开发中", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text("查看捕获的数据")
                }
            }
        }
    }
    
    @Composable
    fun StatItem(label: String, value: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
    
    private fun formatTime(seconds: Double): String {
        val mins = (seconds / 60).toInt()
        val secs = (seconds % 60).toInt()
        return if (mins > 0) "${mins}m${secs}s" else "${secs}s"
    }
    
    private fun checkVpnPermissionAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startCaptureService()
        }
    }
    
    private fun startCaptureService() {
        Log.i(TAG, "Starting capture service")
        val intent = Intent(this, CaptureService::class.java)
        startForegroundService(intent)
        Toast.makeText(this, "开始捕获数据", Toast.LENGTH_SHORT).show()
    }
    
    private fun stopCaptureService() {
        Log.i(TAG, "Stopping capture service")
        val intent = Intent(this, CaptureService::class.java)
        stopService(intent)
        Toast.makeText(this, "停止捕获数据", Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statsReceiver)
        stopCaptureService()
    }
    
    private external fun nativeInitParser(): Boolean
}
