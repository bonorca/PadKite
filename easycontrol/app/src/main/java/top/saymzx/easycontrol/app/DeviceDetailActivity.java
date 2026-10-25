package top.saymzx.easycontrol.app;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Arrays;
import java.util.UUID;

import top.saymzx.easycontrol.app.databinding.ActivityDeviceDetailBinding;
import top.saymzx.easycontrol.app.databinding.ItemLoadingBinding;
import top.saymzx.easycontrol.app.databinding.ItemScanAddressListBinding;
import top.saymzx.easycontrol.app.databinding.ItemSpinnerBinding;
import top.saymzx.easycontrol.app.databinding.ItemSwitchBinding;
import top.saymzx.easycontrol.app.databinding.ItemTextBinding;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.entity.Device;
import top.saymzx.easycontrol.app.helper.MyBroadcastReceiver;
import top.saymzx.easycontrol.app.helper.PublicTools;
import top.saymzx.easycontrol.app.helper.ViewTools;

public class DeviceDetailActivity extends AppCompatActivity {
    private ActivityDeviceDetailBinding activityDeviceDetailBinding;
    private boolean isNew;
    private Device device;
    private Device openedDevice;
    private ItemSpinnerBinding maxVideoBitSpinner;
    private ItemSwitchBinding customResolutionFollowCard;
    private boolean customResolutionOn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ViewTools.setStatusAndNavBar(this);
        ViewTools.setLocale(this);
        activityDeviceDetailBinding = ActivityDeviceDetailBinding.inflate(this.getLayoutInflater());
        setContentView(activityDeviceDetailBinding.getRoot());
        // 获取Device
        String uuid = getIntent().getStringExtra("uuid");
        isNew = uuid == null;
        if (isNew) {
            device = new Device(UUID.randomUUID().toString(), Device.TYPE_NETWORK);
            device.name = AppData.dbHelper.getDefaultDeviceName();
        } else {
            device = AppData.dbHelper.getByUUID(uuid);
            if (device != null) openedDevice = device.clone(device.uuid);
        }
        // 设备已被删除（UUID过期），直接退出
        if (device == null) {
            finish();
            return;
        }
        // 绘制UI
        drawUI();
        // 设置监听
        setListener();
    }

    // 绘制UI
    // 分辨率档位：本机屏幕同比例缩放
    private static final int[] maxSizePercents = new int[]{100, 80, 60, 40};
    private static final String[] maxFpsList = new String[]{"90", "60", "40", "30", "20", "10"};

    private void drawUI() {
        // 预填写参数
        activityDeviceDetailBinding.name.setText(device.name);
        activityDeviceDetailBinding.address.setText(device.address);
        activityDeviceDetailBinding.startApp.setText(device.startApp);
        activityDeviceDetailBinding.adbPort.setText(String.valueOf(device.adbPort));
        activityDeviceDetailBinding.serverPort.setText(String.valueOf(device.serverPort));
        activityDeviceDetailBinding.customResolutionWidth.setText(String.valueOf(device.customResolutionWidth));
        activityDeviceDetailBinding.customResolutionHeight.setText(String.valueOf(device.customResolutionHeight));
        // 连接时操作
        activityDeviceDetailBinding.layoutOnConnect.setOnClickListener(v -> activityDeviceDetailBinding.layoutOnConnectSub.setVisibility(activityDeviceDetailBinding.layoutOnConnectSub.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        customResolutionOn = device.customResolutionOnConnect;
        customResolutionFollowCard = ViewTools.createSwitchCard(this, getString(R.string.device_custom_resolution_follow), getString(R.string.device_custom_resolution_follow_detail), device.customResolutionFollow, isChecked -> {
            device.customResolutionFollow = isChecked;
            updateCustomResolutionVisibility();
        });
        updateCustomResolutionVisibility();
        activityDeviceDetailBinding.layoutOnConnectSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_custom_resolution_on_connect), getString(R.string.device_custom_resolution_on_connect_detail), device.customResolutionOnConnect, isChecked -> {
            customResolutionOn = isChecked;
            updateCustomResolutionVisibility();
        }).getRoot(), 0);
        activityDeviceDetailBinding.layoutOnConnectSub.addView(customResolutionFollowCard.getRoot(), 1);
        activityDeviceDetailBinding.layoutOnConnectSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_wake_on_connect), getString(R.string.device_wake_on_connect_detail), device.wakeOnConnect, isChecked -> device.wakeOnConnect = isChecked).getRoot());
        activityDeviceDetailBinding.layoutOnConnectSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_light_off_on_connect), getString(R.string.device_light_off_on_connect_detail), device.lightOffOnConnect, isChecked -> device.lightOffOnConnect = isChecked).getRoot());
        activityDeviceDetailBinding.layoutOnConnectSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_show_nav_bar_on_connect), getString(R.string.device_show_nav_bar_on_connect_detail), device.showNavBarOnConnect, isChecked -> device.showNavBarOnConnect = isChecked).getRoot());
        activityDeviceDetailBinding.layoutOnConnectSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_change_to_full_on_connect), getString(R.string.device_change_to_full_on_connect_detail), device.changeToFullOnConnect, isChecked -> device.changeToFullOnConnect = isChecked).getRoot());
        // 运行时操作
        activityDeviceDetailBinding.layoutOnRunning.setOnClickListener(v -> activityDeviceDetailBinding.layoutOnRunningSub.setVisibility(activityDeviceDetailBinding.layoutOnRunningSub.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        activityDeviceDetailBinding.layoutOnRunningSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_listen_clip_on_running), getString(R.string.device_listen_clip_on_running_detail), device.listenClip, isChecked -> device.listenClip = isChecked).getRoot());
        activityDeviceDetailBinding.layoutOnRunningSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_keep_wake_on_running), getString(R.string.device_keep_wake_on_running_detail), device.keepWakeOnRunning, isChecked -> device.keepWakeOnRunning = isChecked).getRoot());
        activityDeviceDetailBinding.layoutOnRunningSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_change_resolution_on_running), getString(R.string.device_change_resolution_on_running_detail), device.changeResolutionOnRunning, isChecked -> device.changeResolutionOnRunning = isChecked).getRoot());
        activityDeviceDetailBinding.layoutOnRunningSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_small_to_mini_on_running), getString(R.string.device_small_to_mini_on_running_detail), device.smallToMiniOnRunning, isChecked -> device.smallToMiniOnRunning = isChecked).getRoot());
        activityDeviceDetailBinding.layoutOnRunningSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_full_to_mini_on_running), getString(R.string.device_full_to_mini_on_running_detail), device.fullToMiniOnRunning, isChecked -> device.fullToMiniOnRunning = isChecked).getRoot());
        activityDeviceDetailBinding.layoutOnRunningSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_mini_timeout_on_running), getString(R.string.device_mini_timeout_on_running_detail), device.miniTimeoutOnRunning, isChecked -> device.miniTimeoutOnRunning = isChecked).getRoot());
        // 断开时操作
        activityDeviceDetailBinding.layoutOnClose.setOnClickListener(v -> activityDeviceDetailBinding.layoutOnCloseSub.setVisibility(activityDeviceDetailBinding.layoutOnCloseSub.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        activityDeviceDetailBinding.layoutOnCloseSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_lock_on_close), getString(R.string.device_lock_on_close_detail), device.lockOnClose, isChecked -> device.lockOnClose = isChecked).getRoot());
        activityDeviceDetailBinding.layoutOnCloseSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_light_on_close), getString(R.string.device_light_on_close_detail), device.lightOnClose, isChecked -> device.lightOnClose = isChecked).getRoot());
        activityDeviceDetailBinding.layoutOnCloseSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_mute_on_close), getString(R.string.device_mute_on_close_detail), device.muteOnClose, isChecked -> device.muteOnClose = isChecked).getRoot());
        activityDeviceDetailBinding.layoutOnCloseSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_reconnect_on_close), getString(R.string.device_reconnect_on_close_detail), device.reconnectOnClose, isChecked -> device.reconnectOnClose = isChecked).getRoot());
        // 参数
        activityDeviceDetailBinding.layoutOption.setOnClickListener(v -> activityDeviceDetailBinding.layoutOptionSub.setVisibility(activityDeviceDetailBinding.layoutOptionSub.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        DisplayMetrics screen = PublicTools.getScreenSize();
        int screenLong = Math.max(screen.widthPixels, screen.heightPixels);
        int screenShort = Math.min(screen.widthPixels, screen.heightPixels);
        int[] maxSizeValues = new int[maxSizePercents.length];
        String[] maxSizeList = new String[maxSizePercents.length];
        int maxSizeIndex = 0;
        for (int i = 0; i < maxSizePercents.length; i++) {
            maxSizeValues[i] = screenLong * maxSizePercents[i] / 100;
            maxSizeList[i] = (i == 0 ? getString(R.string.device_max_size_native) : maxSizePercents[i] + "%") + " " + maxSizeValues[i] + "×" + screenShort * maxSizePercents[i] / 100;
            // 已保存的值不在档位里时取最接近的一档
            if (Math.abs(maxSizeValues[i] - device.maxSize) < Math.abs(maxSizeValues[maxSizeIndex] - device.maxSize)) maxSizeIndex = i;
        }
        ArrayAdapter<String> maxSizeAdapter = new ArrayAdapter<>(AppData.applicationContext, R.layout.item_spinner_item, maxSizeList);
        ArrayAdapter<String> maxFpsAdapter = new ArrayAdapter<>(AppData.applicationContext, R.layout.item_spinner_item, maxFpsList);
        ArrayAdapter<String> maxVideoBitAdapter = new ArrayAdapter<>(AppData.applicationContext, R.layout.item_spinner_item, Device.maxVideoBitList);
        activityDeviceDetailBinding.layoutOptionSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_is_audio), getString(R.string.device_is_audio_detail), device.isAudio, isChecked -> device.isAudio = isChecked).getRoot());
        activityDeviceDetailBinding.layoutOptionSub.addView(ViewTools.createSpinnerCard(this, getString(R.string.device_max_size), getString(R.string.device_max_size_detail), maxSizeList[maxSizeIndex], maxSizeAdapter, str -> {
            // 下拉框初始化时也会回调一次，值没变就不动码率，免得覆盖手动选的码率
            int value = maxSizeValues[Arrays.asList(maxSizeList).indexOf(str)];
            if (value == device.maxSize) return;
            device.maxSize = value;
            applyAutoVideoBit();
        }).getRoot());
        activityDeviceDetailBinding.layoutOptionSub.addView(ViewTools.createSpinnerCard(this, getString(R.string.device_max_fps), getString(R.string.device_max_fps_detail), String.valueOf(device.maxFps), maxFpsAdapter, str -> {
            int value = Integer.parseInt(str);
            if (value == device.maxFps) return;
            device.maxFps = value;
            applyAutoVideoBit();
        }).getRoot());
        maxVideoBitSpinner = ViewTools.createSpinnerCard(this, getString(R.string.device_max_video_bit), getString(R.string.device_max_video_bit_detail), String.valueOf(device.maxVideoBit), maxVideoBitAdapter, str -> device.maxVideoBit = Integer.parseInt(str));
        activityDeviceDetailBinding.layoutOptionSub.addView(maxVideoBitSpinner.getRoot());
        activityDeviceDetailBinding.layoutOptionSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_use_h265), getString(R.string.device_use_h265_detail), device.useH265, isChecked -> device.useH265 = isChecked).getRoot());
        activityDeviceDetailBinding.layoutOptionSub.addView(ViewTools.createSwitchCard(this, getString(R.string.device_connect_on_start), getString(R.string.device_connect_on_start_detail), device.connectOnStart, isChecked -> device.connectOnStart = isChecked).getRoot());
    }

    // 连接时修改分辨率：开启后显示「跟随本机屏幕」，关掉跟随才显示手填宽高
    private void updateCustomResolutionVisibility() {
        customResolutionFollowCard.getRoot().setVisibility(customResolutionOn ? View.VISIBLE : View.GONE);
        activityDeviceDetailBinding.customResolution.setVisibility(customResolutionOn && !device.customResolutionFollow ? View.VISIBLE : View.GONE);
    }

    // 最大大小或最大帧率变化后，按两者组合自动选码率，之后仍可手动修改
    private void applyAutoVideoBit() {
        device.maxVideoBit = Device.autoVideoBit(device.maxSize, device.maxFps);
        maxVideoBitSpinner.itemSpinner.setSelection(Arrays.asList(Device.maxVideoBitList).indexOf(String.valueOf(device.maxVideoBit)));
    }

    // 设置监听
    private void setListener() {
        activityDeviceDetailBinding.backButton.setOnClickListener(v -> finish());
        activityDeviceDetailBinding.buttonScan.setOnClickListener(v -> scanAddress());
        // 设置确认按钮监听
        activityDeviceDetailBinding.ok.setOnClickListener(v -> {
            String name = String.valueOf(activityDeviceDetailBinding.name.getText());
            String address = String.valueOf(activityDeviceDetailBinding.address.getText());
            if (name.equals("----") || name.equals("") || address.equals("")) {
                Toast.makeText(this, getString(R.string.toast_config), Toast.LENGTH_SHORT).show();
                return;
            }
            device.name = name;
            device.address = device.isLinkDevice() ? device.uuid : address;
            device.startApp = String.valueOf(activityDeviceDetailBinding.startApp.getText());
            // 端口解析保护
            try {
                device.adbPort = Integer.parseInt(String.valueOf(activityDeviceDetailBinding.adbPort.getText()).trim());
                device.serverPort = Integer.parseInt(String.valueOf(activityDeviceDetailBinding.serverPort.getText()).trim());
            } catch (NumberFormatException e) {
                Toast.makeText(this, getString(R.string.toast_config), Toast.LENGTH_SHORT).show();
                return;
            }
            // 自定义分辨率
            String width = String.valueOf(activityDeviceDetailBinding.customResolutionWidth.getText());
            String height = String.valueOf(activityDeviceDetailBinding.customResolutionHeight.getText());
            device.customResolutionOnConnect = customResolutionOn && device.customResolutionFollow;
            if (customResolutionOn && !device.customResolutionFollow && !width.equals("") && !height.equals("")) {
                try {
                    int w = Integer.parseInt(width.trim());
                    int h = Integer.parseInt(height.trim());
                    if (w >= 500 && h >= 500) {
                        device.customResolutionOnConnect = true;
                        device.customResolutionWidth = w;
                        device.customResolutionHeight = h;
                    }
                } catch (NumberFormatException e) {
                    Toast.makeText(this, getString(R.string.toast_config), Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            // 更新数据库
            if (isNew) AppData.dbHelper.insert(device);
            else {
                Device latest = AppData.dbHelper.getByUUID(device.uuid);
                if (latest != null && openedDevice != null) device.keepSessionPrefs(openedDevice, latest);
                AppData.dbHelper.update(device);
            }
            Intent intent = new Intent();
            intent.setAction(MyBroadcastReceiver.ACTION_UPDATE_DEVICE_LIST);
            sendBroadcast(intent);
            finish();
        });
    }

    // 扫描局域网地址
    private void scanAddress() {
        Pair<ItemLoadingBinding, Dialog> loading = ViewTools.createLoading(this);
        loading.second.show();
        new Thread(() -> {
            java.util.List<String> scannedAddresses = PublicTools.scanAddress();
            ViewTools.dismiss(loading.second);
            AppData.uiHandler.post(() -> {
                // 扫描期间 Activity 可能已销毁（旋转/退出），此时 show 会抛 BadTokenException
                if (isFinishing() || isDestroyed()) return;
                ItemScanAddressListBinding scanAddressListView = ItemScanAddressListBinding.inflate(LayoutInflater.from(this));
                Dialog dialog = ViewTools.createDialog(this, true, scanAddressListView.getRoot());
                for (String i : scannedAddresses) {
                    ItemTextBinding text = ViewTools.createTextCard(this, i, () -> {
                        // 只填入纯IP地址，去掉可能的"(本机)"后缀
                        activityDeviceDetailBinding.address.setText(i.split(" \\(")[0]);
                        dialog.cancel();
                    });
                    scanAddressListView.list.addView(text.getRoot());
                }
                dialog.show();
            });
        }).start();
    }
}