adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/manager.apk
adb shell su -c "cp /sdcard/Download/manager.apk /data/adb/modules/zygisk_lsposed/manager.apk&&exit"
adb shell am force-stop org.lsposed.manager
adb shell am start -n org.lsposed.manager/org.lsposed.manager.ui.activity.MainActivity