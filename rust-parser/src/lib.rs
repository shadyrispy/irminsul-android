// lib.rs - Rust JNI 桥接层
// 封装 auto-artifactarium 供 Android 通过 JNI 调用

use jni::JNIEnv;
use jni::objects::{JClass, JByteArray, JString};
use jni::sys::{jstring, jboolean, jint, jlong};
use serde::Serialize;
use serde_json;
use std::panic;
use std::collections::HashMap;

// 引入 auto-artifactarium
use auto_artifactarium::{
    GameSniffer, GamePacket, GameCommand, ConnectionPacket,
    matches_avatar_packet, matches_item_packet, matches_achievement_packet,
};

// ──────────────────────────────────────
// 数据结构定义
// ──────────────────────────────────────

/// 捕获统计信息
#[derive(Serialize)]
struct CaptureStats {
    packets_received: u64,
    game_packets: u64,
    commands_parsed: u64,
    connection_events: u64,
    bytes_processed: u64,
    elapsed_seconds: f64,
}

/// 解析结果（发送回 Java 层的 JSON）
#[derive(Serialize)]
#[serde(tag = "type")]
enum ParseOutput {
    #[serde(rename = "connection")]
    Connection { event: String },
    #[serde(rename = "avatar")]
    Avatar { data: serde_json::Value },
    #[serde(rename = "item")]
    Item { data: serde_json::Value },
    #[serde(rename = "achievement")]
    Achievement { data: serde_json::Value },
    #[serde(rename = "command")]
    Command { command_id: u16, head: String, payload_hex: String },
}

// ──────────────────────────────────────
// 全局状态（每个 sniffer 对应一个实例）
// ──────────────────────────────────────

use std::sync::Mutex;

lazy_static::lazy_static! {
    static ref SNIFFER: Mutex<Option<GameSniffer>> = Mutex::new(None);
    static ref STATS: Mutex<CaptureStats> = Mutex::new(CaptureStats {
        packets_received: 0,
        game_packets: 0,
        commands_parsed: 0,
        connection_events: 0,
        bytes_processed: 0,
        elapsed_seconds: 0.0,
    });
    static ref START_TIME: Mutex<Option<std::time::Instant>> = Mutex::new(None);
}

// ──────────────────────────────────────
// JNI 函数实现
// ──────────────────────────────────────

/// 初始化解析器
#[no_mangle]
pub extern "system" fn Java_com_github_konkers_irminsul_CaptureService_nativeInitParser(
    mut env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let result = panic::catch_unwind(|| {
        // 初始化日志
        init_logger_once();
        log::info!("Irminsul Rust parser initialized");

        // 初始化 sniffer
        let sniffer = GameSniffer::new();
        *SNIFFER.lock().unwrap() = Some(sniffer);

        // 重置统计
        *STATS.lock().unwrap() = CaptureStats {
            packets_received: 0,
            game_packets: 0,
            commands_parsed: 0,
            connection_events: 0,
            bytes_processed: 0,
            elapsed_seconds: 0.0,
        };
        *START_TIME.lock().unwrap() = Some(std::time::Instant::now());

        true
    });

    match result {
        Ok(true) => 1,
        _ => 0,
    }
}

/// 解析单个数据包
#[no_mangle]
pub extern "system" fn Java_com_github_konkers_irminsul_CaptureService_nativeParsePacket(
    mut env: JNIEnv,
    _class: JClass,
    packet_data: JByteArray,
) -> jstring {
    let result = panic::catch_unwind(|| {
        // 将 Java 字节数组转换为 Rust 向量
        let data = match env.convert_byte_array(packet_data) {
            Ok(d) => d,
            Err(e) => {
                log::error!("Failed to convert byte array: {:?}", e);
                return None;
            }
        };

        let data_len = data.len();

        // 更新统计
        {
            let mut stats = STATS.lock().unwrap();
            stats.packets_received += 1;
            stats.bytes_processed += data_len as u64;
        }

        // 获取 sniffer
        let mut sniffer_guard = SNIFFER.lock().unwrap();
        let sniffer = match sniffer_guard.as_mut() {
            Some(s) => s,
            None => {
                log::warn!("Sniffer not initialized");
                return None;
            }
        };

        // 传入原始以太网帧进行解析
        match sniffer.receive_packet(data) {
            Some(GamePacket::Connection(conn_packet)) => {
                // 连接事件
                {
                    let mut stats = STATS.lock().unwrap();
                    stats.game_packets += 1;
                    stats.connection_events += 1;
                }

                let event = match conn_packet {
                    ConnectionPacket::Connected => "connected",
                    ConnectionPacket::Disconnected => "disconnected",
                };

                let output = ParseOutput::Connection {
                    event: event.to_string(),
                };

                serde_json::to_string(&output).ok()
            }
            Some(GamePacket::Commands(commands)) => {
                // 游戏命令
                {
                    let mut stats = STATS.lock().unwrap();
                    stats.game_packets += 1;
                    stats.commands_parsed += commands.len() as u64;
                }

                // 尝试匹配已知的数据类型
                let mut results = Vec::new();

                for cmd in &commands {
                    // 尝试匹配角色数据
                    if let Some(avatars) = matches_avatar_packet(cmd) {
                        for avatar in avatars {
                            let json = serde_json::to_value(&avatar).unwrap_or_default();
                            results.push(serde_json::to_string(&ParseOutput::Avatar { data: json }).unwrap_or_default());
                        }
                        continue;
                    }

                    // 尝试匹配物品数据
                    if let Some(items) = matches_item_packet(cmd) {
                        for item in items {
                            let json = serde_json::to_value(&item).unwrap_or_default();
                            results.push(serde_json::to_string(&ParseOutput::Item { data: json }).unwrap_or_default());
                        }
                        continue;
                    }

                    // 尝试匹配成就数据
                    if let Some(achievements) = matches_achievement_packet(cmd) {
                        for ach in achievements {
                            let json = serde_json::to_value(&ach).unwrap_or_default();
                            results.push(serde_json::to_string(&ParseOutput::Achievement { data: json }).unwrap_or_default());
                        }
                        continue;
                    }

                    // 未匹配的命令，返回基本信息
                    let output = ParseOutput::Command {
                        command_id: cmd.command_id,
                        head: BASE64_STANDARD.encode(&cmd.head),
                        payload_hex: bytes_as_hex(&cmd.payload),
                    };
                    results.push(serde_json::to_string(&output).unwrap_or_default());
                }

                if results.is_empty() {
                    None
                } else if results.len() == 1 {
                    Some(results.remove(0))
                } else {
                    // 多个结果，用 JSON 数组返回
                    Some(format!("[{}]", results.join(",")))
                }
            }
            None => None, // 不是原神数据包
        }
    });

    match result {
        Ok(Some(json_str)) => match env.new_string(json_str) {
            Ok(jstr) => jstr.into_inner(),
            Err(_) => std::ptr::null_mut(),
        },
        _ => std::ptr::null_mut(),
    }
}

/// 获取捕获统计信息
#[no_mangle]
pub extern "system" fn Java_com_github_konkers_irminsul_CaptureService_nativeGetStats(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = panic::catch_unwind(|| {
        let mut stats = STATS.lock().unwrap();
        let start = START_TIME.lock().unwrap();

        if let Some(instant) = *start {
            stats.elapsed_seconds = instant.elapsed().as_secs_f64();
        }

        serde_json::to_string(&*stats).ok()
    });

    match result {
        Ok(Some(json)) => match env.new_string(json) {
            Ok(jstr) => jstr.into_inner(),
            Err(_) => std::ptr::null_mut(),
        },
        _ => std::ptr::null_mut(),
    }
}

/// 设置初始密钥
#[no_mangle]
pub extern "system" fn Java_com_github_konkers_irminsul_CaptureService_nativeSetInitialKeys(
    mut env: JNIEnv,
    _class: JClass,
    keys_json: JString,
) -> jboolean {
    let result = panic::catch_unwind(|| {
        let keys_str: String = match env.get_string(&keys_json) {
            Ok(s) => s.into(),
            Err(_) => return false,
        };

        let keys_map: HashMap<u16, String> = match serde_json::from_str(&keys_str) {
            Ok(m) => m,
            Err(e) => {
                log::error!("Failed to parse keys JSON: {:?}", e);
                return false;
            }
        };

        // 转换为 auto-artifactarium 需要的格式
        let initial_keys: HashMap<u16, Vec<u8>> = keys_map
            .into_iter()
            .filter_map(|(k, v)| {
                // 假设值是 base64 编码的密钥
                BASE64_STANDARD.decode(v).ok().map(|decoded| (k, decoded))
            })
            .collect();

        let mut sniffer_guard = SNIFFER.lock().unwrap();
        if let Some(sniffer) = sniffer_guard.as_mut() {
            *sniffer = std::mem::replace(sniffer, GameSniffer::new())
                .set_initial_keys(initial_keys);
            true
        } else {
            false
        }
    });

    match result {
        Ok(true) => 1,
        _ => 0,
    }
}

/// 释放解析器资源
#[no_mangle]
pub extern "system" fn Java_com_github_konkers_irminsul_CaptureService_nativeReleaseParser(
    mut env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let result = panic::catch_unwind(|| {
        *SNIFFER.lock().unwrap() = None;
        log::info!("Parser released");
        true
    });

    match result {
        Ok(true) => 1,
        _ => 0,
    }
}

// ──────────────────────────────────────
// 辅助函数
// ──────────────────────────────────────

fn init_logger_once() {
    use std::sync::Once;
    static INIT: Once = Once::new();
    INIT.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_min_level(log::Level::Info)
                .with_tag("IrminsulRust")
        );
    });
}

fn bytes_as_hex(bytes: &[u8]) -> String {
    bytes.iter().fold(String::new(), |mut output, b| {
        use std::fmt::Write;
        let _ = write!(output, "{b:02x}");
        output
    })
}

use base64::Engine;
use base64::prelude::BASE64_STANDARD;

// ──────────────────────────────────────
// 单元测试
// ──────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_stats_serialization() {
        let stats = CaptureStats {
            packets_received: 100,
            game_packets: 10,
            commands_parsed: 5,
            connection_events: 2,
            bytes_processed: 65536,
            elapsed_seconds: 30.5,
        };

        let json = serde_json::to_string(&stats).unwrap();
        assert!(json.contains("packets_received"));
        assert!(json.contains("65536"));
    }

    #[test]
    fn test_parse_output_serialization() {
        let output = ParseOutput::Connection {
            event: "connected".to_string(),
        };
        let json = serde_json::to_string(&output).unwrap();
        assert!(json.contains("connection"));

        let output = ParseOutput::Command {
            command_id: 1234,
            head: "abc".to_string(),
            payload_hex: "deadbeef".to_string(),
        };
        let json = serde_json::to_string(&output).unwrap();
        assert!(json.contains("1234"));
    }
}
