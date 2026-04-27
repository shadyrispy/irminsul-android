#!/bin/bash
# sync-game-data.sh
# 从 auto-artifactarium 仓库下载 game_data JSON 文件到 app/src/main/assets/game_data/
# 并在 VERSION 文件中记录 commit hash 以便后续增量更新

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
ASSETS_DIR="$PROJECT_ROOT/app/src/main/assets/game_data"
AUTO_DIR="$PROJECT_ROOT/auto-artifactarium"

mkdir -p "$ASSETS_DIR"

# 获取 auto-artifactarium 的最新 commit hash
COMMIT_HASH=$(cd "$AUTO_DIR" && git rev-parse HEAD)
COMMIT_SHORT=$(echo "$COMMIT_HASH" | cut -c1-8)
VERSION_FILE="$ASSETS_DIR/VERSION"

echo "=== Syncing game data from auto-artifactarium commit $COMMIT_SHORT ==="

# 检查是否已有相同版本的数据
if [ -f "$VERSION_FILE" ]; then
    EXISTING_HASH=$(cat "$VERSION_FILE")
    if [ "$EXISTING_HASH" = "$COMMIT_HASH" ]; then
        echo "Game data already up-to-date (commit $COMMIT_SHORT)"
        exit 0
    fi
fi

# 下载 JSON 文件列表
BASE_URL="https://raw.githubusercontent.com/konkers/auto-artifactarium/$COMMIT_HASH/game_data"

FILES=(
    "gi_keys.json"
    "TextMapCHS.json"
    "AvatarExcelConfigData.json"
    "WeaponExcelConfigData.json"
    "MaterialExcelConfigData.json"
    "ReliquaryExcelConfigData.json"
    "ReliquaryMainPropExcelConfigData.json"
)

for f in "${FILES[@]}"; do
    echo "Downloading $f..."
    curl -L --fail --progress-bar -o "$ASSETS_DIR/$f" "$BASE_URL/$f" || {
        echo "ERROR: Failed to download $f"
        exit 1
    }
    SIZE=$(ls -lh "$ASSETS_DIR/$f" | awk '{print $5}')
    echo "  -> $SIZE"
done

# 记录版本
echo "$COMMIT_HASH" > "$VERSION_FILE"
echo "Game data synced to commit $COMMIT_HASH"

# 列出最终文件
echo ""
echo "=== Final assets/game_data/ contents ==="
ls -lh "$ASSETS_DIR/"
