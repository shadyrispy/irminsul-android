package com.github.konkers.irminsul

import android.content.Intent
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.github.konkers.irminsul.ui.theme.IrminsulTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    // 捕获状态
    private var isCapturing by mutableStateOf(false)
    
    // VPN 权限请求
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startCaptureService()
        } else {
            Toast.makeText(this, "需要 VPN 权限才能捕获数据", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
                    onStartCapture = { checkVpnPermissionAndStart() },
                    onStopCapture = { stopCaptureService() }
                )
            }
        }
    }
    
    @Composable
    fun MainScreen(
        isCapturing: Boolean,
        onStartCapture: () -> Unit,
        onStopCapture: () -> Unit
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 标题
                Text(
                    text = "irminsul-android",
                    style = MaterialTheme.typography.headlineLarge
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // 状态指示
                Card(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isCapturing) "正在捕获数据..." else "准备就绪",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = if (isCapturing) "请在游戏中进行操作" else "点击开始按钮启动捕获",
                            style = MaterialTheme.typography.bodyMedium
                        )
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
                
                // 查看数据按钮
                OutlinedButton(
                    onClick = {
                        // TODO: 跳转到数据列表界面
                        Toast.makeText(this@MainActivity, "数据查看功能开发中", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text("查看捕获的数据")
                }
            }
        }
    }
    
    private fun checkVpnPermissionAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // 需要请求 VPN 权限
            vpnPermissionLauncher.launch(intent)
        } else {
            // 已经有权限
            startCaptureService()
        }
    }
    
    private fun startCaptureService() {
        Log.i(TAG, "Starting capture service")
        isCapturing = true
        
        val intent = Intent(this, CaptureService::class.java)
        startForegroundService(intent)
        
        Toast.makeText(this, "开始捕获数据", Toast.LENGTH_SHORT).show()
    }
    
    private fun stopCaptureService() {
        Log.i(TAG, "Stopping capture service")
        isCapturing = false
        
        val intent = Intent(this, CaptureService::class.java)
        stopService(intent)
        
        Toast.makeText(this, "停止捕获数据", Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopCaptureService()
    }
    
    // JNI 方法
    private external fun nativeInitParser(): Boolean
}
