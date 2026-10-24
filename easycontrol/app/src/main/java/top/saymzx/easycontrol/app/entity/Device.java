package top.saymzx.easycontrol.app.entity;

import java.util.Objects;

public class Device {
    // 可选码率(Mbps)，从高到低
    public static final String[] maxVideoBitList = new String[]{"50", "40", "30", "25", "20", "16", "12", "10", "8", "6", "4", "2", "1"};

    // 按最大大小和最大帧率推荐码率(Mbps)，算法参考 Moonlight 的默认码率：按像素数在常见分辨率之间插值，
    // 60帧以上按平方根增长。这里只知道长边，短边按 16:10 估算。结果取 maxVideoBitList 中最接近的一档
    public static int autoVideoBit(int maxSize, int maxFps) {
        int[][] table = new int[][]{{640 * 360, 1}, {854 * 480, 2}, {1280 * 720, 5}, {1920 * 1080, 10}, {2560 * 1440, 20}, {3840 * 2160, 40}};
        double pixels = (double) maxSize * (maxSize * 10 / 16);
        double resolutionFactor = table[table.length - 1][1];
        if (pixels <= table[0][0]) resolutionFactor = table[0][1];
        else for (int i = 1; i < table.length; i++) {
            if (pixels <= table[i][0]) {
                resolutionFactor = table[i - 1][1] + (pixels - table[i - 1][0]) / (table[i][0] - table[i - 1][0]) * (table[i][1] - table[i - 1][1]);
                break;
            }
        }
        double fpsFactor = (maxFps <= 60 ? maxFps : Math.sqrt(maxFps / 60.0) * 60) / 30.0;
        double target = resolutionFactor * fpsFactor;
        int best = Integer.parseInt(maxVideoBitList[0]);
        for (String option : maxVideoBitList) {
            int value = Integer.parseInt(option);
            if (Math.abs(Math.log(value / target)) < Math.abs(Math.log(best / target))) best = value;
        }
        return best;
    }

    public static final int TYPE_NETWORK = 1;
    public static final int TYPE_LINK = 2;
    public final String uuid;
    public final int type;
    public String name;
    public String address = "";
    public String startApp = "";
    public int adbPort = 5555;
    public int serverPort = 25166;
    public boolean listenClip = true;
    public boolean isAudio = true;
    public int maxSize = 1600;
    public int maxFps = 60;
    public int maxVideoBit = autoVideoBit(maxSize, maxFps);
    public boolean useH265 = true;
    public boolean connectOnStart = false;
    public boolean customResolutionOnConnect = false;
    public boolean wakeOnConnect = true;
    public boolean lightOffOnConnect = false;
    public boolean showNavBarOnConnect = true;
    public boolean changeToFullOnConnect = false;
    public boolean keepWakeOnRunning = true;
    public boolean changeResolutionOnRunning = false;
    public boolean smallToMiniOnRunning = false;
    public boolean fullToMiniOnRunning = true;
    public boolean miniTimeoutOnRunning = false;
    public boolean lockOnClose = false;
    public boolean lightOnClose = false;
    public boolean reconnectOnClose = false;
    public boolean muteOnClose = true;
    public int customResolutionWidth = 1080;
    public int customResolutionHeight = 2400;
    public int smallX = 200;
    public int smallY = 200;
    public int smallLength = 800;
    public int smallXLan = 200;
    public int smallYLan = 200;
    public int smallLengthLan = 800;
    public int miniY = 200;

    // 连接成功后的回调（用于单应用投屏切换：temp 连接成功后再关闭原会话，避免失败时丢失会话）
    public Runnable onConnectSuccess = null;

    public Device(String uuid, int type) {
        this.uuid = uuid;
        this.type = type;
        this.name = uuid;
    }

    public boolean isNetworkDevice() {
        return type == TYPE_NETWORK;
    }

    public boolean isLinkDevice() {
        return type == TYPE_LINK;
    }

    public boolean isTempDevice() {
        return Objects.equals(name, "----");
    }

    public Device clone(String uuid) {
        Device newDevice = new Device(uuid, type);
        newDevice.name = name;
        newDevice.address = address;
        newDevice.startApp = startApp;
        newDevice.adbPort = adbPort;
        newDevice.serverPort = serverPort;
        newDevice.listenClip = listenClip;
        newDevice.isAudio = isAudio;
        newDevice.maxSize = maxSize;
        newDevice.maxFps = maxFps;
        newDevice.maxVideoBit = maxVideoBit;
        newDevice.useH265 = useH265;
        newDevice.connectOnStart = connectOnStart;
        newDevice.customResolutionOnConnect = customResolutionOnConnect;
        newDevice.wakeOnConnect = wakeOnConnect;
        newDevice.lightOffOnConnect = lightOffOnConnect;
        newDevice.showNavBarOnConnect = showNavBarOnConnect;
        newDevice.changeToFullOnConnect = changeToFullOnConnect;
        newDevice.keepWakeOnRunning = keepWakeOnRunning;
        newDevice.changeResolutionOnRunning = changeResolutionOnRunning;
        newDevice.smallToMiniOnRunning = smallToMiniOnRunning;
        newDevice.fullToMiniOnRunning = fullToMiniOnRunning;
        newDevice.miniTimeoutOnRunning = miniTimeoutOnRunning;
        newDevice.lockOnClose = lockOnClose;
        newDevice.lightOnClose = lightOnClose;
        newDevice.reconnectOnClose = reconnectOnClose;
        newDevice.muteOnClose = muteOnClose;

        newDevice.customResolutionWidth = customResolutionWidth;
        newDevice.customResolutionHeight = customResolutionHeight;
        newDevice.smallX = smallX;
        newDevice.smallY = smallY;
        newDevice.smallLength = smallLength;
        newDevice.smallXLan = smallXLan;
        newDevice.smallYLan = smallYLan;
        newDevice.smallLengthLan = smallLengthLan;
        newDevice.miniY = miniY;
        return newDevice;
    }
}
