#!/usr/bin/env python3
"""
一步完成：
1. 优先从本地 app/src/main/assets/game_data/ 取 JSON 文件
   （没有或文件太小时才从网络下载）
2. 处理成 irminsul 所需的 database.json
3. 将 database.json 写入 assets 目录供打包进 APK

用法:
  python3 build_database.py [commit_hash] [output_path]
"""

import json
import os
import sys
import shutil
import tempfile
import urllib.request

# ── 配置 ─────────────────────────────────────────────
# 本地数据目录（优先从这里取文件）
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
LOCAL_DIR  = os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets", "game_data")
LOCAL_DIR  = os.path.abspath(LOCAL_DIR)

DEFAULT_COMMIT = "7ad6457973f718484ef8b36569b5f76fab628084"

# 需要准备的原始 JSON 文件
FILE_SPECS = [
    ("AvatarExcelConfigData.json",            False),
    ("WeaponExcelConfigData.json",             False),
    ("MaterialExcelConfigData.json",            False),
    ("ReliquaryExcelConfigData.json",         False),
    ("ReliquaryMainPropExcelConfigData.json", False),
    ("ReliquaryAffixExcelConfigData.json",    False),
    ("AvatarSkillDepotExcelConfigData.json",  False),
    ("DisplayItemExcelConfigData.json",        False),
    ("TextMapCHS.json",                        True),   # 可选
]

# ── Property 枚举映射（对齐 anime-game-data/src/types.rs）──
PROPERTY_MAP = {
    "FIGHT_PROP_HP":                    "Hp",
    "FIGHT_PROP_HP_PERCENT":            "HpPercent",
    "FIGHT_PROP_ATTACK":                "Attack",
    "FIGHT_PROP_ATTACK_PERCENT":        "AttackPercent",
    "FIGHT_PROP_DEFENSE":              "Defense",
    "FIGHT_PROP_DEFENSE_PERCENT":      "DefensePercent",
    "FIGHT_PROP_ELEMENT_MASTERY":      "ElementalMastery",
    "FIGHT_PROP_CHARGE_EFFICIENCY":    "EnergyRecharge",
    "FIGHT_PROP_HEAL_ADD":             "Healing",
    "FIGHT_PROP_CRITICAL":              "CritRate",
    "FIGHT_PROP_CRITICAL_HURT":        "CritDamage",
    "FIGHT_PROP_PHYSICAL_ADD_HURT":    "PhysicalDamage",
    "FIGHT_PROP_WIND_ADD_HURT":        "AnemoDamage",
    "FIGHT_PROP_ROCK_ADD_HURT":        "GeoDamage",
    "FIGHT_PROP_ELEC_ADD_HURT":       "ElectroDamage",
    "FIGHT_PROP_WATER_ADD_HURT":       "HydroDamage",
    "FIGHT_PROP_FIRE_ADD_HURT":        "PyroDamage",
    "FIGHT_PROP_ICE_ADD_HURT":         "CryoDamage",
    "FIGHT_PROP_GRASS_ADD_HURT":       "DendroDamage",
}

PERCENT_PROPS = set(PROPERTY_MAP.values()) & {
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
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "irminsul"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read()
    except Exception:
        return None


def prepare_file(fname: str, is_optional: bool, commit: str, tmpdir: str) -> bool:
    """
    准备单个文件：
    1. 先找本地 LOCAL_DIR/fname
    2. 本地没有或太小（< 1KB）才从网络下载到 tmpdir
    3. 返回是否成功（可选文件始终返回 True）
    """
    local_path  = os.path.join(LOCAL_DIR, fname)
    dest_path   = os.path.join(tmpdir, fname)
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)

    # 本地已有且大小正常 → 直接复制
    if os.path.exists(local_path) and os.path.getsize(local_path) > 1024:
        shutil.copy2(local_path, dest_path)
        size_kb = os.path.getsize(local_path) / 1024
        print(f"  ✓ {fname:40s} (本地, {size_kb:.1f} KB)")
        return True

    # 本地没有 → 尝试下载
    if commit and not commit.startswith("local"):
        url = (f"https://raw.githubusercontent.com/Sycamore0/GenshinData/"
                f"{commit}/ExcelBinOutput/{fname}")
        data = http_get(url)
        if data:
            with open(dest_path, "wb") as f:
                f.write(data)
            size_kb = len(data) / 1024
            print(f"  ✓ {fname:40s} (下载, {size_kb:.1f} KB)")
            return True

    # 失败
    if is_optional:
        print(f"  ⚠ {fname:40s} (可选文件缺失，继续)")
        return True
    print(f"  ✗ {fname:40s} (必需文件缺失，中止)")
    return False


def load_json(path: str) -> list:
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return []


def build_text_map(tmpdir: str) -> dict[int, str]:
    """从 tmpdir 里的 TextMapCHS.json 构建哈希→文本映射"""
    for candidate in ["TextMapCHS.json", "TextMap/TextMapCHS.json"]:
        path = os.path.join(tmpdir, candidate)
        if os.path.exists(path):
            try:
                with open(path, "r", encoding="utf-8") as f:
                    raw = json.load(f)
                if isinstance(raw, dict):
                    return {int(k): v for k, v in raw.items()}
                elif isinstance(raw, list):
                    return {int(e["hash"]): e["text"] for e in raw}
            except Exception:
                pass
    return {}


def build_database(tmpdir: str, version_hash: str) -> dict:
    text_map = build_text_map(tmpdir)
    has_text = len(text_map) > 0
    print(f"  TextMap 条目: {len(text_map)} "
          f"{'✓' if has_text else '⚠ 将使用 ID 代替文本'}")

    def lookup(h: int) -> str | None:
        return text_map.get(h)

    # 1. skill_type_map
    skill_type_map: dict[str, str] = {}
    for e in load_json(os.path.join(tmpdir, "AvatarSkillDepotExcelConfigData.json")):
        if e.get("energySkill"):
            skill_type_map[str(e["energySkill"])] = "Burst"
        skills = e.get("skills", [])
        if len(skills) > 0 and skills[0]:
            skill_type_map[str(skills[0])] = "Auto"
        if len(skills) > 1 and skills[1]:
            skill_type_map[str(skills[1])] = "Skill"
    print(f"  skill_type_map: {len(skill_type_map)}")

    # 2. set_map  ← DisplayItemExcelConfigData.json
    set_map: dict[str, str] = {}
    for e in load_json(os.path.join(tmpdir, "DisplayItemExcelConfigData.json")):
        if e.get("displayType") == "RELQUARY_ITEM":
            name = lookup(e.get("nameTextMapHash", 0))
            if name and "param" in e:
                set_map[str(e["param"])] = name
    print(f"  set_map: {len(set_map)}")

    # 3. property_map
    property_map: dict[str, str] = {}
    for e in load_json(os.path.join(tmpdir, "ReliquaryMainPropExcelConfigData.json")):
        pt = e.get("propType", "")
        if pt in PROPERTY_MAP:
            property_map[str(e["id"])] = PROPERTY_MAP[pt]
    print(f"  property_map: {len(property_map)}")

    # 4. affix_map
    affix_map: dict[str, dict] = {}
    for e in load_json(os.path.join(tmpdir, "ReliquaryAffixExcelConfigData.json")):
        pt = e.get("propType", "")
        if pt not in PROPERTY_MAP:
            continue
        prop  = PROPERTY_MAP[pt]
        value = float(e.get("propValue", 0.0))
        if prop in PERCENT_PROPS:
            value *= 100.0
        affix_map[str(e["id"])] = {"property": prop, "value": round(value, 2)}
    print(f"  affix_map: {len(affix_map)}")

    # 5. artifact_map
    artifact_map: dict[str, dict] = {}
    for e in load_json(os.path.join(tmpdir, "ReliquaryExcelConfigData.json")):
        slot = SLOT_MAP.get(e.get("equipType", ""))
        if not slot:
            continue
        set_id   = str(e.get("setId", 0))
        set_name = set_map.get(set_id, "")
        artifact_map[str(e["id"])] = {
            "set":    set_name,
            "slot":   slot,
            "rarity": int(e.get("rankLevel", 0)),
        }
    print(f"  artifact_map: {len(artifact_map)}")

    # 6. weapon_map
    weapon_map: dict[str, dict] = {}
    for e in load_json(os.path.join(tmpdir, "WeaponExcelConfigData.json")):
        h = e.get("nameTextMapHash", 0)
        name = lookup(h) if has_text else f"weapon_{e['id']}"
        if not name:
            name = f"weapon_{e['id']}"
        weapon_map[str(e["id"])] = {"name": name, "rarity": int(e.get("rankLevel", 0))}
    print(f"  weapon_map: {len(weapon_map)}")

    # 7. character_map
    character_map: dict[str, str] = {}
    for e in load_json(os.path.join(tmpdir, "AvatarExcelConfigData.json")):
        h = e.get("nameTextMapHash", 0)
        name = lookup(h) if has_text else f"avatar_{e['id']}"
        if not name:
            name = f"avatar_{e['id']}"
        character_map[str(e["id"])] = name
    print(f"  character_map: {len(character_map)}")

    # 8. material_map
    material_map: dict[str, str] = {}
    for e in load_json(os.path.join(tmpdir, "MaterialExcelConfigData.json")):
        h = e.get("nameTextMapHash", 0)
        name = lookup(h) if has_text else f"material_{e['id']}"
        if not name:
            name = f"material_{e['id']}"
        material_map[str(e["id"])] = name
    print(f"  material_map: {len(material_map)}")

    return {
        "version":        1,
        "git_hash":      version_hash,
        "affix_map":    affix_map,
        "artifact_map":  artifact_map,
        "character_map": character_map,
        "material_map":  material_map,
        "property_map":  property_map,
        "set_map":       set_map,
        "skill_type_map": skill_type_map,
        "weapon_map":   weapon_map,
    }


def main():
    commit = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_COMMIT
    output_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        LOCAL_DIR, "database.json"
    )
    print(f"Commit:  {commit[:8]}")
    print(f"输出:   {output_path}")
    print()

    with tempfile.TemporaryDirectory() as tmpdir:
        # 准备所有原始 JSON 文件
        print("正在准备原始 JSON 文件...")
        all_ok = True
        for fname, is_opt in FILE_SPECS:
            if not prepare_file(fname, is_opt, commit, tmpdir):
                all_ok = False
                break
        if not all_ok:
            sys.exit(1)
        print()

        # 处理成 database.json
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

        # 写入 VERSION
        version_file = os.path.join(os.path.dirname(output_path), "VERSION")
        with open(version_file, "w") as f:
            f.write(f"{commit}\n")
        print(f"✓ 已写入 {version_file}")


if __name__ == "__main__":
    main()
