// lib.rs - Rust 库主文件
// 这个库封装 auto-artifactarium 供 Android 通过 JNI 调用

use jni::JNIEnv;
use jni::objects::{JClass, JByteArray, JString};
use jni::sys::{jstring, jboolean};
use serde::{Deserialize, Serialize};
use serde_json;
use std::panic;
use auto_artifactarium::{PacketParser, ParseResult};

// 初始化日志
fn init_logger() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_min_level(log::Level::Debug)
            .with_tag("IrminsulRust")
    );
}

// 解析结果结构体（用于与 Java 通信）
#[derive(Serialize, Deserialize)]
struct ParseResultJson {
    packet_type: String,
    data_type: String,  // Character, Weapon, Artifact, Material
    name: String,
    id: u64,
    level: Option<i32>,
    rarity: Option<i32>,
    element: Option<String>,
    constellation: Option<i32>,
    refinement: Option<i32>,
    set_name: Option<String>,
    slot: Option<String>,
    main_stat: Option<String>,
    main_stat_value: Option<f64>,
    count: Option<i32>,
    raw_json: String,
}

impl From<ParseResult> for ParseResultJson {
    fn from(result: ParseResult) -> Self {
        ParseResultJson {
            packet_type: result.packet_type,
            data_type: result.data_type,
            name: result.name,
            id: result.id,
            level: result.level,
            rarity: result.rarity,
            element: result.element,
            constellation: result.constellation,
            refinement: result.refinement,
            set_name: result.set_name,
            slot: result.slot,
            main_stat: result.main_stat,
            main_stat_value: result.main_stat_value,
            count: result.count,
            raw_json: result.raw_json,
        }
    }
}

/// JNI 函数：初始化解析器
#[no_mangle]
pub extern "system" fn Java_com_github_konkers_irminsul_MainActivity_nativeInitParser(
    mut env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let result = panic::catch_unwind(|| {
        // 初始化日志
        init_logger();
        log::info!("Irminsul Rust parser initialized");
        
        // 可以在这里加载密钥文件等资源
        // let keys = include_bytes!("../keys/key.bin");
        
        true
    });
    
    match result {
        Ok(success) => if success { 1 } else { 0 },
        Err(_) => 0,
    }
}

/// JNI 函数：解析原神数据包
#[no_mangle]
pub extern "system" fn Java_com_github_konkers_irminsul_CaptureService_nativeParsePacket(
    mut env: JNIEnv,
    _class: JClass,
    packet_data: JByteArray,
) -> jstring {
    // 设置 panic 处理器
    let result = panic::catch_unwind(|| {
        // 将 Java 字节数组转换为 Rust 向量
        let data = match env.convert_byte_array(packet_data) {
            Ok(d) => d,
            Err(e) => {
                log::error!("Failed to convert byte array: {:?}", e);
                return std::ptr::null_mut();
            }
        };
        
        log::debug!("Parsing packet of size: {} bytes", data.len());
        
        // 创建解析器
        let parser = match PacketParser::new() {
            Ok(p) => p,
            Err(e) => {
                log::error!("Failed to create parser: {:?}", e);
                return std::ptr::null_mut();
            }
        };
        
        // 解析数据包
        match parser.parse(&data) {
            Some(result) => {
                // 转换为 JSON
                let json_result: ParseResultJson = result.into();
                match serde_json::to_string(&json_result) {
                    Ok(json_str) => {
                        log::info!("Successfully parsed packet: {}", json_result.data_type);
                        
                        // 将结果转换回 Java 字符串
                        match env.new_string(json_str) {
                            Ok(jstr) => jstr.into_inner(),
                            Err(e) => {
                                log::error!("Failed to create Java string: {:?}", e);
                                std::ptr::null_mut()
                            }
                        }
                    }
                    Err(e) => {
                        log::error!("Failed to serialize to JSON: {:?}", e);
                        std::ptr::null_mut()
                    }
                }
            }
            None => {
                // 不是原神数据包，返回 null
                log::debug!("Not a Genshin packet");
                std::ptr::null_mut()
            }
        }
    });
    
    match result {
        Ok(jstring) => jstring,
        Err(_) => {
            log::error!("Panic in nativeParsePacket");
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_parse_result_json_serialization() {
        let result = ParseResultJson {
            packet_type: "AvatarData".to_string(),
            data_type: "Character".to_string(),
            name: "Test Character".to_string(),
            id: 1000001,
            level: Some(80),
            rarity: Some(5),
            element: Some("Anemo".to_string()),
            constellation: Some(0),
            refinement: None,
            set_name: None,
            slot: None,
            main_stat: None,
            main_stat_value: None,
            count: None,
            raw_json: "{}".to_string(),
        };
        
        let json = serde_json::to_string(&result).unwrap();
        assert!(json.contains("Character"));
        assert!(json.contains("1000001"));
    }
}
