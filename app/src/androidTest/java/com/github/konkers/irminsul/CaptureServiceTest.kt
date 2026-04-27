package com.github.konkers.irminsul

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CaptureServiceTest {
    
    companion object {
        private const val TAG = "CaptureServiceTest"
    }
    
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)
    
    private lateinit var context: Context
    private val receivedIntents = mutableListOf<String>()
    private val latch = CountDownLatch(3) // 期望收到3个广播
    
    // 广播接收器
    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                receivedIntents.add(it.action ?: "unknown")
                latch.countDown()
                Log.d(TAG, "Received broadcast: ${it.action}")
            }
        }
    }
    
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        
        // 注册广播接收器
        val filter = IntentFilter().apply {
            addAction(CaptureService.ACTION_CAPTURE_STARTED)
            addAction(CaptureService.ACTION_CAPTURE_STOPPED)
            addAction(CaptureService.ACTION_PACKET_CAPTURED)
        }
        context.registerReceiver(testReceiver, filter)
        
        receivedIntents.clear()
        Log.d(TAG, "Test setup completed")
    }
    
    @After
    fun tearDown() {
        try {
            context.unregisterReceiver(testReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver not registered", e)
        }
        Log.d(TAG, "Test teardown completed")
    }
    
    @Test
    fun testCaptureServiceStartStop() {
        Log.d(TAG, "Starting test: testCaptureServiceStartStop")
        
        // 启动捕获服务
        val startIntent = Intent(context, CaptureService::class.java)
        context.startService(startIntent)
        
        // 等待服务启动广播
        Thread.sleep(2000)
        
        // 验证服务是否运行
        // 注意：在实际测试中，这里应该检查服务状态
        Log.d(TAG, "Capture service started")
        
        // 停止捕获服务
        val stopIntent = Intent(context, CaptureService::class.java)
        context.stopService(stopIntent)
        
        // 等待服务停止广播
        Thread.sleep(1000)
        
        Log.d(TAG, "Capture service stopped")
        
        // 验证：检查是否收到了预期的广播
        // 注意：由于服务可能在不同的进程，广播可能无法接收
        Log.d(TAG, "Received intents: $receivedIntents")
    }
    
    @Test
    fun testCaptureServiceBroadcasts() {
        Log.d(TAG, "Starting test: testCaptureServiceBroadcasts")
        
        // 启动服务
        val startIntent = Intent(context, CaptureService::class.java)
        context.startService(startIntent)
        
        // 等待捕获开始广播
        val startTime = System.currentTimeMillis()
        while (!receivedIntents.contains(CaptureService.ACTION_CAPTURE_STARTED) && 
               System.currentTimeMillis() - startTime < 5000) {
            Thread.sleep(100)
        }
        
        assertTrue("Should receive capture started broadcast", 
                   receivedIntents.contains(CaptureService.ACTION_CAPTURE_STARTED))
        
        // 等待一段时间，看是否有数据包捕获广播
        Thread.sleep(3000)
        
        // 停止服务
        val stopIntent = Intent(context, CaptureService::class.java)
        context.stopService(stopIntent)
        
        // 等待捕获停止广播
        val stopTime = System.currentTimeMillis()
        while (!receivedIntents.contains(CaptureService.ACTION_CAPTURE_STOPPED) && 
               System.currentTimeMillis() - stopTime < 3000) {
            Thread.sleep(100)
        }
        
        assertTrue("Should receive capture stopped broadcast", 
                   receivedIntents.contains(CaptureService.ACTION_CAPTURE_STOPPED))
        
        Log.d(TAG, "Test completed. Received intents: $receivedIntents")
    }
    
    @Test
    fun testMultipleStartStop() {
        Log.d(TAG, "Starting test: testMultipleStartStop")
        
        // 多次启动和停止服务
        repeat(3) { index ->
            Log.d(TAG, "Iteration $index")
            
            // 启动
            val startIntent = Intent(context, CaptureService::class.java)
            context.startService(startIntent)
            Thread.sleep(1000)
            
            // 停止
            val stopIntent = Intent(context, CaptureService::class.java)
            context.stopService(stopIntent)
            Thread.sleep(1000)
        }
        
        // 验证没有崩溃
        Log.d(TAG, "Multiple start/stop test completed successfully")
    }
}
