#!/usr/bin/env python3
"""
一步完成：
1. 下载 GenshinData 原始 JSON 文件到临时目录
2. 处理成 irminsul 所需的 database.json
3. 将 database.json 写入 assets 目录供打包进 APK

用法:
  python3 build_database.py [commit_hash] [output_path]
"""

import json
import os
import sys
import tempfile
import urllib.request
import urllib.error
import hashlib

# ── 配置 ─────────────────────────────────────────────
# 数据源：Dimbreath/GenshinData (GitLab) 是 anime-game-data 的原始数据源
# 备选：Sycamore0/GenshinData (GitHub fork)
USE_GITLAB = False   # GitLab 在沙箱里访问超时，改为 False
DEFAULT_COMMIT = "7ad6457973f718484ef8b36569b5f76fab628084"

if USE_GITLAB:
    BASE_URL = "https://gitlab.com/Dimbreath/GenshinData/-/raw/{commit}/ExcelBinOutput"
else:
    BASE_URL = "https://raw.githubusercontent.com/Sycamore0/GenshinData/{commit}/ExcelBinOutput"

# 需要下载的文件（对齐 anime-game-data/src/lib.rs update_impl 顺序）
FILES_TO_DOWNLOAD = [
    "AvatarExcelConfigData.json",
    "WeaponExcelConfigData.json",
    "MaterialExcelConfigData.json",
    "ReliquaryExcelConfigData.json",
    "ReliquaryMainPropExcelConfigData.json",
    "ReliquaryAffixExcelConfigData.json",
    "AvatarSkillDepotExcelConfigData.json",
    "DisplayItemExcelConfigData.json",
    "TextMapCHS.json",
    "TextMap_MediumEN.json",
]

# ── Property 枚举映射（对齐 anime-game-data/src/types.rs）──
PROPERTY_MAP = {
    "FIGHT_PROP_HP":                   "Hp",
    "FIGHT_PROP_HP_PERCENT":            "HpPercent",
    "FIGHT_PROP_ATTACK":                "Attack",
    "FIGHT_PROP_ATTACK_PERCENT":        "AttackPercent",
    "FIGHT_PROP_DEFENSE":               "Defense",
    "FIGHT_PROP_DEFENSE_PERCENT":       "DefensePercent",
    "FIGHT_PROP_ELEMENT_MASTERY":       "ElementalMastery",
    "FIGHT_PROP_CHARGE_EFFICIENCY":     "EnergyRecharge",
    "FIGHT_PROP_HEAL_ADD":              "Healing",
    "FIGHT_PROP_CRITICAL":              "CritRate",
    "FIGHT_PROP_CRITICAL_HURT":         "CritDamage",
    "FIGHT_PROP_PHYSICAL_ADD_HURT":     "PhysicalDamage",
    "FIGHT_PROP_WIND_ADD_HURT":         "AnemoDamage",
    "FIGHT_PROP_ROCK_ADD_HURT":         "GeoDamage",
    "FIGHT_PROP_ELEC_ADD_HURT":         "ElectroDamage",
    "FIGHT_PROP_WATER_ADD_HURT":        "HydroDamage",
    "FIGHT_PROP_FIRE_ADD_HURT":         "PyroDamage",
    "FIGHT_PROP_ICE_ADD_HURT":          "CryoDamage",
    "FIGHT_PROP_GRASS_ADD_HURT":        "DendroDamage",
}

PERCENT_PROPS = {
    "HpPercent", "AttackPercent", "DefensePercent",
    "EnergyRecharge", "Healing", "CritRate", "CritDamage",
    "PhysicalDamage", "AnemoDamage", "GeoDamage",
    "ElectroDamage", "HydroDamage", "PyroDamage",
    "CryoDamage", "DendroDamage",
}

SLOT_MAP = {
    "EQUIP_BRACER": "Flower",
    "EQUIP_NECKLACE": "Plume",
    "EQUIP_SHOES":    "Sands",
    "EQUIP_RING":     "Goblet",
    "EQUIP_DRESS":    "Circlet",
}

# ── 工具函数 ────────────────────────────────────────────
def http_get(url: str, timeout: int = 60) -> bytes | None:
    """HTTP GET，返回 bytes 或 None"""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "irminsul-android"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read()
    except Exception:
        return None


def download_file(url: str, dest: str) -> bool:
    """下载单个文件，返回是否成功"""
    data = http_get(url)
    if data is None:
        return False
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "wb") as f:
        f.write(data)
    return True


def load_json(path: str) -> list:
    """加载本地 JSON 文件，失败返回空列表"""
    if not os.path.exists(path):
        return []
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return []


def build_text_map(json_dir: str) -> dict[int, str]:
    """构建文本哈希 → 文本内容的映射，优先中文，备用英文"""
    for candidate in ["TextMap/TextMapCHS.json", "TextMapCHS.json",
                     "TextMap/TextMap_MediumEN.json", "TextMap_MediumEN.json"]:
        path = os.path.join(json_dir, candidate)
        if os.path.exists(path):
            try:
                with open(path, "r", encoding="utf-8") as f:
                    raw = json.load(f)
                # TextMap 格式: {"12345": "文本", ...} 或 [{"hash": 12345, "text": "文本"}, ...]
                if isinstance(raw, dict):
                    return {int(k): v for k, v in raw.items()}
                elif isinstance(raw, list):
                    return {int(entry["hash"]): entry["text"] for entry in raw}
            except Exception:
                pass
    return {}


def lookup_text(text_map: dict, hash_id: int) -> str | None:
    return text_map.get(hash_id)


def file_md5(path: str) -> str:
    """计算文件的 MD5，用于 CI 缓存判断"""
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def build_database(json_dir: str, version_hash: str) -> dict:
    """
    对齐 anime-game-data/src/lib.rs update_impl 的处理逻辑
    """
    text_map = build_text_map(json_dir)
    has_text = len(text_map) > 0
    print(f"  TextMap 条目: {len(text_map)} {'✓' if has_text else '⚠ 将使用 ID 代替文本'}")

    # 1. skill_type_map  ← AvatarSkillDepotExcelConfigData.json
    skill_type_map: dict[str, str] = {}
    for entry in load_json(os.path.join(json_dir, "AvatarSkillDepotExcelConfigData.json")):
        if entry.get("energySkill"):
            skill_type_map[str(entry["energySkill"])] = "Burst"
        skills = entry.get("skills", [])
        if len(skills) > 0 and skills[0]:
            skill_type_map[str(skills[0])] = "Auto"
        if len(skills) > 1 and skills[1]:
            skill_type_map[str(skills[1])] = "Skill"
    print(f"  skill_type_map: {len(skill_type_map)}")

    # 2. set_map  ← DisplayItemExcelConfigData.json (displayType=="RELIQUARY_ITEM")
    set_map: dict[str, str] = {}
    for entry in load_json(os.path.join(json_dir, "DisplayItemExcelConfigData.json")):
        if entry.get("displayType") == "RELIQUARY_ITEM":
            name = lookup_text(text_map, entry.get("nameTextMapHash", 0))
            if name and "param" in entry:
                set_map[str(entry["param"])] = name
    print(f"  set_map: {len(set_map)}")

    # 3. property_map  ← ReliquaryMainPropExcelConfigData.json
    property_map: dict[str, str] = {}
    for entry in load_json(os.path.join(json_dir, "ReliquaryMainPropExcelConfigData.json")):
        prop_type = entry.get("propType", "")
        if prop_type in PROPERTY_MAP:
            property_map[str(entry["id"])] = PROPERTY_MAP[prop_type]
    print(f"  property_map: {len(property_map)}")

    # 4. affix_map  ← ReliquaryAffixExcelConfigData.json
    affix_map: dict[str, dict] = {}
    for entry in load_json(os.path.join(json_dir, "ReliquaryAffixExcelConfigData.json")):
        prop_type = entry.get("propType", "")
        if prop_type not in PROPERTY_MAP:
            continue
        prop = PROPERTY_MAP[prop_type]
        value = float(entry.get("propValue", 0.0))
        if prop in PERCENT_PROPS:
            value *= 100.0
        affix_map[str(entry["id"])] = {"property": prop, "value": round(value, 2)}
    print(f"  affix_map: {len(affix_map)}")

    # 5. artifact_map  ← ReliquaryExcelConfigData.json
    artifact_map: dict[str, dict] = {}
    for entry in load_json(os.path.join(json_dir, "ReliquaryExcelConfigData.json")):
        equip_type = entry.get("equipType", "")
        slot = SLOT_MAP.get(equip_type)
        if not slot:
            continue
        set_id = str(entry.get("setId", 0))
        set_name = set_map.get(set_id, "")
        artifact_map[str(entry["id"])] = {
            "set": set_name,
            "slot": slot,
            "rarity": int(entry.get("rankLevel", 0)),
        }
    print(f"  artifact_map: {len(artifact_map)}")

    # 6. weapon_map  ← WeaponExcelConfigData.json
    weapon_map: dict[str, dict] = {}
    for entry in load_json(os.path.join(json_dir, "WeaponExcelConfigData.json")):
        name_hash = entry.get("nameTextMapHash", 0)
        name = lookup_text(text_map, name_hash) if has_text else f"weapon_{entry['id']}"
        if not name:
            name = f"weapon_{entry['id']}"
        weapon_map[str(entry["id"])] = {
            "name": name,
            "rarity": int(entry.get("rankLevel", 0)),
        }
    print(f"  weapon_map: {len(weapon_map)}")

    # 7. character_map  ← AvatarExcelConfigData.json
    character_map: dict[str, str] = {}
    for entry in load_json(os.path.join(json_dir, "AvatarExcelConfigData.json")):
        name_hash = entry.get("nameTextMapHash", 0)
        name = lookup_text(text_map, name_hash) if has_text else f"avatar_{entry['id']}"
        if not name:
            name = f"avatar_{entry['id']}"
        character_map[str(entry["id"])] = name
    print(f"  character_map: {len(character_map)}")

    # 8. material_map  ← MaterialExcelConfigData.json
    material_map: dict[str, str] = {}
    for entry in load_json(os.path.join(json_dir, "MaterialExcelConfigData.json")):
        name_hash = entry.get("nameTextMapHash", 0)
        name = lookup_text(text_map, name_hash) if has_text else f"material_{entry['id']}"
        if not name:
            name = f"material_{entry['id']}"
        material_map[str(entry["id"])] = name
    print(f"  material_map: {len(material_map)}")

    return {
        "version": 1,
        "git_hash": version_hash,
        "affix_map": affix_map,
        "artifact_map": artifact_map,
        "character_map": character_map,
        "material_map": material_map,
        "property_map": property_map,
        "set_map": set_map,
        "skill_type_map": skill_type_map,
        "weapon_map": weapon_map,
    }


def main():
    commit = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_COMMIT
    output_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "app", "src", "main", "assets", "game_data", "database.json"
    )

    base_url = BASE_URL.format(commit=commit)
    print(f"Commit:  {commit[:8]}")
    print(f"数据源: {'GitLab Dimbreath' if USE_GITLAB else 'GitHub Sycamore0'}")
    print(f"输出:   {output_path}")
    print()

    # 下载到临时目录
    with tempfile.TemporaryDirectory() as tmpdir:
        print("正在下载原始 JSON 文件...")
        ok_count = 0
        downloaded_files = []
        for fname in FILES_TO_DOWNLOAD:
            url = f"{base_url}/{fname}"
            dest = os.path.join(tmpdir, fname)
            if download_file(url, dest):
                size_kb = os.path.getsize(dest) / 1024
                print(f"  ✓ {fname:40s} ({size_kb:.1f} KB)")
                ok_count += 1
                downloaded_files.append(fname)
            else:
                # TextMap 文件非关键，其他失败则退出
                if "TextMap" in fname:
                    print(f"  ⚠ {fname:40s} (文本文件缺失，不影响核心功能)")
                else:
                    print(f"  ✗ {fname:40s} 下载失败，中止")
                    sys.exit(1)
        print(f"下载完成: {ok_count}/{len(FILES_TO_DOWNLOAD)}")
        print()

        # 处理数据
        print("正在处理数据...")
        db = build_database(tmpdir, commit)
        print("处理完成！")
        print()

        # 写入输出
        os.makedirs(os.path.dirname(output_path), exist_ok=True)
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(db, f, ensure_ascii=False, indent=2)

        size_kb = os.path.getsize(output_path) / 1024
        print(f"✓ 已写入 {output_path} ({size_kb:.1f} KB)")

        # 同时写入 VERSION 文件供 CI 缓存判断
        version_file = os.path.join(os.path.dirname(output_path), "VERSION")
        with open(version_file, "w") as f:
            f.write(f"{commit}\n")
        print(f"✓ 已写入 {version_file}")


if __name__ == "__main__":
    main()
