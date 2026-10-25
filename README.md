# PadKite

Control one Android device from another wirelessly. Fork of [yutils/Easycontrol](https://github.com/yutils/Easycontrol) 1.6.1.

## Setup

The controlled device must accept ADB over the network. This is off by default and is lost on every reboot.

**Controlled device**

1. Turn on Developer options, then USB debugging or Wireless debugging.
2. Make ADB listen on port 5555, in one of two ways:
   - From a computer over USB: `adb tcpip 5555`
   - On the device itself (Android 11+), in Termux with `pkg install android-tools`. In Wireless debugging, tap *Pair device with pairing code* (use split screen to see both), then run the commands below. `<port>` is the one shown on the Wireless debugging screen.
     ```
     adb pair localhost:<pairing port> <pairing code>
     adb connect localhost:<port>
     adb tcpip 5555
     ```
3. Repeat step 2 after every reboot.

With a USB OTG cable between the two devices, step 2 is not needed.

**Controlling device**

1. Install the APK.
2. Allow *Display over other apps*. If Android greys it out as a restricted setting, first open App info → ⋮ → *Allow restricted settings*.
3. Add the device by its IP address. The ADB port is 5555.
4. On the first connect, accept the debugging prompt on the controlled device and tick *Always allow*.

Both devices must be on the same network or joined by a VPN. Audio forwarding needs Android 12 or later on the controlled device.

## Changes

- **Renamed to PadKite,** with its own package name `io.github.chillibeaver.padkite`. It installs next to EasyControl, not over it.
- **Android 16 black screen fixed.** On Android 14+ the server mirrors the screen with `DisplayManager.createVirtualDisplay(name, width, height, displayIdToMirror, surface)`, as scrcpy does, and falls back to `SurfaceControl` if that fails.
- **Resolution tiers follow the phone screen:** native, 80%, 60%, 40%.
- **Change resolution on connect can match the phone.** The tablet switches to the phone's screen resolution, orientation matched automatically, and switches back on disconnect.
- **Resolution is always restored.** This covers a normal disconnect, the adb shell closing, a dead network (30 s heartbeat timeout), the server being killed or crashing (a separate guard process restores it), and a normal reboot or shutdown. The previous size and density are backed up on the tablet first, so your own display size or the system's resolution setting comes back exactly. After a power loss, a direct `reboot`, or adb being restarted mid-session, the next connect restores from that backup.
- **Disconnect actions always run.** Lock, restore backlight and mute are done by the server when the session ends, so they also happen when the connection drops. Restore backlight now also works after the screen-off button.
- **Mute on disconnect fixed.** It used to set the media volume to 0 while audio was still being forwarded, which hit the forwarding volume instead of the speaker. The tablet kept playing aloud and the next session was silent on the phone. It now uses the system mute switch, which covers every output, and the next connect unmutes. A mute you set yourself is kept.
- **New controls after connecting.** Full screen is the default. A floating button opens one control panel. Drop it anywhere and it snaps to the nearest edge, then tucks half away when idle. Tap outside, press back or tap × to close it. Toggles such as nav bar, auto-rotate, mouse and screen off are labelled and highlighted when on. The nav bar holds only back, home and recents. The windowed mode uses the same panel. Everything you switch there (nav bar, auto-rotate, orientation, mouse, screen off) and the window positions are remembered per device.
- **Backlight toggle fixed.** Turning the backlight back on left the panel in doze (dim).
- **Density follows resolution.** When the resolution is changed, density is scaled by the same ratio, so the smallest width in dp stays as before and the launcher still fits. Your own display size setting is backed up on the tablet and restored afterwards.
- **Foreground service while connected,** so Android does not freeze the app in the background and drop the session.
- **Bitrate up to 50 Mbps.** It is picked automatically whenever resolution or FPS changes, and can still be set by hand.
- **Update checker removed.** The `com.kotlinx:appUpdate` library and its plain-HTTP check are gone. *Check for updates* in Settings just opens the latest GitHub release.
- **No public signing key.** Upstream ships its keystore and password, so you build with your own key.
- **Fork branding removed.**
- **Single-app casting removed.** It could not create its virtual display on Android 16 and fell back to a normal mirror.
- **External control broadcast removed.** Its sender check never worked, and it could run shell commands on the controlled device.
- **Device status fixed.** On Android 16 the battery showed the capacity level (3%) instead of the charge. Brightness now uses the vendor's range, resolution is the controlled device's screen, and encoding is the codec actually in use.

## Build

You need JDK 21 and the Android SDK (platforms 34 and 35). Set `ANDROID_HOME`, or put `sdk.dir=...` in `easycontrol/local.properties`.

Create a signing key once and keep it. Every later build must use the same key, or you will have to uninstall before updating. Without the key the release APK is left unsigned.

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
- Coming from EasyControl, add your devices again. To skip the debugging prompt on the controlled device, copy both keys from EasyControl's Settings → *Custom ADB key* into PadKite's.
- The server jar on the controlled device is named after the versionCode and a checksum of its contents, so a changed server is pushed again on the next connect.
- Port 25166 has no authentication. Do not expose it, or the ADB port, to the internet. Use a VPN instead.
- To see which capture path is in use, run `adb logcat -s Easycontrol_Server` on the controlled device.

## License

GPL-3.0. Based on [mingzhixian/Easycontrol](https://github.com/mingzhixian/Easycontrol), [yutils/Easycontrol](https://github.com/yutils/Easycontrol) and [scrcpy](https://github.com/Genymobile/scrcpy).
