#!/usr/bin/env bash
# P0 真机能力矩阵采集：连上手表（adb devices 能看到）后运行，输出写到 <输出目录>。
# 只读：不安装、不修改任何系统设置。
# 用法：capability-matrix.sh [输出目录]   （默认 ./device-matrix-<序列号>）
set -u
SERIAL=$(adb get-serialno 2>/dev/null | tr -d '\r')
[ -z "$SERIAL" ] || [ "$SERIAL" = "unknown" ] && { echo "没有找到已连接的设备"; exit 1; }
OUT="${1:-./device-matrix-$SERIAL}"
mkdir -p "$OUT"
sh_() { adb shell "$@" 2>&1 | tr -d '\r'; }
row() { printf '%-28s %s\n' "$1" "$2" | tee -a "$OUT/matrix.txt"; }

: > "$OUT/matrix.txt"
row "型号" "$(sh_ getprop ro.product.manufacturer) $(sh_ getprop ro.product.model)"
row "Android / SDK" "$(sh_ getprop ro.build.version.release) / $(sh_ getprop ro.build.version.sdk)"
row "ROM 指纹" "$(sh_ getprop ro.build.fingerprint)"
row "ABI" "$(sh_ getprop ro.product.cpu.abilist)"
row "屏幕尺寸" "$(sh_ wm size | tr '\n' ' ')"
row "屏幕密度" "$(sh_ wm density | tr '\n' ' ')"
row "圆屏 (config_isScreenRound)" "$(sh_ getprop ro.config.screen_round) $(sh_ cmd uimode 2>/dev/null | head -1)"
row "adb root 可用" "$(adb root 2>&1 | head -1)"; adb wait-for-device; sleep 1
row "su 可用" "$(sh_ 'su -c id' | head -1)"
row "Magisk" "$(sh_ pm list packages com.topjohnwu.magisk)"
row "Shizuku" "$(sh_ pm list packages moe.shizuku.privileged.api)"
row "默认 HOME" "$(sh_ cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1)"
row "已装 HOME 桌面" "$(sh_ cmd package query-activities --brief -c android.intent.category.HOME -a android.intent.action.MAIN | grep / | tr '\n' ' ')"
row "旧 FFlue 版本" "$(sh_ dumpsys package com.gaozai.surface | grep -E 'versionCode|versionName' | head -2 | tr -s ' ' | tr '\n' ' ')"
row "原版 Flue 版本" "$(sh_ dumpsys package com.flue.launcher | grep -E 'versionCode|versionName' | head -2 | tr -s ' ' | tr '\n' ' ')"
row "statusbar 服务" "$(sh_ service check statusbar)"
row "导航模式" "$(sh_ settings get secure navigation_mode)"
row "手势 / 按键设置" "$(sh_ settings list secure | grep -iE 'gesture|swipe|double_tap|button' | tr '\n' ' ' | cut -c1-300)"
row "通知监听" "$(sh_ settings get secure enabled_notification_listeners)"
row "无障碍" "$(sh_ settings get secure enabled_accessibility_services)"
row "设备管理员" "$(sh_ dumpsys device_policy | grep -E 'admin=' | head -3 | tr '\n' ' ')"
row "默认电话 / 短信" "$(sh_ cmd role get-role-holders android.app.role.DIALER) / $(sh_ cmd role get-role-holders android.app.role.SMS)"
row "电话能力" "$(sh_ pm list features | grep -E 'telephony$|telephony.gsm|telephony.calling' | tr '\n' ' ')"
row "电池" "$(sh_ dumpsys battery | grep -E 'level|status' | tr -s ' ' | tr '\n' ' ')"

sh_ dumpsys statusbar > "$OUT/statusbar.txt"
sh_ dumpsys window displays > "$OUT/window-displays.txt"
sh_ getprop > "$OUT/getprop.txt"
sh_ pm list packages -f > "$OUT/packages.txt"
sh_ settings list global > "$OUT/settings-global.txt"
sh_ settings list secure > "$OUT/settings-secure.txt"
sh_ settings list system > "$OUT/settings-system.txt"
adb unroot >/dev/null 2>&1
echo "已写入 $OUT"
