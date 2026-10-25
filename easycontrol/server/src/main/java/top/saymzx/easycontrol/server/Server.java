/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package top.saymzx.easycontrol.server;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.IInterface;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

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

    private static final String TAG = "Easycontrol_Server";
    private static final int timeoutDelay = 1000 * 20;
    // 客户端每2秒发一次心跳、30秒收不到数据就自己断开，服务端等更久没有意义
    private static final int keepAliveTimeout = 1000 * 30;

    public static void main(String... args) {
        // 任何线程崩溃都走正常释放(先恢复分辨率)，默认处理会直接杀进程
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Log.e(TAG, "uncaught in " + t.getName(), e);
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
            Device.prepareVolume(canAudio);
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
            lastKeepAliveTime = SystemClock.elapsedRealtime();
            for (Thread thread : threads) thread.setPriority(Thread.MAX_PRIORITY);
            for (Thread thread : threads) thread.start();
            // 程序运行
            timeOutThread.interrupt();
            // 没人 notify，这里一直等到进程被结束；wait 可能无故醒来，要循环
            synchronized (object) {
                while (!isReleased.get()) object.wait();
            }
            // 终止子服务
            for (Thread thread : threads) thread.interrupt();
        } catch (Exception e) {
            Log.e(TAG, "main", e);
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

    // 心跳检测单独一个线程：平板息屏没有新帧、或网络卡死写阻塞时，视频线程会卡住，放在视频循环里检测不到断线
    private static void executeKeepAliveCheck() {
        try {
            while (!Thread.interrupted()) {
                Thread.sleep(1000);
                // 单调时钟：系统对时跳变不会误判断线
                if (SystemClock.elapsedRealtime() - lastKeepAliveTime > keepAliveTimeout)
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
                Thread.sleep(50);
                if (!initial.equals(get.invoke(null, "sys.shutdown.requested") + "|" + get.invoke(null, "sys.powerctl"))) {
                    isShutdown = true;
                    throw new IOException("系统关机");
                }
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

    private static volatile long lastKeepAliveTime = SystemClock.elapsedRealtime();

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
                        lastKeepAliveTime = SystemClock.elapsedRealtime();
                        // 收到第一个心跳才算会话建立：客户端没真正连上就放弃的连接，断开时不做锁定/静音
                        isConnected = true;
                        // 回复心跳放到别的线程，同时最多一个：慢网时主通道的写(截图、音频)会阻塞很久，
                        // 读线程卡在写上就读不到后面的心跳，会被误判断线
                        if (isReplyingKeepAlive.compareAndSet(false, true)) new Thread(() -> {
                            try {
                                writeMain(ByteBuffer.wrap(new byte[]{4}));
                            } catch (IOException ignored) {
                            } finally {
                                isReplyingKeepAlive.set(false);
                            }
                        }).start();
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

    private static final AtomicBoolean isReplyingKeepAlive = new AtomicBoolean(false);

    public synchronized static void writeMain(ByteBuffer byteBuffer) throws IOException {
        mainOutputStream.write(byteBuffer.array());
    }

    public static void writeVideo(ByteBuffer byteBuffer) throws IOException {
        videoOutputStream.write(byteBuffer.array());
    }

    // 直接释放：用 notify 唤醒主线程的话，主线程还没进 wait 时出错会丢通知，服务端就一直挂着
    // 只写 logcat：stderr 接在 adb 的终端上，断网时可能写满阻塞，恢复就开始不了
    public static void errorClose(Exception e) {
        Log.e(TAG, "close: " + e);
        release();
    }

    private static final AtomicBoolean isReleased = new AtomicBoolean(false);
    private static volatile boolean isConnected = false;
    private static volatile boolean isShutdown = false;

    // 释放资源：先恢复分辨率，后面哪步卡住也不影响；5秒还没退出就强制退出
    // 不能用 synchronized：writeMain 也锁 Server.class，断网时写阻塞会让这里永远等锁
    private static void release() {
        if (!isReleased.compareAndSet(false, true)) return;
        // 用 SIGKILL 不用 Runtime.halt：halt 和 exit 共用一把锁，exit 卡住时 halt 也进不去
        Thread haltThread = new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
            }
            try {
                Os.kill(Os.getpid(), OsConstants.SIGKILL);
            } catch (Exception e) {
                Runtime.getRuntime().halt(0);
            }
        });
        haltThread.setDaemon(true);
        haltThread.start();
        for (int i = 0; i < 4; i++) {
            try {
                switch (i) {
                    case 0:
                        // 断开时操作先做：都是很快的系统调用，分辨率之后哪步卡住、被强制结束，守护进程还会按备份恢复；
                        // 反过来的话恢复分辨率卡住，锁定和静音就没机会做了。关机时不用锁定，但用户的静音照样还回去
                        if (!isShutdown) {
                            Device.restoreScreenPower();
                            if (isConnected) Device.runCloseActions();
                        }
                        Device.finishVolume(isConnected && !isShutdown);
                        break;
                    case 1:
                        Device.fallbackResolution(isShutdown);
                        break;
                    case 2:
                        mainInputStream.close();
                        mainSocket.close();
                        videoSocket.close();
                        break;
                    case 3:
                        // 编码器、录音、镜像屏不手动释放，直接结束进程交给系统回收：音视频线程这时还在用，
                        // 别的线程去 stop/reset/release 会在 native 层崩溃(实测 MediaCodec.reset 断言失败、SIGSEGV)；
                        // exit 也一样，它跑析构时这些线程还在跑
                        Os.kill(Os.getpid(), OsConstants.SIGKILL);
                        break;
                }
            } catch (Exception ignored) {
                if (i == 3) Runtime.getRuntime().halt(0);
            }
        }
    }

}
