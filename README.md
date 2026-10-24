# EasyControl: Android 16 black-screen fix

Control one Android device from another over ADB with low latency, based on the scrcpy protocol.

This is a patched fork of [yutils/Easycontrol](https://github.com/yutils/Easycontrol) 1.6.1, which is itself a fork of [mingzhixian/Easycontrol](https://github.com/mingzhixian/Easycontrol). It fixes the black video you get when the **controlled** device runs Android 16. Input still works in that case, but the picture never shows up.

| ![Screenshot](pic/screenshot/1.jpg) | ![Screenshot](pic/screenshot/3.jpg) | ![Screenshot](pic/screenshot/5.jpg) |
| -- | -- | -- |

## The problem

With upstream 1.6.1 on an Android 16 device, the session connects and touch input works, but the video stays black. Turning off H.265, lowering the resolution or the frame rate makes no difference. The toolbar screenshot does work, but only because it falls back to `screencap`, which is a separate path.

The cause is in screen capture, not in encoding. The server creates a display with `SurfaceControl`. On Android 15 and later it reaches that code through the internal `com.android.server.display.DisplayControl` class, loaded from `services.jar`. On Android 16 the display is created without any error, but nothing is ever drawn into it. The encoder only gets empty frames, so the encoding settings don't matter.

## The fix

On Android 14 and later, the server now mirrors the screen with the hidden static API that upstream scrcpy uses:

```java
android.hardware.display.DisplayManager.createVirtualDisplay(
        String name, int width, int height, int displayIdToMirror, Surface surface)
```

The system creates a virtual display that mirrors the main display and renders straight into the encoder's input surface. If this call fails, the server falls back to the original `SurfaceControl` path. Android 13 and older are unchanged.

## Changes from upstream 1.6.1

- **Screen capture:** the DisplayManager mirror described above, in `server/.../helper/VideoEncode.java` and `server/.../wrappers/DisplayManager.java`. The server also logs which capture path it used (see Troubleshooting).
- **Update checker removed:** upstream includes `com.kotlinx:appUpdate`, which contacts `http://apk.kotlinx.com:9999` on every launch over plain HTTP. It can force updates, and its checksum check can be skipped. This fork removes the library and the call.
- **Public signing key removed:** upstream commits `app/doc/yujingtest.jks` together with its password (`123456`). Anyone can use that key to sign an APK that installs over yours as an "update". This fork does not ship a key, so you build with your own (see Building).
- **Version bumped to 10602 / `1.6.1-a16fix`:** the client pushes the server to `/data/local/tmp/easycontrol_server_<versionCode>.jar`. Release builds skip the push when a file with that name already exists. Without the bump, a device that has run 1.6.1 keeps the old, unpatched server. **If you change server code, bump `versionCode` too.**

## Building

Requirements:

- JDK 21. The JBR that ships with Android Studio works.
- Android SDK with platforms 34 and 35.

1. Tell Gradle where the SDK is. Either set `ANDROID_HOME`, or create `easycontrol/local.properties`:

   ```properties
   sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
   ```

2. Generate your own signing key once and keep it. Later builds must be signed with the same key, or you will have to uninstall before every update.

   ```bash
   keytool -genkeypair -keystore easycontrol/app/doc/yujingtest.jks -storetype JKS \
     -alias test -keyalg RSA -keysize 2048 -validity 10000 \
     -storepass 123456 -keypass 123456 -dname "CN=private"
   ```

   The file name, alias and password match what `app/build.gradle` expects. What actually protects you is keeping the key file private: `*.jks` under `app/doc` is git-ignored. If you want a different password, change it in `app/build.gradle` as well.

3. Build:

   ```bash
   # Windows
   .\easycontrol\gradlew.bat --no-daemon assembleRelease -p easycontrol

   # Linux / macOS
   ./easycontrol/gradlew --no-daemon assembleRelease -p easycontrol
   ```

   Output: `easycontrol/app/build/outputs/apk/release/Yikong•Yuji_v1.6.1-a16fix_release.apk`

## Installing

- Install the APK on the **controlling** device only. The client pushes the server to the controlled device over ADB.
- The controlled device needs ADB over the network: wireless debugging, or `adb tcpip 5555`.
- If an upstream build is installed, uninstall it first, because the signatures differ. Uninstalling clears your saved devices.
- On the first connection, the controlled device asks you to authorize the client's ADB key. Tick "Always allow from this computer". You need the device in your hands for this step.
- On Android 13 and later you may need to "Allow restricted settings" for the sideloaded app before you can grant the overlay permission.

## Troubleshooting

Run this on the controlled device:

```bash
adb logcat -d -s Easycontrol_Server
```

| Log line | Meaning |
| -- | -- |
| `Display: using DisplayManager mirror` | The new capture path is active. |
| `DisplayManager mirror failed, falling back to SurfaceControl: ...` | The mirror failed; the exception explains why. |
| `Display: using SurfaceControl` | The old path is in use (Android 13 or older, or after the failure above). |

## Security notes

- The server listens on TCP port 25166 on every network interface, with no authentication and no encryption. Whoever connects first gets control. **Never expose 25166 or the ADB port to the internet.** For remote use, reach your home network through a VPN such as WireGuard.
- Known issue, not yet fixed: the client's exported broadcast receiver has a weak caller check. Exploiting it needs a malicious app on the same device, so the risk is low.

## Tested on

- Controller: OnePlus 12R
- Controlled: OnePlus Pad 3 (Android 16)

No other devices have been tested.

## Usage and features

The upstream usage guide is in [HOW_TO_USE.md](HOW_TO_USE.md) (Chinese).

Features inherited from upstream include audio forwarding, multiple devices, wired connections, clipboard sync, sharing the controller's physical keyboard, adaptive resolution, rotation, windowed and full-screen modes, remote screenshots, a virtual mouse, remote volume control and a device status panel.

## Credits

- [mingzhixian/Easycontrol](https://github.com/mingzhixian/Easycontrol): the original project
- [yutils/Easycontrol](https://github.com/yutils/Easycontrol): the fork this one is based on (Android 15 support, UI rework, many fixes)
- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy): the protocol, and the DisplayManager capture approach used here

## License

GPL-3.0, the same as upstream. See [LICENSE](LICENSE). Upstream copyright notices are kept. This fork's modifications are listed above and in the git history.
