/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package top.saymzx.easycontrol.server.wrappers;

import android.os.IInterface;
import android.view.Display;
import android.view.IRotationWatcher;

import java.lang.reflect.Method;

public final class WindowManager {
    private static IInterface manager;
    private static Class<?> CLASS;

    // 旋转冻结/解冻方法的多版本探测（Android 14 QPR3/15新增caller参数）
    private static Method freezeRotationMethod = null;          // 旧: freezeRotation(int)
    private static Method freezeDisplayRotationMethod = null;   // 中: freezeDisplayRotation(int, int)
    private static Method freezeDisplayRotationCallerMethod = null; // 新: freezeDisplayRotation(int, int, String)
    private static int freezeMethodVersion = -1; // 0=caller, 1=displayId, 2=old

    private static Method isRotationFrozenMethod = null;        // 旧: isRotationFrozen()
    private static Method isDisplayRotationFrozenMethod = null; // 新: isDisplayRotationFrozen(int)
    private static int isFrozenMethodVersion = -1; // 0=displayId, 1=old

    private static Method thawRotationMethod = null;            // 旧: thawRotation()
    private static Method thawDisplayRotationMethod = null;     // 中: thawDisplayRotation(int)
    private static Method thawDisplayRotationCallerMethod = null; // 新: thawDisplayRotation(int, String)
    private static int thawMethodVersion = -1; // 0=caller, 1=displayId, 2=old

    public static void init(IInterface m) {
        manager = m;
        CLASS = manager.getClass();
        // 探测freezeRotation方法版本
        try {
            // Android 15 preview / 14 QPR3: freezeDisplayRotation(int, int, String caller)
            freezeDisplayRotationCallerMethod = manager.getClass().getMethod("freezeDisplayRotation", int.class, int.class, String.class);
            freezeMethodVersion = 0;
        } catch (NoSuchMethodException e1) {
            try {
                // Android 11+: freezeDisplayRotation(int, int)
                freezeDisplayRotationMethod = manager.getClass().getMethod("freezeDisplayRotation", int.class, int.class);
                freezeMethodVersion = 1;
            } catch (NoSuchMethodException e2) {
                try {
                    freezeRotationMethod = manager.getClass().getMethod("freezeRotation", int.class);
                    freezeMethodVersion = 2;
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        // 探测isRotationFrozen方法版本
        try {
            isDisplayRotationFrozenMethod = manager.getClass().getMethod("isDisplayRotationFrozen", int.class);
            isFrozenMethodVersion = 0;
        } catch (NoSuchMethodException e1) {
            try {
                isRotationFrozenMethod = manager.getClass().getMethod("isRotationFrozen");
                isFrozenMethodVersion = 1;
            } catch (NoSuchMethodException ignored) {
            }
        }
        // 探测thawRotation方法版本
        try {
            // Android 15 preview / 14 QPR3: thawDisplayRotation(int, String caller)
            thawDisplayRotationCallerMethod = manager.getClass().getMethod("thawDisplayRotation", int.class, String.class);
            thawMethodVersion = 0;
        } catch (NoSuchMethodException e1) {
            try {
                // Android 11+: thawDisplayRotation(int)
                thawDisplayRotationMethod = manager.getClass().getMethod("thawDisplayRotation", int.class);
                thawMethodVersion = 1;
            } catch (NoSuchMethodException e2) {
                try {
                    thawRotationMethod = manager.getClass().getMethod("thawRotation");
                    thawMethodVersion = 2;
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
    }

    public static void freezeRotation(int displayId, int rotation) {
        try {
            switch (freezeMethodVersion) {
                case 0:
                    freezeDisplayRotationCallerMethod.invoke(manager, displayId, rotation, "easycontrol#freezeRotation");
                    break;
                case 1:
                    freezeDisplayRotationMethod.invoke(manager, displayId, rotation);
                    break;
                default:
                    if (displayId == Display.DEFAULT_DISPLAY)
                        freezeRotationMethod.invoke(manager, rotation);
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    public static boolean isRotationFrozen(int displayId) {
        try {
            switch (isFrozenMethodVersion) {
                case 0:
                    return (boolean) isDisplayRotationFrozenMethod.invoke(manager, displayId);
                default:
                    if (displayId == Display.DEFAULT_DISPLAY)
                        return (boolean) isRotationFrozenMethod.invoke(manager);
                    return false;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void thawRotation(int displayId) {
        try {
            switch (thawMethodVersion) {
                case 0:
                    thawDisplayRotationCallerMethod.invoke(manager, displayId, "easycontrol#thawRotation");
                    break;
                case 1:
                    thawDisplayRotationMethod.invoke(manager, displayId);
                    break;
                default:
                    if (displayId == Display.DEFAULT_DISPLAY) thawRotationMethod.invoke(manager);
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    // 直接调窗口服务恢复分辨率和 density：比 wm 命令(要起进程)快得多，reboot 时能抢在系统服务被停掉之前
    // size 是 reset 或 WxH，density 是 reset 或数字；用户 -2 即 USER_CURRENT，和 wm density 一样
    public static boolean setDisplay(String size, String density) {
        try {
            if (size.equals("reset")) CLASS.getMethod("clearForcedDisplaySize", int.class).invoke(manager, Display.DEFAULT_DISPLAY);
            else {
                String[] wh = size.split("x");
                CLASS.getMethod("setForcedDisplaySize", int.class, int.class, int.class).invoke(manager, Display.DEFAULT_DISPLAY, Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
            }
            if (density.equals("reset"))
                CLASS.getMethod("clearForcedDisplayDensityForUser", int.class, int.class).invoke(manager, Display.DEFAULT_DISPLAY, -2);
            else
                CLASS.getMethod("setForcedDisplayDensityForUser", int.class, int.class, int.class).invoke(manager, Display.DEFAULT_DISPLAY, Integer.parseInt(density), -2);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void registerRotationWatcher(IRotationWatcher rotationWatcher, int displayId) {
        try {
            try {
                CLASS.getMethod("watchRotation", IRotationWatcher.class, int.class).invoke(manager, rotationWatcher, displayId);
            } catch (NoSuchMethodException e) {
                CLASS.getMethod("watchRotation", IRotationWatcher.class).invoke(manager, rotationWatcher);
            }
        } catch (Exception ignored) {
        }
    }

}
