/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package top.saymzx.easycontrol.server.wrappers;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.os.IInterface;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.List;

// 通过 audio 服务(android.media.IAudioService)操作系统音量，
// 与系统设置音量使用同一路径，兼容各厂商ROM(部分ROM拦截 media_session 命令但不拦截此处)
public final class AudioManager {
    private static IInterface manager;
    private static Method setStreamVolumeMethod = null;
    private static Method getStreamMaxVolumeMethod = null;
    private static Method getStreamVolumeMethod = null;
    private static Method getDevicesForAttributesMethod = null;

    public static void init(IInterface m) {
        manager = m;
        if (manager == null) return;
        try {
            setStreamVolumeMethod = manager.getClass().getMethod("setStreamVolume", int.class, int.class, int.class, String.class);
        } catch (Exception ignored) {
        }
        try {
            getStreamMaxVolumeMethod = manager.getClass().getMethod("getStreamMaxVolume", int.class);
        } catch (Exception ignored) {
        }
        try {
            getStreamVolumeMethod = manager.getClass().getMethod("getStreamVolume", int.class);
        } catch (Exception ignored) {
        }
        try {
            getDevicesForAttributesMethod = manager.getClass().getMethod("getDevicesForAttributes", AudioAttributes.class);
        } catch (Exception ignored) {
        }
    }

    // 媒体声音现在是不是送进 REMOTE_SUBMIX(音频转发)：系统给它单独记一份音量，
    // 这时读写的 STREAM_MUSIC 音量都是转发那一份，不是平板外放。查不到返回 null
    public static Boolean isMediaOnRemoteSubmix() {
        if (getDevicesForAttributesMethod == null || manager == null) return null;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
            List<?> devices = (List<?>) getDevicesForAttributesMethod.invoke(manager, attributes);
            if (devices == null || devices.isEmpty()) return null;
            for (Object device : devices) {
                if ((Integer) device.getClass().getMethod("getType").invoke(device) == AudioDeviceInfo.TYPE_REMOTE_SUBMIX)
                    return true;
            }
            return false;
        } catch (Exception ignored) {
            return null;
        }
    }

    // 系统的“静音”开关(音量面板上那个)：对外放、转发、耳机所有输出一起生效，不改档位，取消静音回到原来的档位
    public static final int ADJUST_MUTE = -100;
    public static final int ADJUST_UNMUTE = 100;

    public static boolean adjustStreamVolume(int streamType, int direction) {
        if (manager == null) return false;
        try {
            manager.getClass().getMethod("adjustStreamVolume", int.class, int.class, int.class, String.class)
                    .invoke(manager, streamType, direction, 0, "com.android.shell");
            return true;
        } catch (Exception ignored) {
        }
        try {
            manager.getClass().getMethod("adjustStreamVolumeWithAttribution", int.class, int.class, int.class, String.class, String.class)
                    .invoke(manager, streamType, direction, 0, "com.android.shell", null);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // 查不到返回 null
    public static Boolean isStreamMute(int streamType) {
        if (manager == null) return null;
        try {
            return (Boolean) manager.getClass().getMethod("isStreamMute", int.class).invoke(manager, streamType);
        } catch (Exception ignored) {
            return null;
        }
    }

    // 等路由切到(或离开) REMOTE_SUBMIX，最多等 timeout 毫秒；查不到路由时直接返回 false
    public static boolean waitMediaOnRemoteSubmix(boolean expected, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (true) {
            Boolean now = isMediaOnRemoteSubmix();
            if (now == null) return false;
            if (now == expected) return true;
            if (SystemClock.elapsedRealtime() > deadline) return false;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return false;
            }
        }
    }

    // 当前音量档位，读不到返回 -1
    public static int getStreamVolume(int streamType) {
        if (getStreamVolumeMethod == null || manager == null) return -1;
        try {
            return (Integer) getStreamVolumeMethod.invoke(manager, streamType);
        } catch (Exception ignored) {
            return -1;
        }
    }

    public static int getStreamMaxVolume(int streamType) {
        if (getStreamMaxVolumeMethod == null || manager == null) return -1;
        try {
            return (Integer) getStreamMaxVolumeMethod.invoke(manager, streamType);
        } catch (Exception ignored) {
            return -1;
        }
    }

    // 设置流音量(绝对音量)，调用成功返回true
    public static boolean setStreamVolume(int streamType, int index) {
        if (setStreamVolumeMethod == null || manager == null) return false;
        try {
            setStreamVolumeMethod.invoke(manager, streamType, index, 0, "com.android.shell");
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
