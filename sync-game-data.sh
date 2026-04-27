#!/bin/bash
# sync-game-data.sh
# 从 Dimbreath/GenshinData 仓库下载游戏 JSON 数据到 app/src/main/assets/game_data/
# 并在 VERSION 文件中记录 commit hash 以便后续增量更新
#
# 用法：
#   ./sync-game-data.sh              # 使用最新 commit
#   ./sync-game-data.sh <commit-hash> # 使用指定 commit

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
ASSETS_DIR="$PROJECT_ROOT/app/src/main/assets/game_data"

mkdir -p "$ASSETS_DIR"

# 目标仓库：Dimbreath/GenshinData（由 anime-game-data 库使用的数据源）
DATA_REPO="Dimbreath/GenshinData"
API_URL="https://api.github.com/repos/$DATA_REPO/commits?per_page=1"

echo "=== Fetching latest commit from $DATA_REPO ==="

if [ -n "$1" ]; then
    COMMIT_HASH="$1"
    echo "Using specified commit: $COMMIT_HASH"
else
    COMMIT_HASH=$(curl -s --max-time 30 "$API_URL" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['sha'] if isinstance(d,list) else 'ERROR')" 2>/dev/null || echo "FETCH_FAILED")
    if [ "$COMMIT_HASH" = "FETCH_FAILED" ] || [ -z "$COMMIT_HASH" ]; then
        echo "ERROR: Failed to fetch latest commit from $DATA_REPO"
        echo "Tip: specify a commit hash manually: ./sync-game-data.sh <hash>"
        exit 1
    fi
fi

COMMIT_SHORT=$(echo "$COMMIT_HASH" | cut -c1-8)
VERSION_FILE="$ASSETS_DIR/VERSION"

echo "Using commit: $COMMIT_SHORT ($COMMIT_HASH)"

# 检查是否已有相同版本的数据
if [ -f "$VERSION_FILE" ]; then
    EXISTING_HASH=$(cat "$VERSION_FILE")
    if [ "$EXISTING_HASH" = "$COMMIT_HASH" ]; then
        echo "Game data already up-to-date (commit $COMMIT_SHORT)"
        exit 0
    fi
fi

BASE_URL="https://raw.githubusercontent.com/$DATA_REPO/$COMMIT_HASH/ExcelBinOutput"

# 要下载的 JSON 文件列表（anime-game-data/src/lib.rs 中定义的路径）
FILES=(
    "TextMap/TextMapCHS.json"
    "ExcelBinOutput/AvatarExcelConfigData.json"
    "ExcelBinOutput/WeaponExcelConfigData.json"
    "ExcelBinOutput/MaterialExcelConfigData.json"
    "ExcelBinOutput/ReliquaryExcelConfigData.json"
    "ExcelBinOutput/ReliquaryMainPropExcelConfigData.json"
    "ExcelBinOutput/ReliquaryAffixExcelConfigData.json"
    "ExcelBinOutput/AvatarSkillDepotExcelConfigData.json"
    "ExcelBinOutput/DisplayItemExcelConfigData.json"
)

for f in "${FILES[@]}"; do
    BASENAME=$(basename "$f")
    DIRNAME=$(dirname "$f")

    echo "Downloading $BASENAME..."

    OUTPUT="$ASSETS_DIR/$BASENAME"

    # TextMap 在子目录中
    if [ "$DIRNAME" != "." ] && [ "$DIRNAME" != "$f" ]; then
        mkdir -p "$ASSETS_DIR/$DIRNAME"
        OUTPUT="$ASSETS_DIR/$f"
    fi

    curl -L --fail --max-time 60 -o "$OUTPUT" "$BASE_URL/$f" 2>&1 | tail -1
    if [ $? -ne 0 ]; then
        echo "ERROR: Failed to download $f"
        echo "URL: $BASE_URL/$f"
        exit 1
    fi
    SIZE=$(ls -lh "$OUTPUT" | awk '{print $5}')
    echo "  -> $SIZE"
done

# 记录版本
echo "$COMMIT_HASH" > "$VERSION_FILE"
echo ""
echo "=== SUCCESS: Game data synced to $DATA_REPO commit $COMMIT_SHORT ==="

# 列出最终文件
echo ""
echo "=== Final assets/game_data/ contents ==="
find "$ASSETS_DIR" -name "*.json" -exec ls -lh {} \;
