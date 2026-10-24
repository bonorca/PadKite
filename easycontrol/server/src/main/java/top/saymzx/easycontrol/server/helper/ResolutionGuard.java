package top.saymzx.easycontrol.server.helper;

import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

// 分辨率兜底：服务端不管怎么结束(被杀、崩溃、kill -9)，都由独立的守护进程把分辨率恢复
public final class ResolutionGuard {
    private static final String NAME = "easycontrol_guard";
    // stdin 接在服务端上，服务端一退出就读到 EOF，随即 wm size reset；系统服务重启时会失败，重试一分钟
    private static final String SCRIPT = "exec >/dev/null 2>&1; read x; i=0; until wm size reset; do i=$((i+1)); [ $i -ge 60 ] && break; sleep 1; done";
    // 必须一直持有，否则管道被回收关闭，守护进程会提前恢复
    private static Process process;

    // 先等上次会话的守护进程恢复完，免得它的 reset 落在本次改分辨率之后
    public static void waitPrevious() {
        try {
            for (int i = 0; i < 30 && findPrevious(false); i++) Thread.sleep(100);
            if (findPrevious(true)) Log.e("Easycontrol_Server", "ResolutionGuard: killed stale guard");
        } catch (Exception ignored) {
        }
    }

    public static void start() {
        try {
            // setsid 脱离 adb 的终端，断开时的 SIGHUP 带不走它
            process = new ProcessBuilder("setsid", "sh", "-c", SCRIPT, NAME).start();
        } catch (Exception e) {
            try {
                process = new ProcessBuilder("sh", "-c", SCRIPT, NAME).start();
            } catch (Exception e2) {
                Log.e("Easycontrol_Server", "ResolutionGuard: start failed " + e2);
            }
        }
    }

    private static boolean findPrevious(boolean kill) {
        boolean found = false;
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return false;
        for (File proc : procs) {
            String pid = proc.getName();
            if (pid.isEmpty() || !Character.isDigit(pid.charAt(0))) continue;
            try (FileInputStream in = new FileInputStream(new File(proc, "cmdline"))) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
                if (!out.toString().contains(NAME)) continue;
                found = true;
                if (kill) Os.kill(Integer.parseInt(pid), OsConstants.SIGKILL);
            } catch (Exception ignored) {
            }
        }
        return found;
    }
}
