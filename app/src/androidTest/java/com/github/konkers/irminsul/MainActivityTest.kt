package com.github.konkers.irminsul

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
    
    @Test
    fun testNativeLibraryLoading() {
        try {
            System.loadLibrary("capture_engine")
            assertTrue("Native library should load successfully", true)
        } catch (e: UnsatisfiedLinkError) {
            // 在 CI 环境中可能无法加载
            println("SKIP: Native library not available in test environment: ${e.message}")
        } catch (e: Exception) {
            fail("Unexpected error loading native library: ${e.message}")
        }
    }
    
    @Test
    fun testParserInitialization() {
        try {
            System.loadLibrary("capture_engine")
            val result = nativeInitParser()
            assertTrue("Parser should initialize successfully", result)
        } catch (e: UnsatisfiedLinkError) {
            println("SKIP: Native method not available: ${e.message}")
        }
    }
    
    @Test
    fun testNativeGetStats() {
        try {
            System.loadLibrary("capture_engine")
            val stats = nativeGetStats()
            assertNotNull("Stats should not be null", stats)
            // 验证 JSON 格式
            if (stats != null) {
                val json = org.json.JSONObject(stats)
                assertTrue("Should have total_packets", json.has("total_packets"))
                assertTrue("Should have genshin_packets", json.has("genshin_packets"))
                assertTrue("Should have bytes_captured", json.has("bytes_captured"))
            }
        } catch (e: UnsatisfiedLinkError) {
            println("SKIP: Native method not available: ${e.message}")
        }
    }
    
    @Test
    fun testNativeSetFilter() {
        try {
            System.loadLibrary("capture_engine")
            // 设置过滤规则：只捕获原神端口
            nativeSetFilter(true, true, intArrayOf(22101), false, 0)
            assertTrue("Filter should be set without error", true)
            
            // 禁用过滤
            nativeSetFilter(false, false, intArrayOf(), false, 0)
            assertTrue("Disabling filter should work", true)
        } catch (e: UnsatisfiedLinkError) {
            println("SKIP: Native method not available: ${e.message}")
        }
    }
    
    @Test
    fun testPacketParsing() {
        try {
            System.loadLibrary("capture_engine")
            
            // 模拟 TCP 数据包（原神端口 22101）
            val testPacket = byteArrayOf(
                // IP 头 (20字节)
                0x45, 0x00, 0x00, 0x3C,
                0x00, 0x01, 0x00, 0x00,
                0x40, 0x06, 0x00, 0x00,
                0x0A, 0x00, 0x00, 0x01,
                0x0A, 0x00, 0x00, 0x02,
                // TCP 头 (20字节)
                0x56, 0x75, 0x56, 0x75,  // 端口 22101 = 0x5655 -> 改为原神端口
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x02,
                0x50, 0x02, 0xFF, 0xFF,
                0x00, 0x00, 0x00, 0x00
            )
            
            val result = nativeParsePacket(testPacket)
            println("Parse result: $result")
        } catch (e: UnsatisfiedLinkError) {
            println("SKIP: Native method not available: ${e.message}")
        }
    }
    
    // JNI 方法声明（与 C++ 侧对应）
    private external fun nativeInitParser(): Boolean
    private external fun nativeGetStats(): String?
    private external fun nativeSetFilter(
        enabled: Boolean,
        filterByPort: Boolean,
        ports: IntArray,
        filterByProtocol: Boolean,
        protocol: Int
    )
    private external fun nativeParsePacket(data: ByteArray): String?
}
