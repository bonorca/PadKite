/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package top.saymzx.easycontrol.server.wrappers;

import android.hardware.display.VirtualDisplay;
import android.view.Surface;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import top.saymzx.easycontrol.server.entity.Device;
import top.saymzx.easycontrol.server.entity.DisplayInfo;

public final class DisplayManager {
    private static Object manager;

    public static void init(Object m) {
        manager = m;
    }

    private static DisplayInfo getDisplayInfoFromDumpsysDisplay(int displayId) {
        try {
            String dumpsysDisplayOutput = Device.execReadOutput("dumpsys display");
            Matcher m = Pattern.compile("mOverrideDisplayInfo=DisplayInfo.*?, displayId " + displayId + ".*?, real ([0-9]+) x ([0-9]+).*?, rotation ([0-9]+).*?, density ([0-9]+).*?, layerStack ([0-9]+)").matcher(dumpsysDisplayOutput);
            if (!m.find()) return null;
            int width = Integer.parseInt(Objects.requireNonNull(m.group(1)));
            int height = Integer.parseInt(Objects.requireNonNull(m.group(2)));
            int rotation = Integer.parseInt(Objects.requireNonNull(m.group(3)));
            int density = Integer.parseInt(Objects.requireNonNull(m.group(4)));
            int layerStack = Integer.parseInt(Objects.requireNonNull(m.group(5)));
            return new DisplayInfo(displayId, width, height, rotation, density, layerStack);
        } catch (Exception e) {
            return null;
        }
    }

    private static java.lang.reflect.Method createMirrorDisplayMethod;

    // Android 14+ hidden static API: mirror a display into a surface (same approach as upstream scrcpy)
    public static VirtualDisplay createMirrorDisplay(String name, int width, int height, int displayIdToMirror, Surface surface) throws Exception {
        if (createMirrorDisplayMethod == null)
            createMirrorDisplayMethod = android.hardware.display.DisplayManager.class.getMethod("createVirtualDisplay", String.class, int.class, int.class, int.class, Surface.class);
        return (VirtualDisplay) createMirrorDisplayMethod.invoke(null, name, width, height, displayIdToMirror, surface);
    }

    public static DisplayInfo getDisplayInfo(int displayId) {
        try {
            Object displayInfo = manager.getClass().getMethod("getDisplayInfo", int.class).invoke(manager, displayId);
            // fallback when displayInfo is null
            if (displayInfo == null) return getDisplayInfoFromDumpsysDisplay(displayId);
            Class<?> cls = displayInfo.getClass();
            // width and height already take the rotation into account
            int width = cls.getDeclaredField("logicalWidth").getInt(displayInfo);
            int height = cls.getDeclaredField("logicalHeight").getInt(displayInfo);
            int rotation = cls.getDeclaredField("rotation").getInt(displayInfo);
            int layerStack = cls.getDeclaredField("layerStack").getInt(displayInfo);
            int density = cls.getDeclaredField("logicalDensityDpi").getInt(displayInfo);
            return new DisplayInfo(displayId, width, height, rotation, density, layerStack);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
