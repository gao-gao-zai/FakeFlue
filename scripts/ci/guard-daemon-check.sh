#!/usr/bin/env bash
# P5 导航守护恢复路径（R29、R30）：在 root 模拟器上直接运行 StatusBarGuardDaemon，
# 模拟应用意外退出（标准输入断开），记录两种结局：
#   A. 应用被重启起来但没有接管 → 解除限制，不打开外部桌面，留下 no-takeover 记录；
#   B. 重启目标打不开 → 用完 3 次预算 → 解除限制、打开外部桌面、留下 budget-exhausted 记录。
# 只记录和判定，不让 CI 失败；结论写在 <输出目录>/result.txt。
# 用法：guard-daemon-check.sh <输出目录>（APK 须已安装）
set -u
OUT="$1"
PKG=com.gaozai.surface
CLS=com.flue.launcher.surface.system.StatusBarGuardDaemon
mkdir -p "$OUT"
log() { echo "[guard] $*" | tee -a "$OUT/result.txt"; }
flags() { adb shell dumpsys statusbar | grep -E "mDisabled1|mDisabled2" | head -2 | tr -s ' ' | tr '\n' ' '; }

adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
APK=$(adb shell pm path $PKG | head -1 | sed 's/^package://' | tr -d '\r')
APP_UID=$(adb shell pm list packages -U $PKG | grep -o 'uid:[0-9]*' | head -1 | cut -d: -f2 | tr -d '\r')
FALLBACK=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | tr -d '\r')
STATE=/data/user/0/$PKG/files/fflue_guard
adb shell run-as $PKG mkdir -p files/fflue_guard
adb shell "rm -f $STATE/recovery.log $STATE/guard.budget $STATE/guard.pid"
log "apk=$APK uid=$APP_UID fallback=$FALLBACK"

run_daemon() { # $1=重启组件 $2=名字
  adb shell "(echo FALLBACK $FALLBACK; echo SET 16842752 5; sleep 5) | CLASSPATH=$APK app_process /system/bin --nice-name=fflue_guard $CLS $PKG $APP_UID $APK $STATE $1" > "$OUT/$2.daemon.txt" 2>&1 &
  sleep 3
  log "$2 while connected: $(flags)"
  grep -q "^APPLIED" "$OUT/$2.daemon.txt" && log "$2 reply: $(grep -m1 '^APPLIED' "$OUT/$2.daemon.txt")" || log "$2 reply: $(head -3 "$OUT/$2.daemon.txt" | tr '\n' ' ')"
}

# A：重启本应用（引导页能正常起来），应用没有接管。
adb shell am force-stop $PKG
run_daemon "$PKG/com.flue.launcher.LauncherActivity" A
sleep 4
log "A after disconnect (recovering): $(flags)"
sleep 16
log "A end: $(flags)"
log "A guard alive: $(adb shell pidof fflue_guard >/dev/null && echo yes || echo no)"
log "A record: $(adb shell cat $STATE/recovery.log 2>/dev/null | tail -1 | tr '\t' ' ')"
log "A record owner: $(adb shell ls -lZ $STATE/recovery.log 2>/dev/null | tr -s ' ')"

# B：重启目标不存在，预算 3 次 × 10 秒后放弃并打开外部桌面。
adb shell am force-stop $PKG
adb shell "rm -f $STATE/guard.budget"
run_daemon "$PKG/does.not.Exist" B
sleep 12
log "B budget mid-recovery: $(adb shell cat $STATE/guard.budget 2>/dev/null)"
sleep 30
log "B end: $(flags)"
log "B guard alive: $(adb shell pidof fflue_guard >/dev/null && echo yes || echo no)"
log "B record: $(adb shell cat $STATE/recovery.log 2>/dev/null | tail -1 | tr '\t' ' ')"
log "B resumed: $(adb shell dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity' | tr -s ' ')"

adb shell "pkill -9 -f fflue_guard" >/dev/null 2>&1
adb unroot >/dev/null 2>&1; sleep 3; adb wait-for-device
exit 0
