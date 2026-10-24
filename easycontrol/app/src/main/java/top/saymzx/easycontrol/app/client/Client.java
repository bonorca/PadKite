package top.saymzx.easycontrol.app.client;

import android.app.Dialog;
import android.util.DisplayMetrics;
import android.util.Pair;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import top.saymzx.easycontrol.app.client.tools.AdbTools;
import top.saymzx.easycontrol.app.client.tools.ClientController;
import top.saymzx.easycontrol.app.client.tools.ClientPlayer;
import top.saymzx.easycontrol.app.client.tools.ClientStream;
import top.saymzx.easycontrol.app.client.tools.ControlPacket;
import top.saymzx.easycontrol.app.databinding.ItemLoadingBinding;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.entity.Device;
import top.saymzx.easycontrol.app.helper.PublicTools;
import top.saymzx.easycontrol.app.helper.ViewTools;

public class Client {
    private static final ConcurrentHashMap<String, Client> allClient = new ConcurrentHashMap<>();
    private volatile boolean isClosed = false;

    // 组件
    private ClientStream clientStream = null;
    private ClientController clientController = null;
    private ClientPlayer clientPlayer = null;
    private Device device;

    public Client(Device device) {
        if (allClient.containsKey(device.uuid)) return;
        if (AppData.mainActivity == null) return;
        this.device = device;
        Pair<ItemLoadingBinding, Dialog> loading = ViewTools.createLoading(AppData.mainActivity);
        loading.second.show();
        // 连接
        clientStream = new ClientStream(device, bool -> {
            if (bool) {
                // putIfAbsent 防止快速双击导致重复连接
                Client existing = allClient.putIfAbsent(device.uuid, this);
                if (existing != null) {
                    // 已有连接，关闭当前新建的
                    ViewTools.dismiss(loading.second);
                    clientStream.close();
                    return;
                }
                AppData.uiHandler.post(ClientService::update);
                // 从后台发起的连接(如断线重连)系统可能不让启动前台服务，等悬浮窗出来后再试一次
                AppData.uiHandler.postDelayed(ClientService::update, 2000);
                // 控制器、播放器
                clientController = new ClientController(device, clientStream, () -> clientPlayer = new ClientPlayer(device.uuid, clientStream));
                // 临时设备
                boolean isTempDevice = device.isTempDevice();
                // 启动界面
                clientController.handleAction(device.changeToFullOnConnect ? "changeToFull" : "changeToSmall", null, 0);
                // 运行启动时操作
                if (device.customResolutionOnConnect) {
                    if (device.customResolutionFollow) {
                        DisplayMetrics screen = PublicTools.getScreenSize();
                        clientController.handleAction("writeByteBuffer", ControlPacket.createMatchResolutionEvent(Math.max(screen.widthPixels, screen.heightPixels), Math.min(screen.widthPixels, screen.heightPixels)), 0);
                    } else
                        clientController.handleAction("writeByteBuffer", ControlPacket.createChangeResolutionEvent(device.customResolutionWidth, device.customResolutionHeight), 0);
                }
                if (!isTempDevice && device.wakeOnConnect)
                    clientController.handleAction("buttonWake", null, 0);
                if (!isTempDevice && device.lightOffOnConnect)
                    clientController.handleAction("buttonLightOff", null, 2000);
                // 连接成功回调（单应用投屏切换：此时才关闭原会话，连接失败则原会话保留）
                if (device.onConnectSuccess != null) {
                    Runnable onConnectSuccess = device.onConnectSuccess;
                    device.onConnectSuccess = null;
                    onConnectSuccess.run();
                }
            }
            ViewTools.dismiss(loading.second);
        });
    }

    public static void startDevice(Device device) {
        if (device == null) return;
        new Client(device);
    }

    public static Device getDevice(String uuid) {
        Client client = allClient.get(uuid);
        if (client == null) return null;
        return client.device;
    }

    public static ClientController getClientController(String uuid) {
        Client client = allClient.get(uuid);
        if (client == null) return null;
        return client.clientController;
    }

    public static ClientPlayer getClientPlayer(String uuid) {
        Client client = allClient.get(uuid);
        if (client == null) return null;
        return client.clientPlayer;
    }

    static boolean isEmpty() {
        return allClient.isEmpty();
    }

    static String names() {
        StringBuilder names = new StringBuilder();
        for (Client client : allClient.values()) {
            if (names.length() > 0) names.append("、");
            names.append(client.device.name);
        }
        return names.toString();
    }

    public static void sendAction(String uuid, String action, ByteBuffer byteBuffer, int delay) {
        if (action == null || uuid == null) return;
        if (action.equals("start")) {
            for (Device device : AdbTools.devicesList)
                if (Objects.equals(device.uuid, uuid)) startDevice(device);
        } else {
            Client client = allClient.get(uuid);
            if (client == null) return;
            if (action.equals("close")) {
                client.close(byteBuffer);
            } else {
                if (client.clientController == null) return;
                client.clientController.handleAction(action, byteBuffer, delay);
            }
        }
    }

    private void close(ByteBuffer byteBuffer) {
        if (isClosed) return;
        isClosed = true;
        // 临时设备
        boolean isTempDevice = device.isTempDevice();
        // 更新数据库
        if (!isTempDevice) AppData.dbHelper.update(device);
        allClient.remove(device.uuid);
        AppData.uiHandler.post(ClientService::update);
        // 断开时操作(锁定/恢复背光/静音)由服务端在会话结束时执行，启动参数传过去
        // 关闭组件
        if (clientPlayer != null) clientPlayer.close();
        if (clientController != null) clientController.close();
        if (clientStream != null) clientStream.close();
        // 异常断开时自动重连（设备级开关或全局开关任一开启）
        if (byteBuffer != null) {
            String errorMsg = new String(byteBuffer.array());
            PublicTools.logToast("Client", errorMsg, true);
            if (device.reconnectOnClose || AppData.setting.getShowReconnect()) {
                top.saymzx.easycontrol.app.helper.ConnectHelper.showReconnect(device, errorMsg);
            }
        }
    }

}
