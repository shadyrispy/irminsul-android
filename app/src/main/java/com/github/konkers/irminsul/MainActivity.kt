package com.github.konkers.irminsul

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.konkers.irminsul.ui.theme.IrminsulTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val BASE_URL = "https://github.com/konkers/auto-artifactarium/raw/refs/heads/main/game_data"
        private val GAME_DATA_FILES = listOf(
            "gi_keys.json",
            "TextMapCHS.json",
            "AvatarExcelConfigData.json",
            "WeaponExcelConfigData.json",
            "MaterialExcelConfigData.json",
            "ReliquaryExcelConfigData.json",
            "ReliquaryMainPropExcelConfigData.json"
        )
    }

    private var isCapturing by mutableStateOf(false)
    private var captureStats by mutableStateOf(CaptureStatsData())

    // 初始化状态
    private var isInitializing by mutableStateOf(true)
    private var initProgress by mutableStateOf(0f)
    private var initStatus by mutableStateOf("检查数据文件...")

    // 标记接收器是否已注册
    private var receiverRegistered = false

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
        try {
            val filter = IntentFilter().apply {
                addAction(CaptureService.ACTION_CAPTURE_STARTED)
                addAction(CaptureService.ACTION_CAPTURE_STOPPED)
                addAction(CaptureService.ACTION_STATS_UPDATED)
            }
            // Android 14+ 必须指定接收器导出标志
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerReceiver(statsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(statsReceiver, filter)
            }
            receiverRegistered = true

            // 安全加载原生库
            loadNativeLibraries()

            // 检查并初始化数据文件
            checkAndInitializeData()

            setContent {
                IrminsulTheme {
                    if (isInitializing) {
                        InitializationScreen(
                            progress = initProgress,
                            status = initStatus
                        )
                    } else {
                        MainScreen(
                            isCapturing = isCapturing,
                            stats = captureStats,
                            onStartCapture = { checkVpnPermissionAndStart() },
                            onStopCapture = { stopCaptureService() }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onCreate crashed", e)
            Toast.makeText(this, "启动崩溃: ${e.message}", Toast.LENGTH_LONG).show()
            setContent {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "启动失败: ${e.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    private fun loadNativeLibraries() {
        // 加载 Rust 解析器库（可选，加载失败不影响启动）
        try {
            System.loadLibrary("irminsul_android_parser")
            Log.i(TAG, "Rust parser library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Rust parser library not found, packet parsing disabled", e)
        } catch (e: Exception) {
            Log.w(TAG, "Error loading Rust parser library", e)
        }

        // 初始化解析器（可选）
        try {
            nativeInitParser()
            Log.i(TAG, "Parser initialized successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Parser init not available", e)
        } catch (e: Exception) {
            Log.w(TAG, "Parser initialization skipped", e)
        }
    }

    private fun checkAndInitializeData() {
        val dataDir = File(filesDir, "game_data")
        val needsDownload = GAME_DATA_FILES.any { file ->
            !File(dataDir, file).exists()
        }

        if (needsDownload) {
            Log.i(TAG, "Game data files missing, starting download")
            downloadGameData()
        } else {
            Log.i(TAG, "Game data files already exist")
            isInitializing = false
        }
    }

    private fun downloadGameData() {
        // 使用 lifecycleScope，随 Activity 生命周期自动取消
        lifecycleScope.launch {
            try {
                val dataDir = File(filesDir, "game_data")
                if (!dataDir.exists()) {
                    dataDir.mkdirs()
                }

                initStatus = "准备下载数据文件..."
                initProgress = 0f

                for ((index, fileName) in GAME_DATA_FILES.withIndex()) {
                    initStatus = "正在下载: $fileName"
                    initProgress = (index.toFloat() / GAME_DATA_FILES.size)

                    downloadFile("$BASE_URL/$fileName", File(dataDir, fileName))
                    Log.i(TAG, "Downloaded $fileName")
                }

                initProgress = 1.0f
                initStatus = "初始化完成"
                delay(500)

                isInitializing = false
                Toast.makeText(this@MainActivity, "数据初始化完成", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "Game data initialization completed")
            } catch (e: CancellationException) {
                // 协程被取消（Activity 销毁），不需要处理
                Log.i(TAG, "Download cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download game data", e)
                initStatus = "初始化失败: ${e.message}"
                // 即使失败也允许进入主界面
                delay(2000)
                isInitializing = false
            }
        }
    }

    private suspend fun downloadFile(url: String, outputFile: File) {
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000  // 15秒连接超时
                    readTimeout = 60000     // 60秒读取超时
                    instanceFollowRedirects = true
                }

                // 处理 HTTP 重定向（GitHub raw 链接会 301 重定向）
                var responseCode = connection.responseCode
                var redirectCount = 0
                while ((responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                            responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                            responseCode == HttpURLConnection.HTTP_SEE_OTHER) &&
                    redirectCount < 5
                ) {
                val redirectUrl = connection?.headerFields?.get("Location")?.firstOrNull() ?: break
                connection?.disconnect()
                connection = (URL(redirectUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 60000
                }
                responseCode = connection?.responseCode ?: break
                    redirectCount++
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP $responseCode for $url")
                }

                connection?.inputStream?.use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    @Composable
    fun InitializationScreen(progress: Float, status: String) {
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
                    text = "Irminsul",
                    style = MaterialTheme.typography.headlineLarge
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Genshin Impact Packet Capture",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(48.dp))

                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(0.6f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (progress > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                    text = "Irminsul",
                    style = MaterialTheme.typography.headlineLarge
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Genshin Impact Packet Capture",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(32.dp))

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
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "开始捕获数据", Toast.LENGTH_SHORT).show()
    }

    private fun stopCaptureService() {
        Log.i(TAG, "Stopping capture service")
        val intent = Intent(this, CaptureService::class.java)
        stopService(intent)
        Toast.makeText(this, "停止捕获数据", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        // 安全注销接收器
        if (receiverRegistered) {
            try {
                unregisterReceiver(statsReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver was not registered", e)
            }
            receiverRegistered = false
        }
        stopCaptureService()
        super.onDestroy()
    }

    private external fun nativeInitParser(): Boolean
}
