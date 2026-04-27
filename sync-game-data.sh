#!/bin/bash
# sync-game-data.sh
# 从 GenshinData 仓库下载游戏 JSON 数据到 app/src/main/assets/game_data/
# 并在 VERSION 文件中记录 commit hash 以便后续增量更新
#
# 用法：
#   ./sync-game-data.sh              # 使用内置的最新 commit
#   ./sync-game-data.sh <commit-hash> # 使用指定 commit
#
# 数据源：Sycamore0/GenshinData（Dimbreath 的 fork，147 stars）

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
ASSETS_DIR="$PROJECT_ROOT/app/src/main/assets/game_data"

mkdir -p "$ASSETS_DIR"

# ──────────────────────────────────────
# 默认使用这个 commit（2025-04 最新）
# 如需更新：在浏览器访问 https://github.com/Sycamore0/GenshinData/commits/main
# 然后替换下面的 COMMIT 变量
# ──────────────────────────────────────
DEFAULT_COMMIT="7ad6457973f718484ef8b36569b5f76fab628084"

if [ -n "$1" ]; then
    COMMIT="$1"
    echo "Using specified commit: ${COMMIT:0:8}"
else
    COMMIT="$DEFAULT_COMMIT"
    echo "Using default commit: ${COMMIT:0:8}"
fi

BASE_URL="https://raw.githubusercontent.com/Sycamore0/GenshinData/$COMMIT/ExcelBinOutput"
SOURCE="Sycamore0/GenshinData"
VERSION_FILE="$ASSETS_DIR/VERSION"

echo "=== Syncing game data from $SOURCE @ ${COMMIT:0:8} ==="

# 检查是否已是最新版本
if [ -f "$VERSION_FILE" ]; then
    EXISTING=$(cat "$VERSION_FILE" | head -1)
    if echo "$EXISTING" | grep -q "$COMMIT"; then
        echo "Game data already up-to-date (commit ${COMMIT:0:8})"
        echo "(To force re-download, delete $VERSION_FILE)"
        exit 0
    fi
fi

# 要下载的 JSON 文件列表
FILES=(
    "AvatarExcelConfigData.json"
    "WeaponExcelConfigData.json"
    "MaterialExcelConfigData.json"
    "ReliquaryExcelConfigData.json"
    "ReliquaryMainPropExcelConfigData.json"
    "ReliquaryAffixExcelConfigData.json"
    "AvatarSkillDepotExcelConfigData.json"
    "DisplayItemExcelConfigData.json"
)

TOTAL=${#FILES[@]}

for f in "${FILES[@]}"; do
    IDX=$(( TOTAL - ${#FILES[@]}))
    echo "[$((IDX+1))/$TOTAL] Downloading $f..."
    curl -L --fail --max-time 60 -o "$ASSETS_DIR/$f" "$BASE_URL/$f" 2>&1 | tail -1
    if [ $? -ne 0 ]; then
        echo "ERROR: Failed to download $BASE_URL/$f"
        exit 1
    fi
    SIZE=$(ls -lh "$ASSETS_DIR/$f" | awk '{print $5}')
    echo "  -> $SIZE"
done

# TextMap/TextMapCHS.json 在子目录
echo "[$TOTAL/$TOTAL] Downloading TextMapCHS.json..."
mkdir -p "$ASSETS_DIR/TextMap"
curl -L --fail --max-time 60 -o "$ASSETS_DIR/TextMap/TextMapCHS.json" "$BASE_URL/TextMap/TextMapCHS.json" 2>&1 | tail -1
if [ $? -eq 0 ]; then
    SIZE=$(ls -lh "$ASSETS_DIR/TextMap/TextMapCHS.json" | awk '{print $5}')
    echo "  -> $SIZE"
else
    echo "WARNING: TextMapCHS.json download failed (may use different path)"
fi

# 记录版本
echo "$COMMIT # $SOURCE" > "$VERSION_FILE"
echo ""
echo "=== SUCCESS: Game data synced to $SOURCE @ ${COMMIT:0:8} ==="

# 列出最终文件
echo ""
echo "=== Final assets/game_data/ contents ==="
find "$ASSETS_DIR" -name "*.json" -exec ls -lh {} \;
