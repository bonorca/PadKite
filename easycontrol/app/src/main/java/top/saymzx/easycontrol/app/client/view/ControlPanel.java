package top.saymzx.easycontrol.app.client.view;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import java.util.ArrayList;
import java.util.Objects;

import top.saymzx.easycontrol.app.R;
import top.saymzx.easycontrol.app.client.Client;
import top.saymzx.easycontrol.app.client.tools.ClientController;
import top.saymzx.easycontrol.app.client.tools.ControlPacket;
import top.saymzx.easycontrol.app.databinding.ItemControlTileBinding;
import top.saymzx.easycontrol.app.databinding.ModuleControlPanelBinding;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.entity.Device;
import top.saymzx.easycontrol.app.entity.MyInterface;
import top.saymzx.easycontrol.app.helper.PublicTools;

// 连接后的控制面板，全屏和小窗共用：点面板外、按返回键、点右上角 × 都能关。
// 开关类按钮开启时整块高亮，不靠图标猜状态
public class ControlPanel {
    public interface Host {
        // 全屏有“自动旋转”“小窗”，小窗有“全屏”
        boolean isFull();

        boolean isAutoRotate();

        void setAutoRotate(boolean isAuto);

        void setNavBarShown(boolean isShow);

        void onPanelClosed();
    }

    private interface State {
        boolean get();
    }

    private final Context context;
    // 全屏：加在 Activity 里；小窗：null，单独开一个全屏悬浮窗
    private final ViewGroup parent;
    private final Device device;
    private final ClientController clientController;
    private final Host host;
    private final FrameLayout root;
    private final ModuleControlPanelBinding panel;
    private boolean isShow = false;
    private int volumeBeforeMute = 50;
    private WindowManager.LayoutParams windowParams;

    public ControlPanel(Context context, ViewGroup parent, Device device, ClientController clientController, Host host) {
        this.context = context;
        this.parent = parent;
        this.device = device;
        this.clientController = clientController;
        this.host = host;
        root = new FrameLayout(context) {
            // 悬浮窗模式下返回键送到这里；全屏模式下由 FullActivity 的按键监听转过来
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) hide(true);
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        panel = ModuleControlPanelBinding.inflate(LayoutInflater.from(context), root, true);
        // 按面板所在的窗口算大小：全屏页可能在分屏里，比整块屏幕小
        int width;
        int height;
        if (parent != null && parent.getWidth() > 0) {
            width = parent.getWidth();
            height = parent.getHeight();
        } else {
            DisplayMetrics screen = PublicTools.getScreenSize();
            width = screen.widthPixels;
            height = screen.heightPixels;
        }
        boolean isLandscape = width > height;
        panel.card.getLayoutParams().width = Math.min(PublicTools.dp2px(isLandscape ? 560f : 400f), width - PublicTools.dp2px(32f));
        // 窗口大小变了(旋转、分屏拖动)排版就不对了，直接收起，再点开会按新大小排
        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            // 布局过程中不能移除视图，放到下一轮
            if (oldRight - oldLeft > 0 && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop))
                root.post(() -> hide(false));
        });
        // 横屏高度不够，导航键和音量并排
        if (isLandscape) {
            panel.controlsRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams navParams = (LinearLayout.LayoutParams) panel.navRow.getLayoutParams();
            navParams.width = 0;
            navParams.weight = 1;
            LinearLayout.LayoutParams volumeParams = (LinearLayout.LayoutParams) panel.volumeRow.getLayoutParams();
            volumeParams.width = 0;
            volumeParams.weight = 1;
            volumeParams.topMargin = 0;
            volumeParams.setMarginStart(PublicTools.dp2px(8f));
        }
        panel.title.setText(device.isTempDevice() ? device.startApp : device.name);
        panel.scrim.setOnClickListener(v -> hide(true));
        panel.buttonClose.setOnClickListener(v -> hide(true));
        setNavButtons();
        setVolume();
        setTiles(isLandscape ? 6 : 4);
        panel.buttonDisconnect.setOnClickListener(v -> {
            hide(false);
            Client.sendAction(device.uuid, "close", null, 0);
        });
    }

    public void show() {
        if (isShow) return;
        isShow = true;
        if (parent != null) parent.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        else {
            windowParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            AppData.windowManager.addView(root, windowParams);
        }
        panel.scrim.setAlpha(0f);
        panel.scrim.animate().alpha(1f).setDuration(150).start();
        panel.card.setScaleX(0.95f);
        panel.card.setScaleY(0.95f);
        panel.card.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
        loadVolume();
    }

    public void hide(boolean animate) {
        if (!isShow) return;
        isShow = false;
        // 悬浮窗淡出的这一会儿别再挡着点击，点到下面的小窗
        if (windowParams != null && animate) {
            windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                AppData.windowManager.updateViewLayout(root, windowParams);
            } catch (Exception ignored) {
            }
        }
        if (animate) panel.scrim.animate().alpha(0f).setDuration(120).withEndAction(this::remove).start();
        else remove();
        host.onPanelClosed();
    }

    private void remove() {
        try {
            if (parent != null) parent.removeView(root);
            else AppData.windowManager.removeView(root);
        } catch (Exception ignored) {
        }
    }

    // 点完就关面板，看得到效果
    private void runAndHide(MyInterface.MyFunction action) {
        hide(false);
        action.run();
    }

    private void setNavButtons() {
        panel.buttonBack.setOnClickListener(v -> runAndHide(() -> clientController.handleAction("buttonBack", null, 0)));
        panel.buttonHome.setOnClickListener(v -> runAndHide(() -> clientController.handleAction("buttonHome", null, 0)));
        panel.buttonSwitch.setOnClickListener(v -> runAndHide(() -> clientController.handleAction("buttonSwitch", null, 0)));
        if (!Objects.equals(device.startApp, "")) {
            panel.buttonHome.setVisibility(View.GONE);
            panel.buttonSwitch.setVisibility(View.GONE);
        }
    }

    // 媒体音量：滑块 0-100，服务端按被控端自身档位换算；左边图标一键静音/恢复
    private void setVolume() {
        panel.seekbarVolume.setEnabled(false);
        panel.buttonMute.setEnabled(false);
        panel.seekbarVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateVolumeView(progress);
                if (fromUser) clientController.handleAction("setVolume", ControlPacket.createVolumeEvent(progress), 0);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        panel.buttonMute.setOnClickListener(v -> {
            int volume = panel.seekbarVolume.getProgress();
            if (volume > 0) volumeBeforeMute = volume;
            int target = volume > 0 ? 0 : volumeBeforeMute;
            panel.seekbarVolume.setProgress(target);
            clientController.handleAction("setVolume", ControlPacket.createVolumeEvent(target), 0);
        });
    }

    // 读被控端当前音量(走 adb shell，放后台)，读不到(老系统)就不显示这一栏
    private void loadVolume() {
        new Thread(() -> {
            Pair<Integer, Integer> volumeInfo;
            try {
                volumeInfo = clientController.getVolumeInfo();
            } catch (Exception ignored) {
                volumeInfo = null;
            }
            final Pair<Integer, Integer> volumeInfoFinal = volumeInfo;
            AppData.uiHandler.post(() -> {
                if (!isShow) return;
                if (volumeInfoFinal == null || volumeInfoFinal.second <= 0) {
                    panel.volumeRow.setVisibility(View.GONE);
                    return;
                }
                panel.seekbarVolume.setProgress(Math.round(volumeInfoFinal.first * 100f / volumeInfoFinal.second));
                updateVolumeView(panel.seekbarVolume.getProgress());
                panel.seekbarVolume.setEnabled(true);
                panel.buttonMute.setEnabled(true);
            });
        }).start();
    }

    private void updateVolumeView(int volume) {
        panel.buttonMute.setImageResource(volume == 0 ? R.drawable.volume_off : R.drawable.volume_up);
        panel.textVolume.setText(String.valueOf(volume));
    }

    private void setTiles(int columns) {
        ArrayList<View> tiles = new ArrayList<>();
        tiles.add(createToggle(R.drawable.nav_bar, R.string.panel_nav_bar, clientController::isNavBarShown, () -> host.setNavBarShown(!clientController.isNavBarShown())));
        if (host.isFull())
            tiles.add(createToggle(R.drawable.screen_rotation, R.string.panel_auto_rotate, host::isAutoRotate, () -> host.setAutoRotate(!host.isAutoRotate())));
        tiles.add(createToggle(R.drawable.mouse, R.string.panel_mouse, clientController::isMouseMode, clientController::toggleMouse));
        tiles.add(createToggle(R.drawable.lightbulb_off, R.string.panel_screen_off, clientController::isScreenOff, () -> clientController.setScreenOff(!clientController.isScreenOff())));
        tiles.add(createAction(R.drawable.horizontal_rotate, R.string.panel_rotate, () -> clientController.handleAction("buttonRotate", null, 0)));
        tiles.add(createAction(R.drawable.screenshot, R.string.panel_screenshot, () -> clientController.handleAction("screenshot", null, 0)));
        tiles.add(createAction(R.drawable.power, R.string.panel_power, () -> clientController.handleAction("buttonPower", null, 0)));
        tiles.add(createAction(R.drawable.status, R.string.panel_status, this::showStatusDialog));
        if (Objects.equals(device.startApp, ""))
            tiles.add(createAction(R.drawable.share, R.string.panel_single_app, () -> clientController.handleAction("changeToApp", null, 0)));
        if (host.isFull())
            tiles.add(createAction(R.drawable.window_restore, R.string.panel_small, () -> clientController.handleAction("changeToSmall", null, 0)));
        else
            tiles.add(createAction(R.drawable.expand, R.string.panel_full, () -> clientController.handleAction("changeToFull", null, 0)));
        tiles.add(createAction(R.drawable.minus, R.string.panel_mini, () -> clientController.handleAction("changeToMini", null, 0)));
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                panel.tiles.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(tiles.get(i));
        }
        // 最后一行补空位，按钮和上面几行一样宽
        for (int i = tiles.size() % columns; i > 0 && i < columns; i++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, 1, 1f);
            params.setMarginStart(PublicTools.dp2px(4f));
            params.setMarginEnd(PublicTools.dp2px(4f));
            row.addView(new View(context), params);
        }
    }

    private ItemControlTileBinding createTile(int icon, int text) {
        ItemControlTileBinding tile = ItemControlTileBinding.inflate(LayoutInflater.from(context), panel.tiles, false);
        tile.icon.setImageResource(icon);
        tile.text.setText(text);
        return tile;
    }

    // 开关：点了不关面板，高亮跟着变
    private View createToggle(int icon, int text, State state, MyInterface.MyFunction toggle) {
        ItemControlTileBinding tile = createTile(icon, text);
        setTileOn(tile, state.get());
        tile.getRoot().setOnClickListener(v -> {
            toggle.run();
            setTileOn(tile, state.get());
        });
        return tile.getRoot();
    }

    private View createAction(int icon, int text, MyInterface.MyFunction action) {
        ItemControlTileBinding tile = createTile(icon, text);
        tile.getRoot().setOnClickListener(v -> runAndHide(action));
        return tile.getRoot();
    }

    private void setTileOn(ItemControlTileBinding tile, boolean isOn) {
        int color = context.getColor(isOn ? R.color.onClientTileOn : R.color.onBlackBacnground);
        tile.getRoot().setBackgroundResource(isOn ? R.drawable.background_client_tile_on : R.drawable.background_client_tile);
        tile.icon.setImageTintList(ColorStateList.valueOf(color));
        tile.text.setTextColor(color);
    }

    // 设备状态：后台读被控端状态后弹窗(小窗模式下没有 Activity，弹窗内部会切悬浮窗类型)
    private void showStatusDialog() {
        new Thread(() -> {
            ClientController.DeviceStatus status;
            try {
                status = clientController.getDeviceStatus();
            } catch (Exception ignored) {
                status = null;
            }
            final ClientController.DeviceStatus statusFinal = status;
            AppData.uiHandler.post(() -> {
                // shell 返回期间全屏页可能已关闭，此时弹窗会抛 BadTokenException
                if (context instanceof Activity && (((Activity) context).isFinishing() || ((Activity) context).isDestroyed()))
                    return;
                if (statusFinal == null) {
                    PublicTools.logToast("ControlPanel", context.getString(R.string.toast_status_failed), true);
                    return;
                }
                DeviceStatusDialog.show(context, device, statusFinal, parent == null, this::showStatusDialog);
            });
        }).start();
    }
}
