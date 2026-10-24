package top.saymzx.easycontrol.app.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import top.saymzx.easycontrol.app.R;
import top.saymzx.easycontrol.app.entity.AppData;

// 有会话时挂前台服务：易控切到后台或手机锁屏时不被系统冻结，心跳不断
public class ClientService extends Service {
    private static final String CHANNEL_ID = "connect";
    private static final int NOTIFICATION_ID = 1;
    private static ClientService instance;

    // 只在主线程调用
    static void update() {
        if (Client.isEmpty()) {
            // instance 为空说明服务还没跑到 onStartCommand，它起来后会自己停
            if (instance != null) {
                instance.stopSelf();
                instance = null;
            }
        } else if (instance == null) {
            Context context = AppData.applicationContext;
            Intent intent = new Intent(context, ClientService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
                else context.startService(intent);
            } catch (Exception ignored) {
                // 后台不允许启动前台服务时会话照常进行，只是没有保活
            }
        } else {
            NotificationManager manager = (NotificationManager) instance.getSystemService(Context.NOTIFICATION_SERVICE);
            manager.notify(NOTIFICATION_ID, instance.createNotification());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        // 必须先 startForeground，没调就 stopSelf 系统会让应用崩溃
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION_ID, createNotification());
        } catch (Exception e) {
            // 系统拒绝(权限、后台限制)时不能让主线程崩溃把会话一起带走
            stopSelf();
            instance = null;
            return START_NOT_STICKY;
        }
        if (Client.isEmpty()) {
            stopSelf();
            instance = null;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_connect), NotificationManager.IMPORTANCE_LOW));
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else builder = new Notification.Builder(this);
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        return builder.setSmallIcon(R.drawable.link)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_connected, Client.names()))
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
    }
}
