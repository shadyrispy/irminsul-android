#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <vector>
#include <chrono>
#include <deque>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netinet/ip.h>
#include <netinet/tcp.h>
#include <netinet/udp.h>

#define LOG_TAG "CaptureEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// ──────────────────────────────────────────────
// 常量定义
// ──────────────────────────────────────────────
static constexpr size_t RING_BUFFER_SIZE     = 256 * 1024;  // 256KB 环形缓冲区
static constexpr size_t MAX_PACKET_SIZE      = 64 * 1024;   // 单个包最大 64KB
static constexpr size_t BATCH_SIZE           = 32;           // 每批最多 32 个包
static constexpr int    EPOLL_TIMEOUT_MS     = 100;          // epoll 超时 100ms
static constexpr int    FLUSH_INTERVAL_MS    = 50;           // 批量刷新间隔 50ms
static constexpr int    EPOLL_MAX_EVENTS     = 64;

// 原神相关端口 & IP（可运行时配置）
static constexpr uint16_t GENSHIN_DEFAULT_PORT = 22101;

// ──────────────────────────────────────────────
// 数据包结构体
// ──────────────────────────────────────────────
#define PROTOCOL_TCP 6
#define PROTOCOL_UDP 17

struct PacketInfo {
    uint32_t src_ip;
    uint32_t dst_ip;
    uint16_t src_port;
    uint16_t dst_port;
    uint8_t  protocol;
    const uint8_t* payload;
    size_t   payload_len;
};

// ──────────────────────────────────────────────
// 捕获统计
// ──────────────────────────────────────────────
struct CaptureStats {
    std::atomic<uint64_t> total_packets{0};
    std::atomic<uint64_t> filtered_packets{0};
    std::atomic<uint64_t> tcp_packets{0};
    std::atomic<uint64_t> udp_packets{0};
    std::atomic<uint64_t> genshin_packets{0};
    std::atomic<uint64_t> bytes_captured{0};
    std::chrono::steady_clock::time_point start_time;

    void reset() {
        total_packets.store(0, std::memory_order_relaxed);
        filtered_packets.store(0, std::memory_order_relaxed);
        tcp_packets.store(0, std::memory_order_relaxed);
        udp_packets.store(0, std::memory_order_relaxed);
        genshin_packets.store(0, std::memory_order_relaxed);
        bytes_captured.store(0, std::memory_order_relaxed);
        start_time = std::chrono::steady_clock::now();
    }

    double elapsed_seconds() const {
        auto now = std::chrono::steady_clock::now();
        return std::chrono::duration<double>(now - start_time).count();
    }
};

// ──────────────────────────────────────────────
// 过滤规则
// ──────────────────────────────────────────────
struct FilterRule {
    bool filter_enabled = true;            // 是否启用过滤
    bool filter_by_port = true;            // 按端口过滤
    std::vector<uint16_t> target_ports;    // 目标端口白名单
    bool filter_by_protocol = false;       // 按协议过滤
    uint8_t allowed_protocol = PROTOCOL_TCP;

    FilterRule() {
        target_ports.push_back(GENSHIN_DEFAULT_PORT);
    }
};

// ──────────────────────────────────────────────
// 环形缓冲区
// ──────────────────────────────────────────────
class RingBuffer {
public:
    explicit RingBuffer(size_t capacity)
        : buffer_(capacity), capacity_(capacity), head_(0), tail_(0) {}

    // 写入数据，返回实际写入字节数
    size_t write(const uint8_t* data, size_t len) {
        std::lock_guard<std::mutex> lock(write_mutex_);
        size_t available = writable();
        size_t to_write = (len < available) ? len : available;
        if (to_write == 0) return 0;

        size_t first = std::min(to_write, capacity_ - tail_);
        std::memcpy(buffer_.data() + tail_, data, first);
        if (to_write > first) {
            std::memcpy(buffer_.data(), data + first, to_write - first);
        }
        tail_ = (tail_ + to_write) % capacity_;
        return to_write;
    }

    // 读取数据，返回实际读取字节数
    size_t read(uint8_t* data, size_t len) {
        size_t available = readable();
        size_t to_read = (len < available) ? len : available;
        if (to_read == 0) return 0;

        size_t first = std::min(to_read, capacity_ - head_);
        std::memcpy(data, buffer_.data() + head_, first);
        if (to_read > first) {
            std::memcpy(data + first, buffer_.data(), to_read - first);
        }
        head_ = (head_ + to_read) % capacity_;
        return to_read;
    }

    size_t readable() const {
        size_t h = head_, t = tail_;
        return (t >= h) ? (t - h) : (capacity_ - h + t);
    }

    size_t writable() const {
        return capacity_ - readable() - 1;
    }

    void clear() {
        head_ = 0;
        tail_ = 0;
    }

private:
    std::vector<uint8_t> buffer_;
    size_t capacity_;
    size_t head_;
    size_t tail_;
    std::mutex write_mutex_;
};

// ──────────────────────────────────────────────
// 全局状态
// ──────────────────────────────────────────────
static int              g_vpn_fd = -1;
static std::atomic<bool> g_capturing{false};
static JavaVM*          g_vm = nullptr;
static jobject          g_obj = nullptr;
static CaptureStats     g_stats;
static FilterRule       g_filter;
static std::mutex       g_filter_mutex;
static std::mutex       g_state_mutex;
static std::thread      g_capture_thread;

// JNI 方法 ID 缓存（避免每次查找）
static jmethodID g_onPacketsMethod = nullptr;
static jmethodID g_onPacketMethod  = nullptr;

// ──────────────────────────────────────────────
// IP 数据包解析
// ──────────────────────────────────────────────
static bool parse_ip_packet(const uint8_t* data, size_t len, PacketInfo* pkt) {
    if (len < sizeof(struct iphdr)) return false;

    const struct iphdr* ip_hdr = reinterpret_cast<const struct iphdr*>(data);

    // IPv4 校验
    if (ip_hdr->version != 4) return false;

    size_t ip_hdr_len = ip_hdr->ihl * 4;
    if (ip_hdr_len < sizeof(struct iphdr) || len < ip_hdr_len) return false;

    pkt->src_ip   = ip_hdr->saddr;
    pkt->dst_ip   = ip_hdr->daddr;
    pkt->protocol = ip_hdr->protocol;

    const uint8_t* transport = data + ip_hdr_len;
    size_t transport_len = len - ip_hdr_len;

    if (ip_hdr->protocol == PROTOCOL_TCP) {
        if (transport_len < sizeof(struct tcphdr)) return false;
        const struct tcphdr* tcp = reinterpret_cast<const struct tcphdr*>(transport);
        pkt->src_port = ntohs(tcp->source);
        pkt->dst_port = ntohs(tcp->dest);
        size_t tcp_hdr_len = tcp->doff * 4;
        if (transport_len > tcp_hdr_len) {
            pkt->payload     = transport + tcp_hdr_len;
            pkt->payload_len = transport_len - tcp_hdr_len;
        } else {
            pkt->payload = nullptr; pkt->payload_len = 0;
        }
        return true;
    }

    if (ip_hdr->protocol == PROTOCOL_UDP) {
        if (transport_len < sizeof(struct udphdr)) return false;
        const struct udphdr* udp = reinterpret_cast<const struct udphdr*>(transport);
        pkt->src_port = ntohs(udp->source);
        pkt->dst_port = ntohs(udp->dest);
        if (transport_len > sizeof(struct udphdr)) {
            pkt->payload     = transport + sizeof(struct udphdr);
            pkt->payload_len = transport_len - sizeof(struct udphdr);
        } else {
            pkt->payload = nullptr; pkt->payload_len = 0;
        }
        return true;
    }

    return false;
}

// ──────────────────────────────────────────────
// 过滤逻辑：判断是否为原神相关流量
// ──────────────────────────────────────────────
static bool is_genshin_packet(const PacketInfo* pkt) {
    std::lock_guard<std::mutex> lock(g_filter_mutex);
    if (!g_filter.filter_enabled) return true;    // 不过滤 = 全放行

    bool port_match = false;
    if (g_filter.filter_by_port) {
        for (uint16_t port : g_filter.target_ports) {
            if (pkt->src_port == port || pkt->dst_port == port) {
                port_match = true;
                break;
            }
        }
    } else {
        port_match = true; // 不按端口过滤时视为匹配
    }

    bool proto_match = true;
    if (g_filter.filter_by_protocol) {
        proto_match = (pkt->protocol == g_filter.allowed_protocol);
    }

    return port_match && proto_match;
}

// ──────────────────────────────────────────────
// 批量回调到 Java 层
// ──────────────────────────────────────────────
static void flush_batch(JNIEnv* env, jobject obj,
                        std::vector<std::vector<uint8_t>>& batch) {
    if (batch.empty()) return;

    jclass cls = env->GetObjectClass(obj);
    if (!cls) { LOGE("flush_batch: failed to get class"); return; }

    // 优先使用批量回调 nativeOnPacketsCaptured([[B)V
    jmethodID method = g_onPacketsMethod;
    if (!method) {
        method = env->GetMethodID(cls, "nativeOnPacketsCaptured", "([[B)V");
        if (method) g_onPacketsMethod = method;
    }

    if (method) {
        // 批量回调
        jobjectArray arr = env->NewObjectArray(batch.size(),
                            env->FindClass("[B"), nullptr);
        if (arr) {
            for (size_t i = 0; i < batch.size(); i++) {
                jbyteArray jdata = env->NewByteArray(batch[i].size());
                if (jdata) {
                    env->SetByteArrayRegion(jdata, 0, batch[i].size(),
                                            reinterpret_cast<const jbyte*>(batch[i].data()));
                    env->SetObjectArrayElement(arr, i, jdata);
                    env->DeleteLocalRef(jdata);
                }
            }
            env->CallVoidMethod(obj, method, arr);
            env->DeleteLocalRef(arr);
        }
    } else {
        // 回退到单包回调 nativeOnPacketCaptured([B)Z
        jmethodID single = g_onPacketMethod;
        if (!single) {
            single = env->GetMethodID(cls, "nativeOnPacketCaptured", "([B)Z");
            if (single) g_onPacketMethod = single;
        }
        if (single) {
            for (auto& pkt : batch) {
                jbyteArray jdata = env->NewByteArray(pkt.size());
                if (jdata) {
                    env->SetByteArrayRegion(jdata, 0, pkt.size(),
                                            reinterpret_cast<const jbyte*>(pkt.data()));
                    env->CallBooleanMethod(obj, single, jdata);
                    env->DeleteLocalRef(jdata);
                }
            }
        } else {
            LOGE("No callback method found");
        }
    }

    env->DeleteLocalRef(cls);
    batch.clear();
}

// ──────────────────────────────────────────────
// 捕获线程（epoll 驱动 + 批量回调）
// ──────────────────────────────────────────────
static void capture_loop() {
    JNIEnv* env = nullptr;
    g_vm->AttachCurrentThread(&env, nullptr);
    jobject obj = env->NewGlobalRef(g_obj);

    LOGI("Capture thread started, fd=%d", g_vpn_fd.load());

    // 创建 epoll
    int epfd = epoll_create1(0);
    if (epfd < 0) {
        LOGE("epoll_create1 failed: %s", strerror(errno));
        goto cleanup;
    }

    {
        struct epoll_event ev;
        ev.events  = EPOLLIN;
        ev.data.fd = g_vpn_fd.load();
        if (epoll_ctl(epfd, EPOLL_CTL_ADD, g_vpn_fd.load(), &ev) < 0) {
            LOGE("epoll_ctl ADD failed: %s", strerror(errno));
            close(epfd);
            goto cleanup;
        }
    }

    {
        std::vector<std::vector<uint8_t>> batch;
        batch.reserve(BATCH_SIZE);
        uint8_t packet_buf[MAX_PACKET_SIZE];
        struct epoll_event events[EPOLL_MAX_EVENTS];

        auto last_flush = std::chrono::steady_clock::now();

        while (g_capturing.load(std::memory_order_acquire)) {
            int nfds = epoll_wait(epfd, events, EPOLL_MAX_EVENTS, EPOLL_TIMEOUT_MS);
            if (nfds < 0) {
                if (errno == EINTR) continue;
                LOGE("epoll_wait error: %s", strerror(errno));
                break;
            }

            for (int i = 0; i < nfds; i++) {
                if (!(events[i].events & EPOLLIN)) continue;

                // 从 VPN 接口读取数据包
                while (true) {
                    ssize_t len = read(g_vpn_fd.load(), packet_buf, MAX_PACKET_SIZE);
                    if (len <= 0) {
                        if (len < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
                            LOGE("read error: %s", strerror(errno));
                        }
                        break;
                    }

                    g_stats.total_packets.fetch_add(1, std::memory_order_relaxed);
                    g_stats.bytes_captured.fetch_add(len, std::memory_order_relaxed);

                    // 解析 IP 数据包
                    PacketInfo pkt;
                    if (!parse_ip_packet(packet_buf, len, &pkt)) {
                        continue;
                    }

                    // 统计协议分布
                    if (pkt.protocol == PROTOCOL_TCP) {
                        g_stats.tcp_packets.fetch_add(1, std::memory_order_relaxed);
                    } else if (pkt.protocol == PROTOCOL_UDP) {
                        g_stats.udp_packets.fetch_add(1, std::memory_order_relaxed);
                    }

                    // 过滤
                    if (is_genshin_packet(&pkt)) {
                        g_stats.genshin_packets.fetch_add(1, std::memory_order_relaxed);

                        // 入批次
                        batch.emplace_back(packet_buf, packet_buf + len);
                    } else {
                        g_stats.filtered_packets.fetch_add(1, std::memory_order_relaxed);
                    }

                    // 批次满，立即刷新
                    if (batch.size() >= BATCH_SIZE) {
                        flush_batch(env, obj, batch);
                    }
                }
            }

            // 定时刷新：避免低流量时数据积压
            auto now = std::chrono::steady_clock::now();
            if (!batch.empty() &&
                std::chrono::duration_cast<std::chrono::milliseconds>(now - last_flush).count()
                    >= FLUSH_INTERVAL_MS) {
                flush_batch(env, obj, batch);
                last_flush = now;
            }
        }

        // 退出前刷剩余
        if (!batch.empty()) {
            flush_batch(env, obj, batch);
        }
    }

    close(epfd);

cleanup:
    env->DeleteGlobalRef(obj);
    g_vm->DetachCurrentThread();
    LOGI("Capture thread stopped. Total=%lu Genshin=%lu Filtered=%lu",
         g_stats.total_packets.load(), g_stats.genshin_packets.load(),
         g_stats.filtered_packets.load());
}

// ──────────────────────────────────────────────
// JNI 生命周期
// ──────────────────────────────────────────────
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    g_vm = vm;
    LOGI("Capture engine v2 loaded (epoll + ring-buffer + batch)");
    return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGI("Capture engine unloaded");
}

// ──────────────────────────────────────────────
// JNI: 启动捕获
// ──────────────────────────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_konkers_irminsul_CaptureService_nativeStartCapture(
    JNIEnv* env, jobject obj, jint fd)
{
    std::lock_guard<std::mutex> lock(g_state_mutex);

    if (g_capturing.load(std::memory_order_acquire)) {
        LOGW("Capture already running");
        return JNI_TRUE;
    }

    LOGI("Starting capture, fd=%d", fd);

    g_vpn_fd.store(fd);
    g_capturing.store(true, std::memory_order_release);
    g_stats.reset();
    g_onPacketsMethod = nullptr;
    g_onPacketMethod  = nullptr;

    g_obj = env->NewGlobalRef(obj);

    try {
        g_capture_thread = std::thread(capture_loop);
        g_capture_thread.detach();
    } catch (const std::exception& e) {
        LOGE("Failed to create capture thread: %s", e.what());
        g_capturing.store(false, std::memory_order_release);
        g_vpn_fd.store(-1);
        env->DeleteGlobalRef(g_obj);
        g_obj = nullptr;
        return JNI_FALSE;
    }

    LOGI("Capture started successfully");
    return JNI_TRUE;
}

// ──────────────────────────────────────────────
// JNI: 停止捕获
// ──────────────────────────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_konkers_irminsul_CaptureService_nativeStopCapture(
    JNIEnv* env, jobject obj)
{
    std::lock_guard<std::mutex> lock(g_state_mutex);

    LOGI("Stopping capture");
    g_capturing.store(false, std::memory_order_release);

    if (g_vpn_fd.load() >= 0) {
        close(g_vpn_fd.load());
        g_vpn_fd.store(-1);
    }

    if (g_obj) {
        env->DeleteGlobalRef(g_obj);
        g_obj = nullptr;
    }

    LOGI("Capture stopped. Stats: total=%lu genshin=%lu filtered=%lu bytes=%lu",
         g_stats.total_packets.load(), g_stats.genshin_packets.load(),
         g_stats.filtered_packets.load(), g_stats.bytes_captured.load());
    return JNI_TRUE;
}

// ──────────────────────────────────────────────
// JNI: 获取捕获状态
// ──────────────────────────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_konkers_irminsul_CaptureService_nativeIsCapturing(
    JNIEnv* env, jobject obj)
{
    return g_capturing.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

// ──────────────────────────────────────────────
// JNI: 获取捕获统计
// ──────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_github_konkers_irminsul_CaptureService_nativeGetStats(
    JNIEnv* env, jobject obj)
{
    char buf[512];
    snprintf(buf, sizeof(buf),
        "{\"total_packets\":%lu,"
         "\"filtered_packets\":%lu,"
         "\"tcp_packets\":%lu,"
         "\"udp_packets\":%lu,"
         "\"genshin_packets\":%lu,"
         "\"bytes_captured\":%lu,"
         "\"elapsed_seconds\":%.2f}",
        g_stats.total_packets.load(std::memory_order_relaxed),
        g_stats.filtered_packets.load(std::memory_order_relaxed),
        g_stats.tcp_packets.load(std::memory_order_relaxed),
        g_stats.udp_packets.load(std::memory_order_relaxed),
        g_stats.genshin_packets.load(std::memory_order_relaxed),
        g_stats.bytes_captured.load(std::memory_order_relaxed),
        g_stats.elapsed_seconds());

    return env->NewStringUTF(buf);
}

// ──────────────────────────────────────────────
// JNI: 设置过滤规则
// ──────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_github_konkers_irminsul_CaptureService_nativeSetFilter(
    JNIEnv* env, jobject obj,
    jboolean enabled,
    jboolean filterByPort,
    jintArray ports,
    jboolean filterByProtocol,
    jint protocol)
{
    std::lock_guard<std::mutex> lock(g_filter_mutex);

    g_filter.filter_enabled   = (enabled == JNI_TRUE);
    g_filter.filter_by_port   = (filterByPort == JNI_TRUE);
    g_filter.filter_by_protocol = (filterByProtocol == JNI_TRUE);
    g_filter.allowed_protocol = static_cast<uint8_t>(protocol);

    g_filter.target_ports.clear();
    if (ports) {
        jsize n = env->GetArrayLength(ports);
        jint* arr = env->GetIntArrayElements(ports, nullptr);
        if (arr) {
            for (jsize i = 0; i < n; i++) {
                g_filter.target_ports.push_back(static_cast<uint16_t>(arr[i]));
            }
            env->ReleaseIntArrayElements(ports, arr, JNI_ABORT);
        }
    }

    // 如果没指定端口，使用默认原神端口
    if (g_filter.target_ports.empty() && g_filter.filter_by_port) {
        g_filter.target_ports.push_back(GENSHIN_DEFAULT_PORT);
    }

    LOGI("Filter updated: enabled=%d byPort=%d(%zu ports) byProto=%d(%d)",
         g_filter.filter_enabled, g_filter.filter_by_port,
         g_filter.target_ports.size(),
         g_filter.filter_by_protocol, g_filter.allowed_protocol);
}

// ──────────────────────────────────────────────
// JNI: 解析数据包（供 Java 层直接调用）
// ──────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_github_konkers_irminsul_CaptureService_nativeParsePacket(
    JNIEnv* env, jobject obj, jbyteArray data)
{
    jsize len = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);

    if (!bytes || len <= 0) {
        if (bytes) env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
        return env->NewStringUTF("");
    }

    // TODO: 调用 auto-artifactarium Rust 库进行解析
    // 当前返回空 JSON，实际解析在 Rust 侧完成
    std::string result = "{}";

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return env->NewStringUTF(result.c_str());
}

// ──────────────────────────────────────────────
// JNI: 初始化解析器
// ──────────────────────────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_konkers_irminsul_MainActivity_nativeInitParser(
    JNIEnv* env, jobject obj)
{
    LOGI("Initializing parser");
    // TODO: 初始化 auto-artifactarium
    LOGI("Parser initialized");
    return JNI_TRUE;
}
