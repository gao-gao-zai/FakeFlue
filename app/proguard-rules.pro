# Add project specific ProGuard rules here.
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class com.flue.launcher.data.model.** { *; }

# LuaJ's JSE artifact bundles a desktop javax.script service provider. Flue uses
# only the VM/compiler directly; Android has no javax.script API and this
# provider is unreachable from the watch-face sandbox.
-dontwarn javax.script.ScriptEngineFactory

# R29：Root 导航守护由 app_process 按类名启动，入口和它用到的协议类不能被裁剪或改名。
-keep class com.flue.launcher.surface.system.StatusBarGuardDaemon { public static void main(java.lang.String[]); }
-keep class com.flue.launcher.surface.system.** { *; }
