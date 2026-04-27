#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netinet/ip.h>
#include <netinet/tcp.h>
#include <netinet/udp.h>
#include <linux/ip.h>
#include <linux/tcp.h>
#include <linux/udp.h>

#define LOG_TAG "CaptureEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// 全局变量
static int vpn_fd = -1;
static bool is_capturing = false;
static JavaVM* g_vm = nullptr;
static jobject g_obj = nullptr;

// 数据包类型定义
#define PROTOCOL_TCP 6
#define PROTOCOL_UDP 17

// 数据包结构体
struct PacketInfo {
    uint32_t src_ip;
    uint32_t dst_ip;
    uint16_t src_port;
    uint16_t dst_port;
    uint8_t protocol;
    uint8_t* payload;
    size_t payload_len;
};

// JNI 初始化
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    
    g_vm = vm;
    LOGI("Capture engine loaded");
    return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGI("Capture engine unloaded");
}

// 解析 IP 数据包
bool parse_ip_packet(const uint8_t* data, size_t len, PacketInfo* pkt) {
    if (len < sizeof(struct iphdr)) {
        return false;
    }
    
    struct iphdr* ip_hdr = (struct iphdr*)data;
    pkt->src_ip = ip_hdr->saddr;
    pkt->dst_ip = ip_hdr->daddr;
    pkt->protocol = ip_hdr->protocol;
    
    size_t ip_hdr_len = ip_hdr->ihl * 4;
    if (len < ip_hdr_len) {
        return false;
    }
    
    const uint8_t* transport_hdr = data + ip_hdr_len;
    size_t transport_len = len - ip_hdr_len;
    
    if (ip_hdr->protocol == PROTOCOL_TCP) {
        if (transport_len < sizeof(struct tcphdr)) {
            return false;
        }
        struct tcphdr* tcp_hdr = (struct tcphdr*)transport_hdr;
        pkt->src_port = ntohs(tcp_hdr->source);
        pkt->dst_port = ntohs(tcp_hdr->dest);
        
        size_t tcp_hdr_len = tcp_hdr->doff * 4;
        if (transport_len > tcp_hdr_len) {
            pkt->payload = (uint8_t*)(transport_hdr + tcp_hdr_len);
            pkt->payload_len = transport_len - tcp_hdr_len;
        } else {
            pkt->payload = nullptr;
            pkt->payload_len = 0;
        }
        
        return true;
    } else if (ip_hdr->protocol == PROTOCOL_UDP) {
        if (transport_len < sizeof(struct udphdr)) {
            return false;
        }
        struct udphdr* udp_hdr = (struct udphdr*)transport_hdr;
        pkt->src_port = ntohs(udp_hdr->source);
        pkt->dst_port = ntohs(udp_hdr->dest);
        
        if (transport_len > sizeof(struct udphdr)) {
            pkt->payload = (uint8_t*)(transport_hdr + sizeof(struct udphdr));
            pkt->payload_len = transport_len - sizeof(struct udphdr);
        } else {
            pkt->payload = nullptr;
            pkt->payload_len = 0;
        }
        
        return true;
    }
    
    return false;
}

// 调用 Java 层的解析方法
bool call_java_parser(JNIEnv* env, jobject obj, const uint8_t* data, size_t len) {
    // 获取 Java 类
    jclass cls = env->GetObjectClass(obj);
    if (cls == nullptr) {
        LOGE("Failed to get object class");
        return false;
    }
    
    // 获取方法 ID
    jmethodID method = env->GetMethodID(cls, "nativeOnPacketCaptured", "([B)Z");
    if (method == nullptr) {
        LOGE("Failed to get method ID");
        env->DeleteLocalRef(cls);
        return false;
    }
    
    // 创建字节数组
    jbyteArray jdata = env->NewByteArray(len);
    if (jdata == nullptr) {
        LOGE("Failed to create byte array");
        env->DeleteLocalRef(cls);
        return false;
    }
    
    // 复制数据
    env->SetByteArrayRegion(jdata, 0, len, (jbyte*)data);
    
    // 调用 Java 方法
    jboolean result = env->CallBooleanMethod(obj, method, jdata);
    
    // 清理
    env->DeleteLocalRef(jdata);
    env->DeleteLocalRef(cls);
    
    return result == JNI_TRUE;
}

// 捕获线程函数
void* capture_thread(void* arg) {
    JNIEnv* env = nullptr;
    g_vm->AttachCurrentThread(&env, nullptr);
    
    jobject obj = env->NewGlobalRef(g_obj);
    
    LOGI("Capture thread started");
    
    while (is_capturing && vpn_fd >= 0) {
        // 从 VPN 读取数据包
        uint8_t buffer[4096];
        ssize_t len = read(vpn_fd, buffer, sizeof(buffer));
        
        if (len <= 0) {
            if (len < 0) {
                LOGE("Read error: %s", strerror(errno));
            }
            usleep(1000); // 1ms
            continue;
        }
        
        // 解析数据包
        PacketInfo pkt;
        if (parse_ip_packet(buffer, len, &pkt)) {
            // 调用 Java 层处理
            call_java_parser(env, obj, buffer, len);
        }
    }
    
    env->DeleteGlobalRef(obj);
    g_vm->DetachCurrentThread();
    
    LOGI("Capture thread stopped");
    return nullptr;
}

// JNI 方法：启动捕获
extern "C" JNIEXPORT jboolean JNICALL Java_com_github_konkers_irminsul_CaptureService_nativeStartCapture(
    JNIEnv* env,
    jobject obj,
    jint fd
) {
    LOGI("Starting capture with fd: %d", fd);
    
    if (is_capturing) {
        LOGI("Capture already running");
        return JNI_TRUE;
    }
    
    vpn_fd = fd;
    is_capturing = true;
    
    // 保存全局引用
    g_obj = env->NewGlobalRef(obj);
    
    // 创建捕获线程
    pthread_t thread;
    if (pthread_create(&thread, nullptr, capture_thread, nullptr) != 0) {
        LOGE("Failed to create capture thread");
        is_capturing = false;
        vpn_fd = -1;
        env->DeleteGlobalRef(g_obj);
        g_obj = nullptr;
        return JNI_FALSE;
    }
    
    pthread_detach(thread);
    
    LOGI("Capture started successfully");
    return JNI_TRUE;
}

// JNI 方法：停止捕获
extern "C" JNIEXPORT jboolean JNICALL Java_com_github_konkers_irminsul_CaptureService_nativeStopCapture(
    JNIEnv* env,
    jobject obj
) {
    LOGI("Stopping capture");
    
    is_capturing = false;
    
    if (vpn_fd >= 0) {
        close(vpn_fd);
        vpn_fd = -1;
    }
    
    if (g_obj != nullptr) {
        env->DeleteGlobalRef(g_obj);
        g_obj = nullptr;
    }
    
    LOGI("Capture stopped");
    return JNI_TRUE;
}

// JNI 方法：获取捕获状态
extern "C" JNIEXPORT jboolean JNICALL Java_com_github_konkers_irminsul_CaptureService_nativeIsCapturing(
    JNIEnv* env,
    jobject obj
) {
    return is_capturing ? JNI_TRUE : JNI_FALSE;
}

// JNI 方法：解析数据包（供 Java 层调用）
extern "C" JNIEXPORT jstring JNICALL Java_com_github_konkers_irminsul_CaptureService_nativeParsePacket(
    JNIEnv* env,
    jobject obj,
    jbyteArray data
) {
    // 获取数据
    jsize len = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    
    if (bytes == nullptr || len <= 0) {
        return env->NewStringUTF("");
    }
    
    // TODO: 调用 auto-artifactarium 进行解析
    // 这里需要链接 auto-artifactarium 库
    
    // 临时返回空 JSON
    std::string result = "{}";
    
    // 释放数据
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    
    return env->NewStringUTF(result.c_str());
}

// JNI 方法：初始化解析器
extern "C" JNIEXPORT jboolean JNICALL Java_com_github_konkers_irminsul_MainActivity_nativeInitParser(
    JNIEnv* env,
    jobject obj
) {
    LOGI("Initializing parser");
    
    // TODO: 初始化 auto-artifactarium
    
    LOGI("Parser initialized");
    return JNI_TRUE;
}
