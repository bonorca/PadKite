# EasyControl

Control one Android device from another wirelessly: screen, touch, keys, audio and clipboard. Made for using a tablet from a phone. Works on Android 16.

Based on [yutils/Easycontrol](https://github.com/yutils/Easycontrol) 1.6.1. The connected interface, resolution handling and disconnect handling are rewritten.

## Features

- **Fills the phone screen.** On connect, the controlled device switches to the phone's resolution and orientation. Density scales with it, so layouts still fit. You can turn this off per device.
- **Always restored.** Resolution and density return to what they were after a disconnect, a dropped connection, a killed or crashed server, or a reboot. After a power loss, they are restored on the next connect.
- **One control panel.** Connections open full screen, with a nav bar of back, home and recents. A floating button opens the panel: volume, nav bar, auto-rotate, mouse mode, screen off, rotate, screenshot, power, status, window, minimize and disconnect. The button snaps to an edge and tucks away when idle. Window mode uses the same panel.
- **Remembered per device:** nav bar, auto-rotate and orientation, mouse mode, screen off, window positions.
- **Disconnect actions run on the controlled device.** Lock, restore backlight and mute also happen when the connection drops.
- **Audio forwarding** needs Android 12 or later on the controlled device. Mute on disconnect uses the system mute switch. A mute you set yourself is kept.
- **Bitrate** follows resolution and frame rate, up to 50 Mbps.

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

Both devices must be on the same network or joined by a VPN.

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
- There is no built-in update check. *Check for updates* in Settings opens the GitHub releases page.
- Port 25166 has no authentication. Do not expose it, or the ADB port, to the internet. Use a VPN instead.
- On Android 14+ the screen is captured with `DisplayManager.createVirtualDisplay(name, width, height, displayIdToMirror, surface)`, as scrcpy does, with `SurfaceControl` as the fallback. `adb logcat -s Easycontrol_Server` on the controlled device shows which path is used.
- The server jar on the controlled device is named after the versionCode and a checksum of its contents, so a changed server is pushed again on the next connect.

## License

GPL-3.0. Based on [mingzhixian/Easycontrol](https://github.com/mingzhixian/Easycontrol), [yutils/Easycontrol](https://github.com/yutils/Easycontrol) and [scrcpy](https://github.com/Genymobile/scrcpy).
