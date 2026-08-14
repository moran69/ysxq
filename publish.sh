#!/bin/bash
# publish.sh - 编译并发布 MOMO APK 到分发目录
# 用法: ./publish.sh [versionName] [updateLog]
# 示例: ./publish.sh 1.5.0 "新增弹幕播放器、进度条优化、用户登录"

set -e

PROJECT_DIR="/opt/ysxq"
RELEASE_DIR="/www/wwwroot/momo-release"

# 参数
VERSION_NAME="${1:-}"
UPDATE_LOG="${2:-功能更新}"

if [ -z "$VERSION_NAME" ]; then
    echo "用法: $0 <versionName> <updateLog>"
    echo "示例: $0 1.5.0 \"新增弹幕播放器\""
    exit 1
fi

# 计算 versionCode (从版本名生成，如 1.5.0 -> 150)
VC_PARTS=(${VERSION_NAME//./ })
VERSION_CODE=$(( ${VC_PARTS[0]} * 100 + ${VC_PARTS[1]:-0} * 10 + ${VC_PARTS[2]:-0} ))

echo "=== 发布 MOMO v${VERSION_NAME} (versionCode=${VERSION_CODE}) ==="

# 更新 build.gradle.kts
cd "$PROJECT_DIR"
sed -i "s/versionCode = .*/versionCode = ${VERSION_CODE}/" app/build.gradle.kts
sed -i "s/versionName = \"[^\"]*\"/versionName = \"${VERSION_NAME}\"/" app/build.gradle.kts
echo "✓ 已更新 build.gradle.kts: versionCode=${VERSION_CODE}, versionName=${VERSION_NAME}"

# 编译
echo "=== 开始编译 ==="
./gradlew assembleDebug --console=plain 2>&1 | tail -5

APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_SRC" ]; then
    echo "✗ 编译失败，APK 未生成"
    exit 1
fi

APK_SIZE=$(stat -c%s "$APK_SRC")
APK_MD5=$(md5sum "$APK_SRC" | awk '{print $1}')
echo "✓ 编译成功: ${APK_SIZE} bytes, MD5=${APK_MD5}"

# 复制 APK 到分发目录
APK_DST="${RELEASE_DIR}/momo-${VERSION_NAME}.apk"
cp "$APK_SRC" "$APK_DST"

# 保留最新版作为 latest.apk
cp "$APK_SRC" "${RELEASE_DIR}/latest.apk"

# 生成版本信息 JSON
cat > "${RELEASE_DIR}/update.json" << EOF
{
  "versionName": "${VERSION_NAME}",
  "versionCode": ${VERSION_CODE},
  "apkUrl": "http://161.118.252.183/release/latest.apk",
  "apkSize": ${APK_SIZE},
  "md5": "${APK_MD5}",
  "forceUpdate": false,
  "updateLog": "${UPDATE_LOG}",
  "publishTime": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
}
EOF

echo "✓ 已生成 update.json"
cat "${RELEASE_DIR}/update.json"

echo ""
echo "=== 发布完成 ==="
echo "下载地址: http://161.118.252.183:8899/release/latest.apk"
echo "版本信息: http://161.118.252.183:8899/release/update.json"
