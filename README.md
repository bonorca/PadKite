# EasyControl

Control an Android device from another Android device. Fork of [yutils/Easycontrol](https://github.com/yutils/Easycontrol) 1.6.1.

## Changes

- **Android 16 black screen fixed.** On Android 14+ the server mirrors the screen with `DisplayManager.createVirtualDisplay(name, width, height, displayIdToMirror, surface)`, as scrcpy does, and falls back to `SurfaceControl` if that fails.
- **Resolution tiers follow the phone screen:** native, 80%, 60%, 40%.
- **Change resolution on connect can match the phone.** The tablet switches to the phone's screen resolution, orientation matched automatically, and switches back on disconnect.
- **Bitrate up to 50 Mbps.** It is picked automatically whenever resolution or FPS changes, and can still be set by hand.
- **Update checker removed.** The `com.kotlinx:appUpdate` library and its plain-HTTP check are gone.
- **No public signing key.** Upstream ships its keystore and password, so you build with your own key.
- **Fork branding removed.**

## Build

You need JDK 21 and the Android SDK (platforms 34 and 35). Set `ANDROID_HOME`, or put `sdk.dir=...` in `easycontrol/local.properties`.

Create a signing key once and keep it. Every later build must use the same key, or you will have to uninstall before updating.

```bash
keytool -genkeypair -keystore easycontrol/app/doc/release.jks -storetype JKS \
  -alias test -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 123456 -keypass 123456 -dname "CN=private"
```

Then build:

```bash
./easycontrol/gradlew --no-daemon assembleRelease -p easycontrol
```

The APK is written to `easycontrol/app/build/outputs/apk/release/`.

## Notes

- Install it on the controlling device only.
- After you change server code, bump `versionCode`. The client only pushes the server when `easycontrol_server_<versionCode>.jar` is missing.
- Port 25166 has no authentication. Do not expose it, or the ADB port, to the internet. Use a VPN instead.
- To see which capture path is in use, run `adb logcat -s Easycontrol_Server` on the controlled device.

## License

GPL-3.0. Based on [mingzhixian/Easycontrol](https://github.com/mingzhixian/Easycontrol), [yutils/Easycontrol](https://github.com/yutils/Easycontrol) and [scrcpy](https://github.com/Genymobile/scrcpy).
