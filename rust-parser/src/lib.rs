// lib.rs - Rust JNI 桥接层
// 封装 auto-artifactarium 供 Android 通过 JNI 调用

use jni::JNIEnv;
use jni::objects::{JClass, JByteArray};
use jni::sys::{jstring, jboolean};
use std::panic;
use std::sync::Mutex;
use std::sync::Once;

use auto_artifactarium::{
    GameSniffer, GamePacket, GameCommand, ConnectionPacket, PacketDirection,
    matches_avatar_packet, matches_item_packet, matches_achievement_packet,
};

use protobuf::Message;

// ──────────────────────────────────────
// 全局状态
// ──────────────────────────────────────

struct CaptureState {
    sniffer: Option<GameSniffer>,
    packets_received: u64,
    game_packets: u64,
    commands_parsed: u64,
    connection_events: u64,
    bytes_processed: u64,
    start_time: Option<std::time::Instant>,
}

impl CaptureState {
    fn new() -> Self {
        CaptureState {
            sniffer: None,
            packets_received: 0,
            game_packets: 0,
            commands_parsed: 0,
            connection_events: 0,
            bytes_processed: 0,
            start_time: None,
        }
    }

    fn reset(&mut self) {
        self.sniffer = Some(GameSniffer::new());
        self.packets_received = 0;
        self.game_packets = 0;
        self.commands_parsed = 0;
        self.connection_events = 0;
        self.bytes_processed = 0;
        self.start_time = Some(std::time::Instant::now());
    }
}

// 使用 lazy_static 风格的安全全局状态
static mut STATE: Option<Mutex<CaptureState>> = None;
static INIT: Once = Once::new();

fn with_state<F, R>(f: F) -> R
where
    F: FnOnce(&mut CaptureState) -> R,
{
    unsafe {
        INIT.call_once(|| {
            STATE = Some(Mutex::new(CaptureState::new()));
        });
        let mut guard = STATE.as_ref().unwrap().lock().unwrap();
        f(&mut guard)
    }
}

// ──────────────────────────────────────
// JNI 函数
// ──────────────────────────────────────

/// 初始化解析器
#[no_mangle]
pub extern "system" fn Java_com_github_konkers_irminsul_CaptureService_nativeInitParser(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let result = panic::catch_unwind(|| {
        init_logger_once();
        log::info!("Irminsul Rust parser initialized");

        with_state(|state| {
            state.reset();
        });

        true
    });

    match result {
        Ok(true) => 1,
        _ => 0,
    }
}

/// 解析单个数据包（原始以太网帧）
#[no_mangle]
pub extern "system" fn Java_com_github_konkers_irminsul_CaptureService_nativeParsePacket(
    mut env: JNIEnv,
    _class: JClass,
    packet_data: JByteArray,
) -> jstring {
    let data = match env.convert_byte_array(&packet_data) {
        Ok(d) => d,
        Err(_) => return std::ptr::null_mut(),
    };

    let data_len = data.len();
    if data_len == 0 {
        return std::ptr::null_mut();
    }

    with_state(|state| {
        state.packets_received += 1;
        state.bytes_processed += data_len as u64;
    });

    let result_json = with_state(|state| -> Option<String> {
        let sniffer = state.sniffer.as_mut()?;

        match sniffer.receive_packet(data) {
            Some(GamePacket::Connection(conn_packet)) => {
                state.game_packets += 1;
                state.connection_events += 1;

                let event = match conn_packet {
                    ConnectionPacket::HandshakeRequested => "handshake_requested",
                    ConnectionPacket::HandshakeEstablished => "handshake_established",
                    ConnectionPacket::Disconnected => "disconnected",
                    ConnectionPacket::SegmentData(dir, _) => match dir {
                        PacketDirection::Sent => "segment_sent",
                        PacketDirection::Received => "segment_received",
                    },
                };

                Some(format!(
                    r#"{{"type":"connection","event":"{}"}}"#,
                    event
                ))
            }
            Some(GamePacket::Commands(commands)) => {
                state.game_packets += 1;
                state.commands_parsed += commands.len() as u64;

                let mut results: Vec<String> = Vec::new();

                for cmd in &commands {
                    // 角色数据
                    if let Some(avatars) = matches_avatar_packet(cmd) {
                        for avatar in avatars {
                            results.push(format_avatar_info(&avatar));
                        }
                        continue;
                    }

                    // 物品数据
                    if let Some(items) = matches_item_packet(cmd) {
                        for item in items {
                            results.push(format_item(&item));
                        }
                        continue;
                    }

                    // 成就数据
                    if let Some(achievements) = matches_achievement_packet(cmd) {
                        for ach in achievements {
                            results.push(format_achievement(&ach));
                        }
                        continue;
                    }

                    // 未识别的命令
                    results.push(format!(
                        r#"{{"type":"command","command_id":{},"data_len":{}}}"#,
                        cmd.command_id,
                        cmd.proto_data.len()
                    ));
                }

                if results.is_empty() {
                    None
                } else if results.len() == 1 {
                    Some(results.remove(0))
                } else {
                    Some(format!("[{}]", results.join(",")))
                }
            }
            None => None,
        }
    });

    match result_json {
        Some(json) => match env.new_string(json) {
            Ok(jstr) => jstr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

/// 获取捕获统计信息
#[no_mangle]
pub extern "system" fn Java_com_github_konkers_irminsul_CaptureService_nativeGetStats(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = with_state(|state| {
        let elapsed = match state.start_time {
            Some(t) => t.elapsed().as_secs_f64(),
            None => 0.0,
        };
        format!(
            r#"{{"packets_received":{},"game_packets":{},"commands_parsed":{},"connection_events":{},"bytes_processed":{},"elapsed_seconds":{:.1}}}"#,
            state.packets_received,
            state.game_packets,
            state.commands_parsed,
            state.connection_events,
            state.bytes_processed,
            elapsed
        )
    });

    match env.new_string(json) {
        Ok(jstr) => jstr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// 释放解析器资源
#[no_mangle]
pub extern "system" fn Java_com_github_konkers_irminsul_CaptureService_nativeReleaseParser(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    with_state(|state| {
        state.sniffer = None;
    });
    log::info!("Parser released");
    1
}

// ──────────────────────────────────────
// 序列化函数（手动 JSON，因为 protobuf 类型没有 serde derive）
// ──────────────────────────────────────

fn format_avatar_info(avatar: &auto_artifactarium::gen::protos::AvatarInfo) -> String {
    let b64 = match avatar.write_to_bytes() {
        Ok(bytes) => base64_encode(&bytes),
        Err(_) => String::new(),
    };

    // AvatarInfo 字段: avatar_id, guid, life_state, skill_depot_id, ...
    // level 在 prop_map (HashMap<u32, PropValue>) 里，key=4001 对应 level
    let level = avatar.prop_map.get(&4001).map(|pv| pv.val).unwrap_or(0);

    format!(
        r#"{{"type":"avatar","avatar_id":{},"guid":{},"level":{},"life_state":{},"skill_depot_id":{},"proto_base64":"{}"}}"#,
        avatar.avatar_id,
        avatar.guid,
        level,
        avatar.life_state,
        avatar.skill_depot_id,
        b64
    )
}

fn format_item(item: &auto_artifactarium::gen::protos::Item) -> String {
    let b64 = match item.write_to_bytes() {
        Ok(bytes) => base64_encode(&bytes),
        Err(_) => String::new(),
    };

    format!(
        r#"{{"type":"item","item_id":{},"guid":{},"proto_base64":"{}"}}"#,
        item.item_id,
        item.guid,
        b64
    )
}

fn format_achievement(ach: &auto_artifactarium::Achievement) -> String {
    let mut parts = vec![
        format!(r#""type":"achievement""#),
        format!(r#""id":{}"#, ach.id),
        format!(r#""status":{}"#, ach.status),
    ];
    if let Some(ts) = ach.finish_timestamp {
        parts.push(format!(r#""finish_timestamp":{}"#, ts));
    }
    format!("{{{}}}", parts.join(","))
}

// ──────────────────────────────────────
// Base64 编码（无需额外依赖）
// ──────────────────────────────────────

fn base64_encode(data: &[u8]) -> String {
    const CHARS: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut result = String::with_capacity((data.len() + 2) / 3 * 4);

    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = if chunk.len() > 1 { chunk[1] as u32 } else { 0 };
        let b2 = if chunk.len() > 2 { chunk[2] as u32 } else { 0 };
        let triple = (b0 << 16) | (b1 << 8) | b2;

        result.push(CHARS[((triple >> 18) & 0x3F) as usize] as char);
        result.push(CHARS[((triple >> 12) & 0x3F) as usize] as char);
        if chunk.len() > 1 {
            result.push(CHARS[((triple >> 6) & 0x3F) as usize] as char);
        } else {
            result.push('=');
        }
        if chunk.len() > 2 {
            result.push(CHARS[(triple & 0x3F) as usize] as char);
        } else {
            result.push('=');
        }
    }

    result
}

// ──────────────────────────────────────
// 辅助
// ──────────────────────────────────────

fn init_logger_once() {
    static LOG_INIT: Once = Once::new();
    LOG_INIT.call_once(|| {
        android_logger::init_once(android_logger::Config::default().with_tag("IrminsulRust"));
        log::set_max_level(log::LevelFilter::Info);
    });
}

// ──────────────────────────────────────
// 单元测试
// ──────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_base64_encode() {
        assert_eq!(base64_encode(b"hello"), "aGVsbG8=");
        assert_eq!(base64_encode(b""), "");
        assert_eq!(base64_encode(b"a"), "YQ==");
        assert_eq!(base64_encode(b"ab"), "YWI=");
        assert_eq!(base64_encode(b"abc"), "YWJj");
    }

    #[test]
    fn test_format_achievement() {
        let ach = auto_artifactarium::Achievement {
            id: 81001,
            status: 2,
            finish_timestamp: Some(1234567890),
        };
        let json = format_achievement(&ach);
        assert!(json.contains("81001"));
        assert!(json.contains("1234567890"));
    }

    #[test]
    fn test_capture_state() {
        with_state(|state| {
            state.reset();
            assert!(state.sniffer.is_some());
            assert_eq!(state.packets_received, 0);
            state.packets_received = 42;
            assert_eq!(state.packets_received, 42);
        });
    }

    #[test]
    fn test_stats_json() {
        with_state(|state| {
            state.reset();
            state.packets_received = 100;
            state.game_packets = 10;
        });
    }
}
