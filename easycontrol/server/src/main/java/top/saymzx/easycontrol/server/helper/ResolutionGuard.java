package top.saymzx.easycontrol.server.helper;

import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import top.saymzx.easycontrol.server.entity.Device;
import top.saymzx.easycontrol.server.wrappers.WindowManager;

// 分辨率兜底：服务端不管怎么结束(被杀、崩溃、kill -9)，都由独立的守护进程把分辨率恢复
public final class ResolutionGuard {
    private static final String NAME = "easycontrol_guard";
    // 改之前的分辨率和 density 存在平板上，内容是 "<size> <density>"，各自是 reset 或原来的 Override
    // (用户调的"显示大小"、系统的屏幕分辨率设置)。文件在才说明是我们改过，所有恢复都只按它来，不碰别人设的值。
    // 不能叫 easycontrol_*，客户端换版本时会 rm easycontrol_*
    private static final String BACKUP = "/data/local/tmp/easycontrol-display";
    // stdin 接在服务端上，服务端一退出就读到 EOF。没有备份说明服务端已恢复，直接退出；
    // 有备份就先等 1 秒，让被杀的服务端还没跑完的 wm 命令先落地，再按备份恢复；系统服务重启时会失败，重试一分钟
    private static final String SCRIPT = "exec >/dev/null 2>&1; read x; f=" + BACKUP + "; [ -f $f ] || exit 0; sleep 1; i=0;"
            + " until read s d < $f && wm size $s && wm density $d && rm $f; do i=$((i+1)); [ $i -ge 60 ] && break; sleep 1; done";
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

    // 只记第一次改之前的值，同一会话再改分辨率不覆盖；先写临时文件再改名，断电也不会留下半个文件。
    // 返回 false 时不能改分辨率，否则恢复时不知道原值
    public static boolean backup(String size, String density) {
        File backup = new File(BACKUP);
        if (backup.exists()) return true;
        File tmp = new File(BACKUP + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write((size + " " + density + "\n").getBytes());
            out.getFD().sync();
        } catch (Exception e) {
            return false;
        }
        return tmp.renameTo(backup);
    }

    // isShutdown：平板正在关机/重启。直接调窗口服务抢时间；备份留着，系统来不及把设置写盘的话，下次连接再按它恢复一遍
    public static void restore(boolean isShutdown) throws IOException, InterruptedException {
        File backup = new File(BACKUP);
        if (!backup.exists()) return;
        String content;
        try (FileInputStream in = new FileInputStream(backup)) {
            byte[] buffer = new byte[64];
            int len = in.read(buffer);
            content = len > 0 ? new String(buffer, 0, len).trim() : "";
        }
        String[] parts = content.split("\\s+");
        boolean isValid = parts.length == 2 && parts[0].matches("reset|\\d+x\\d+") && parts[1].matches("reset|\\d+");
        if (isShutdown) {
            if (isValid && !WindowManager.setDisplay(parts[0], parts[1]))
                Device.execReadOutput("wm size " + parts[0] + " && wm density " + parts[1]);
            return;
        }
        // 内容坏了没法恢复，删掉免得每次连接都卡在这
        if (isValid) Device.execReadOutput("wm size " + parts[0] + " && wm density " + parts[1]);
        backup.delete();
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
