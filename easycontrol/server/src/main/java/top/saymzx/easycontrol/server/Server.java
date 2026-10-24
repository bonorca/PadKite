/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package top.saymzx.easycontrol.server;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.IInterface;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import top.saymzx.easycontrol.server.entity.Device;
import top.saymzx.easycontrol.server.entity.Options;
import top.saymzx.easycontrol.server.helper.AudioEncode;
import top.saymzx.easycontrol.server.helper.ControlPacket;
import top.saymzx.easycontrol.server.helper.VideoEncode;
import top.saymzx.easycontrol.server.wrappers.ClipboardManager;
import top.saymzx.easycontrol.server.wrappers.DisplayManager;
import top.saymzx.easycontrol.server.wrappers.InputManager;
import top.saymzx.easycontrol.server.wrappers.PowerManager;
import top.saymzx.easycontrol.server.wrappers.SurfaceControl;
import top.saymzx.easycontrol.server.wrappers.WindowManager;
import top.saymzx.easycontrol.server.wrappers.AudioManager;

// 此部分代码摘抄借鉴了著名投屏软件Scrcpy的开源代码(https://github.com/Genymobile/scrcpy/tree/master/server)
public final class Server {
    private static Socket mainSocket;
    private static Socket videoSocket;
    private static OutputStream mainOutputStream;
    private static OutputStream videoOutputStream;
    public static DataInputStream mainInputStream;

    private static final Object object = new Object();

    private static final int timeoutDelay = 1000 * 20;

    public static void main(String... args) {
        // 任何线程崩溃都走正常释放(先恢复分辨率)，默认处理会直接杀进程
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            e.printStackTrace();
            release();
        });
        try {
            Thread timeOutThread = new Thread(() -> {
                try {
                    Thread.sleep(timeoutDelay);
                    release();
                } catch (InterruptedException ignored) {
                }
            });
            timeOutThread.start();
            // 解析参数
            Options.parse(args);
            // 初始化
            setManagers();
            Device.init();
            // 连接
            connectClient();
            // 初始化子服务
            boolean canAudio = AudioEncode.init();
            VideoEncode.init();
            // 启动
            ArrayList<Thread> threads = new ArrayList<>();
            threads.add(new Thread(Server::executeVideoOut));
            if (canAudio) {
                threads.add(new Thread(Server::executeAudioIn));
                threads.add(new Thread(Server::executeAudioOut));
            }
            threads.add(new Thread(Server::executeControlIn));
            threads.add(new Thread(Server::executeKeepAliveCheck));
            threads.add(new Thread(Server::executeShutdownCheck));
            lastKeepAliveTime = System.currentTimeMillis();
            for (Thread thread : threads) thread.setPriority(Thread.MAX_PRIORITY);
            for (Thread thread : threads) thread.start();
            // 程序运行
            timeOutThread.interrupt();
            synchronized (object) {
                object.wait();
            }
            // 终止子服务
            for (Thread thread : threads) thread.interrupt();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // 释放资源
            release();
        }
    }

    private static Method GET_SERVICE_METHOD;

    @SuppressLint({"DiscouragedPrivateApi", "PrivateApi"})
    private static void setManagers() throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        GET_SERVICE_METHOD = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class);
        // 1
        WindowManager.init(getService("window", "android.view.IWindowManager"));
        // 2
        DisplayManager.init(Class.forName("android.hardware.display.DisplayManagerGlobal").getDeclaredMethod("getInstance").invoke(null));
        // 3
        Class<?> inputManagerClass;
        try {
            inputManagerClass = Class.forName("android.hardware.input.InputManagerGlobal");
        } catch (ClassNotFoundException e) {
            inputManagerClass = android.hardware.input.InputManager.class;
        }
        InputManager.init(inputManagerClass.getDeclaredMethod("getInstance").invoke(null));
        // 4
        ClipboardManager.init(getService("clipboard", "android.content.IClipboard"));
        // 5
        SurfaceControl.init();
        // 6
        PowerManager.init(getService("power", "android.os.IPowerManager"));
        // 7
        AudioManager.init(getService("audio", "android.media.IAudioService"));
    }

    private static IInterface getService(String service, String type) {
        try {
            IBinder binder = (IBinder) GET_SERVICE_METHOD.invoke(null, service);
            Method asInterfaceMethod = Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class);
            return (IInterface) asInterfaceMethod.invoke(null, binder);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void connectClient() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(Options.serverPort)) {
            mainSocket = serverSocket.accept();
            videoSocket = serverSocket.accept();
            mainOutputStream = mainSocket.getOutputStream();
            videoOutputStream = videoSocket.getOutputStream();
            mainInputStream = new DataInputStream(mainSocket.getInputStream());
        }
    }

    private static void executeVideoOut() {
        try {
            int frame = 0;
            long lastKeepActiveTime = 0;
            while (!Thread.interrupted()) {
                if (VideoEncode.isHasChangeConfig) {
                    VideoEncode.isHasChangeConfig = false;
                    VideoEncode.stopEncode();
                    VideoEncode.startEncode();
                }
                VideoEncode.encodeOut();
                frame++;
                // 每60帧（约1秒）发送一次userActivity防息屏
                if (frame % 60 == 0) {
                    Device.keepActive();
                    frame = 0;
                }
            }
        } catch (Exception e) {
            errorClose(e);
        }
    }

    // 心跳检测单独一个线程：画面静止或网络卡死时视频线程会阻塞，放在视频循环里检测不到断线
    private static void executeKeepAliveCheck() {
        try {
            while (!Thread.interrupted()) {
                Thread.sleep(1000);
                if (System.currentTimeMillis() - lastKeepAliveTime > timeoutDelay)
                    throw new IOException("连接断开");
            }
        } catch (Exception e) {
            errorClose(e);
        }
    }

    // 平板关机/重启：抢在窗口服务停掉前恢复分辨率
    // sys.shutdown.requested 是系统关机流程最开头写的，sys.powerctl 是 reboot 命令直接交给 init 的
    private static void executeShutdownCheck() {
        Method get;
        String initial;
        try {
            get = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            initial = get.invoke(null, "sys.shutdown.requested") + "|" + get.invoke(null, "sys.powerctl");
        } catch (Exception e) {
            return;
        }
        try {
            while (!Thread.interrupted()) {
                Thread.sleep(100);
                if (!initial.equals(get.invoke(null, "sys.shutdown.requested") + "|" + get.invoke(null, "sys.powerctl")))
                    throw new IOException("系统关机");
            }
        } catch (Exception e) {
            errorClose(e);
        }
    }

    private static void executeAudioIn() {
        while (!Thread.interrupted()) AudioEncode.encodeIn();
    }

    private static void executeAudioOut() {
        try {
            while (!Thread.interrupted()) AudioEncode.encodeOut();
        } catch (Exception e) {
            errorClose(e);
        }
    }

    private static volatile long lastKeepAliveTime = System.currentTimeMillis();

    private static void executeControlIn() {
        try {
            while (!Thread.interrupted()) {
                switch (Server.mainInputStream.readByte()) {
                    case 1:
                        ControlPacket.handleTouchEvent();
                        break;
                    case 2:
                        ControlPacket.handleKeyEvent();
                        break;
                    case 3:
                        ControlPacket.handleClipboardEvent();
                        break;
                    case 4:
                        lastKeepAliveTime = System.currentTimeMillis();
                        // 回复心跳响应，让客户端检测连接状态
                        Server.writeMain(ByteBuffer.wrap(new byte[]{4}));
                        break;
                    case 5:
                        Device.changeResolution(mainInputStream.readFloat());
                        break;
                    case 6:
                        Device.rotateDevice();
                        break;
                    case 7:
                        Device.changeScreenPowerMode(mainInputStream.readByte());
                        break;
                    case 8:
                        Device.changePower(mainInputStream.readInt());
                        break;
                    case 9:
                        Device.changeResolution(mainInputStream.readInt(), mainInputStream.readInt());
                        break;
                    case 10:
                        Device.changeVolume(mainInputStream.readInt());
                        break;
                    case 11:
                        ControlPacket.handleMouseEvent();
                        break;
                    case 12:
                        // 截图请求：独立线程执行截图，避免阻塞控制流；成功回传 [5][size][PNG]，失败回传 [5][0]
                        // 首选截 shadow display(与投屏画面一致，息屏/折叠不黑)，失败再回退 screencap
                        new Thread(() -> {
                            try {
                                byte[] png = SurfaceControl.captureDisplayPng(VideoEncode.getCaptureDisplay(), Device.videoSize.first, Device.videoSize.second);
                                // 多屏折叠设备上 screencap 默认会选到息屏的那块(黑图)，用默认显示的物理ID指定 -d
                                long captureId = SurfaceControl.getDefaultDisplayPhysicalId();
                                if (png == null && captureId >= 0) {
                                    try {
                                        png = Device.execReadOutputBytes("screencap -d " + captureId + " -p");
                                    } catch (Exception ignored) {
                                        // -d 物理ID无效等场景下 execReadOutputBytes 会抛异常，须继续回退到不带 -d 的 screencap
                                    }
                                }
                                if (png == null) png = Device.execReadOutputBytes("screencap -p");
                                ControlPacket.sendScreenshotEvent(png);
                            } catch (Exception ignored) {
                                try {
                                    ControlPacket.sendScreenshotEvent(new byte[0]);
                                } catch (Exception ignored2) {
                                }
                            }
                        }).start();
                        break;
                    case 13:
                        // 请求关键帧(录屏开始)：让编码器尽快输出I帧，缩短录屏首关键帧等待
                        VideoEncode.requestSyncFrame();
                        break;
                    case 14:
                        Device.changeResolutionMatch(mainInputStream.readInt(), mainInputStream.readInt());
                        break;
                }
            }
        } catch (Exception e) {
            errorClose(e);
        }
    }

    public synchronized static void writeMain(ByteBuffer byteBuffer) throws IOException {
        mainOutputStream.write(byteBuffer.array());
    }

    public static void writeVideo(ByteBuffer byteBuffer) throws IOException {
        videoOutputStream.write(byteBuffer.array());
    }

    // 直接释放：用 notify 唤醒主线程的话，主线程还没进 wait 时出错会丢通知，服务端就一直挂着
    public static void errorClose(Exception e) {
        e.printStackTrace();
        release();
    }

    private static final AtomicBoolean isReleased = new AtomicBoolean(false);

    // 释放资源：先恢复分辨率，后面哪步卡住也不影响；5秒还没退出就强制退出
    // 不能用 synchronized：writeMain 也锁 Server.class，断网时写阻塞会让这里永远等锁
    private static void release() {
        if (!isReleased.compareAndSet(false, true)) return;
        Thread haltThread = new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
            }
            Runtime.getRuntime().halt(0);
        });
        haltThread.setDaemon(true);
        haltThread.start();
        for (int i = 0; i < 4; i++) {
            try {
                switch (i) {
                    case 0:
                        Device.fallbackResolution();
                        break;
                    case 1:
                        mainInputStream.close();
                        mainSocket.close();
                        videoSocket.close();
                        break;
                    case 2:
                        VideoEncode.release();
                        AudioEncode.release();
                        break;
                    case 3:
                        Runtime.getRuntime().exit(0);
                        break;
                }
            } catch (Exception ignored) {
            }
        }
    }

}
