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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.konkers.irminsul.data.GameDatabase
import com.github.konkers.irminsul.ui.theme.IrminsulTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val VERSION = "1.2"
    }

    // 捕获状态
    private var isCapturing by mutableStateOf(false)
    private var captureStats by mutableStateOf(CaptureStatsData())

    // 解析到的游戏数据状态
    private var dataUpdated by mutableStateOf(DataUpdated())

    // 标记接收器是否已注册
    private var receiverRegistered = false

    // === 数据结构 ===
    data class CaptureStatsData(
        val totalPackets: Long = 0,
        val genshinPackets: Long = 0,
        val filteredPackets: Long = 0,
        val bytesCaptured: Long = 0,
        val elapsedSeconds: Double = 0.0
    )

    data class DataUpdated(
        val items: Boolean = false,
        val characters: Boolean = false,
        val achievements: Boolean = false,
    )

    // === VPN 权限启动器 ===
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startCaptureService()
        } else {
            Toast.makeText(this, "需要 VPN 权限才能捕获数据", Toast.LENGTH_SHORT).show()
        }
    }

    // === 广播接收器 ===
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
                CaptureService.ACTION_PACKET_CAPTURED -> {
                    val packetType = intent.getStringExtra("packet_type") ?: "unknown"
                    when (packetType) {
                        "character" -> dataUpdated = dataUpdated.copy(characters = true)
                        "item" -> dataUpdated = dataUpdated.copy(items = true)
                        "achievement" -> dataUpdated = dataUpdated.copy(achievements = true)
                        "weapon" -> dataUpdated = dataUpdated.copy(items = true)
                        "artifact" -> dataUpdated = dataUpdated.copy(items = true)
                    }
                    Log.i(TAG, "Packet parsed: $packetType")
                }
            }
        }
    }

    // === Activity 生命周期 ===
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val filter = IntentFilter().apply {
                addAction(CaptureService.ACTION_CAPTURE_STARTED)
                addAction(CaptureService.ACTION_CAPTURE_STOPPED)
                addAction(CaptureService.ACTION_STATS_UPDATED)
                addAction(CaptureService.ACTION_PACKET_CAPTURED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerReceiver(statsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(statsReceiver, filter)
            }
            receiverRegistered = true

            loadNativeLibraries()

            setContent {
                IrminsulTheme {
                    val viewModel: MainViewModel = viewModel()
                    val loadState by viewModel.loadState.collectAsState(initial = LoadState.Idle)

                    LaunchedEffect(Unit) {
                        viewModel.loadDatabase()
                    }

                    when (val state = loadState) {
                        is LoadState.Idle -> {
                            Box(Modifier.fillMaxSize(), Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        is LoadState.Loading -> {
                            InitializationScreen(progress = 0.1f, status = "正在加载游戏数据...")
                        }
                        is LoadState.Error -> {
                            Column(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("加载数据失败", color = MaterialTheme.colorScheme.error)
                                Text(state.message, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        is LoadState.Success -> {
                            // 数据库加载成功，显示主界面
                            val dbDataUpdated = DataUpdated(
                                characters = state.db.character_map?.isNotEmpty() == true,
                                items = state.db.weapon_map?.isNotEmpty() == true
                                        || state.db.artifact_map?.isNotEmpty() == true,
                                achievements = false
                            )
                            IrminsulMainScreen(
                                isCapturing = isCapturing,
                                stats = captureStats,
                                dataUpdated = dbDataUpdated,
                                onStartCapture = { checkVpnPermissionAndStart() },
                                onStopCapture = { stopCaptureService() }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onCreate crashed", e)
            Toast.makeText(this, "启动崩溃: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadNativeLibraries() {
        try {
            System.loadLibrary("irminsul_android_parser")
            Log.i(TAG, "Rust parser loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Rust parser not loaded (will use demo mode)", e)
        }
        try {
            nativeInitParser()
            Log.i(TAG, "Parser initialized")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Parser init skipped (native not available)", e)
        }
    }

    // === VPN / Service 控制 ===
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

    // === Composable UI ===

    @Composable
    fun InitializationScreen(progress: Float, status: String) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Irminsul", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                Text("Genshin Impact Packet Capture", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(48.dp))
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth(0.6f))
                Spacer(Modifier.height(16.dp))
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    fun IrminsulMainScreen(
        isCapturing: Boolean,
        stats: CaptureStatsData,
        dataUpdated: DataUpdated,
        onStartCapture: () -> Unit,
        onStopCapture: () -> Unit,
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // 深色背景
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF1a1a2e)
            ) {}

            // 主内容
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Irminsul", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text("Genshin Impact Packet Capture", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))

                Spacer(Modifier.height(40.dp))

                // 捕获状态卡片
                Card(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2d2d42).copy(alpha = 0.8f))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isCapturing) "● 正在捕获" else "○ 准备就绪",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isCapturing) Color(0xFF66bb6a) else Color.White.copy(alpha = 0.5f)
                        )

                        if (isCapturing) {
                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                StatItem("总包数", stats.totalPackets.toString())
                                StatItem("原神包", stats.genshinPackets.toString())
                                StatItem("过滤", stats.filteredPackets.toString())
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                StatItem("流量", formatBytes(stats.bytesCaptured))
                                StatItem("时间", formatTime(stats.elapsedSeconds))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // 数据状态卡片
                Card(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2d2d42).copy(alpha = 0.8f))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text("已解析数据", style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.9f))
                        Spacer(Modifier.height(12.dp))
                        DataStateRow(label = "角色", updated = dataUpdated.characters)
                        DataStateRow(label = "武器/圣遗物", updated = dataUpdated.items)
                        DataStateRow(label = "成就", updated = dataUpdated.achievements)
                    }
                }

                Spacer(Modifier.height(32.dp))

                Button(
                    onClick = if (isCapturing) onStopCapture else onStartCapture,
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text(if (isCapturing) "停止捕获" else "开始捕获")
                }
            }

            // 右下角版本号
            Text(
                text = VERSION,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            )
        }
    }

    @Composable
    fun StatItem(label: String, value: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
        }
    }

    @Composable
    fun DataStateRow(label: String, updated: Boolean) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (updated) "✓" else "○",
                color = if (updated) Color(0xFF66bb6a) else Color.White.copy(alpha = 0.3f),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                color = if (updated) Color.White else Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodyMedium
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
}
