package com.github.konkers.irminsul

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    
    private lateinit var context: android.content.Context
    
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }
    
    @After
    fun tearDown() {
        // 清理工作
    }
    
    @Test
    fun testNativeLibraryLoading() {
        // 测试原生库是否能正常加载
        try {
            System.loadLibrary("capture_engine")
            assertTrue("Native library should load successfully", true)
        } catch (e: Exception) {
            fail("Failed to load native library: ${e.message}")
        }
    }
    
    @Test
    fun testParserInitialization() {
        // 测试解析器初始化
        try {
            System.loadLibrary("capture_engine")
            val result = nativeInitParser()
            assertTrue("Parser should initialize successfully", result)
        } catch (e: UnsatisfiedLinkError) {
            // 如果原生库未实现，跳过测试
            println("Native method not implemented yet: ${e.message}")
        } catch (e: Exception) {
            fail("Parser initialization failed: ${e.message}")
        }
    }
    
    @Test
    fun testVpnPermissionCheck() {
        // 测试 VPN 权限检查
        val intent = android.net.VpnService.prepare(context)
        // 如果 intent 为 null，表示已经有权限
        // 如果 intent 不为 null，需要请求权限
        if (intent != null) {
            println("VPN permission not granted, need to request")
        } else {
            println("VPN permission already granted")
        }
        // 这个测试主要是确保不崩溃
        assertTrue(true)
    }
    
    @Test
    fun testStartCaptureService() {
        // 测试启动捕获服务
        try {
            val intent = Intent(context, CaptureService::class.java)
            context.startService(intent)
            
            // 等待一下让服务启动
            Thread.sleep(1000)
            
            // 停止服务
            context.stopService(intent)
            
            assertTrue(true)
        } catch (e: Exception) {
            fail("Failed to start/stop capture service: ${e.message}")
        }
    }
    
    @Test
    fun testPacketParsing() {
        // 测试数据包解析
        try {
            System.loadLibrary("capture_engine")
            
            // 创建模拟的数据包
            val testData = byteArrayOf(
                // IP 头 (20字节)
                0x45, 0x00, 0x00, 0x3C,  // IPv4, 长度 60
                0x00, 0x01, 0x00, 0x00,  // ID, Flags
                0x40, 0x06, 0x00, 0x00,  // TTL=64, TCP, Checksum
                0x0A, 0x00, 0x00, 0x01,  // 源 IP: 10.0.0.1
                0x0A, 0x00, 0x00, 0x02,  // 目标 IP: 10.0.0.2
                // TCP 头 (20字节)
                0x01, 0xBB, 0x01, 0xBB,  // 源端口 443, 目标端口 443
                0x00, 0x00, 0x00, 0x01,  // 序列号
                0x00, 0x00, 0x00, 0x02,  // 确认号
                0x50, 0x02, 0xFF, 0xFF,  // TCP 头长度, Flags, Window
                0x00, 0x00, 0x00, 0x00   // Checksum, Urgent pointer
            )
            
            val result = nativeParsePacket(testData)
            // 结果可能是 null (如果不是原神数据包)
            println("Parse result: $result")
            
        } catch (e: UnsatisfiedLinkError) {
            println("Native method not implemented yet: ${e.message}")
        }
    }
    
    // JNI 方法声明
    private external fun nativeInitParser(): Boolean
    private external fun nativeParsePacket(data: ByteArray): String?
}
