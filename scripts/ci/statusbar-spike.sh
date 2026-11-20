#!/usr/bin/env bash
# P0 导航限制验证（R29 前置）：在 CI 模拟器上用 root app_process 带 token 调用
# IStatusBarService.disable/disable2，记录限制前后、进程被杀后的系统状态。
#
# 用法：statusbar-spike.sh <debug-apk> <输出目录>
# 只做记录和判定，不让 CI 失败；结论写在 <输出目录>/result.txt。
set -u
APK="$1"
OUT="$2"
PKG=com.gaozai.surface
CLS=com.flue.launcher.surface.spike.StatusBarDisableSpike
mkdir -p "$OUT"

log() { echo "[spike] $*" | tee -a "$OUT/result.txt"; }
snap() {
  local name="$1"
  adb shell dumpsys statusbar > "$OUT/$name.statusbar.txt" 2>&1
  adb shell dumpsys activity activities 2>/dev/null | grep -E "mResumedActivity|topResumedActivity|ResumedActivity:" > "$OUT/$name.resumed.txt"
  adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" > "$OUT/$name.focus.txt"
}
flags() { grep -E "mDisabled1|mDisabled2|disable1|disable2" "$OUT/$1.statusbar.txt" | head -4 | tr -s ' ' | tr '\n' ' '; }

log "api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
log "adb uid: $(adb shell id -u | tr -d '\r')"

adb install -r -t "$APK" > "$OUT/install.txt" 2>&1 || { log "install failed"; cat "$OUT/install.txt"; exit 0; }
APK_PATH=$(adb shell pm path $PKG | head -1 | sed 's/^package://' | tr -d '\r')
log "apk on device: $APK_PATH"

snap before
log "before: $(flags before)"

adb shell "CLASSPATH=$APK_PATH app_process /system/bin $CLS" > "$OUT/helper.txt" 2>&1 &
HELPER=$!
sleep 6
cat "$OUT/helper.txt" | tee -a "$OUT/result.txt"
snap disabled
log "disabled: $(flags disabled)"
grep -q "$PKG" "$OUT/disabled.statusbar.txt" && log "disable record mentions $PKG: yes" || log "disable record mentions $PKG: no"

# 下拉通知栏、最近任务应被屏蔽；Home、返回应可用
adb shell cmd statusbar expand-notifications >/dev/null 2>&1; sleep 2
adb shell dumpsys window | grep -E "mCurrentFocus" > "$OUT/disabled.after-expand.txt"
adb shell cmd statusbar collapse >/dev/null 2>&1
adb shell input keyevent KEYCODE_APP_SWITCH; sleep 2
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" > "$OUT/disabled.after-recents.txt"
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" > "$OUT/disabled.after-home.txt"
log "while disabled, after expand: $(tr -s ' ' < "$OUT/disabled.after-expand.txt" | tr '\n' ' ')"
log "while disabled, after recents: $(tr -s ' ' < "$OUT/disabled.after-recents.txt" | tr '\n' ' ')"
log "while disabled, after home: $(tr -s ' ' < "$OUT/disabled.after-home.txt" | tr '\n' ' ')"

# 杀掉 helper（模拟崩溃），限制应自动解除
adb shell "pkill -9 -f $CLS" ; kill "$HELPER" 2>/dev/null; sleep 4
snap killed
log "after kill: $(flags killed)"
grep -q "$PKG" "$OUT/killed.statusbar.txt" && log "disable record after kill mentions $PKG: yes (残留)" || log "disable record after kill mentions $PKG: no (已解除)"

adb shell cmd statusbar expand-notifications >/dev/null 2>&1; sleep 2
adb shell dumpsys window | grep -E "mCurrentFocus" > "$OUT/killed.after-expand.txt"
adb shell cmd statusbar collapse >/dev/null 2>&1
log "after kill, after expand: $(tr -s ' ' < "$OUT/killed.after-expand.txt" | tr '\n' ' ')"

adb unroot >/dev/null 2>&1; sleep 3; adb wait-for-device
exit 0
