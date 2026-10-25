package top.saymzx.easycontrol.app.client.view;

import android.annotation.SuppressLint;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.os.Build;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import top.saymzx.easycontrol.app.R;
import top.saymzx.easycontrol.app.client.Client;
import top.saymzx.easycontrol.app.client.tools.ClientController;
import top.saymzx.easycontrol.app.client.tools.ControlPacket;
import top.saymzx.easycontrol.app.databinding.ModuleSmallViewBinding;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.entity.Device;
import top.saymzx.easycontrol.app.helper.PublicTools;
import top.saymzx.easycontrol.app.helper.ViewTools;

public class SmallView extends ViewOutlineProvider implements ControlPanel.Host {
    private final Device device;
    private ClientController clientController;
    private volatile boolean isShow = false;
    private ControlPanel controlPanel;

    // 悬浮窗
    private final ModuleSmallViewBinding smallView = ModuleSmallViewBinding.inflate(LayoutInflater.from(AppData.applicationContext));
    private final WindowManager.LayoutParams smallViewParams =
            new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    LayoutParamsFlagFocus,
                    PixelFormat.TRANSLUCENT
            );

    private static final int LayoutParamsFlagFocus = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
    private static final int LayoutParamsFlagNoFocus = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

    public SmallView(String uuid) {
        device = Client.getDevice(uuid);
        clientController = Client.getClientController(uuid);
        if (device == null || clientController == null) return;
        smallViewParams.gravity = Gravity.START | Gravity.TOP;
        // 设置监听控制
        setFloatVideoListener();
        setReSizeListener();
        setBarListener();
        setButtonListener();
        setKeyEvent();
        // 设置圆角
        smallView.body.setOutlineProvider(this);
        smallView.body.setClipToOutline(true);
    }

    public void show() {
        if (device == null || clientController == null) return;
        // 初始化
        showNavBar(clientController.isNavBarShown());
        smallViewParams.x = device.smallX;
        smallViewParams.y = device.smallY;
        updateMaxSize();
        // 自定义分辨率(2:1)
        if (!device.customResolutionOnConnect && device.changeResolutionOnRunning)
            clientController.handleAction("writeByteBuffer", ControlPacket.createChangeResolutionEvent(0.5f), 0);
        // 显示
        AppData.windowManager.addView(smallView.getRoot(), smallViewParams);
        smallView.textureViewLayout.addView(clientController.getTextureView(), 0);
        // 小窗重开时恢复光标覆盖层
        if (clientController.isMouseMode()) clientController.showCursorOverlay();
        ViewTools.viewAnim(smallView.getRoot(), true, 0, PublicTools.dp2px(40f), null);
        isShow = true;
    }

    public void hide() {
        if (device == null || clientController == null) return;
        if (controlPanel != null) controlPanel.hide(false);
        try {
            smallView.textureViewLayout.removeView(clientController.getTextureView());
            AppData.windowManager.removeView(smallView.getRoot());
            isShow = false;
        } catch (Exception ignored) {
        }
    }

    // 设置焦点监听
    @SuppressLint("ClickableViewAccessibility")
    private void setFloatVideoListener() {
        smallView.getRoot().setOnTouchHandle(event -> {
            if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                // 控制面板盖在小窗上面，点面板也算点到小窗外面，不能因此挂起
                if (controlPanel != null) return;
                if (device.smallToMiniOnRunning)
                    clientController.handleAction("changeToMini", ByteBuffer.wrap("changeToSmall".getBytes()), 0);
                else if (smallViewParams.flags != LayoutParamsFlagNoFocus) {
                    smallView.editText.clearFocus();
                    smallViewParams.flags = LayoutParamsFlagNoFocus;
                    AppData.windowManager.updateViewLayout(smallView.getRoot(), smallViewParams);
                }
            } else if (smallViewParams.flags != LayoutParamsFlagFocus) {
                smallViewParams.flags = LayoutParamsFlagFocus;
                AppData.windowManager.updateViewLayout(smallView.getRoot(), smallViewParams);
                smallView.editText.requestFocus();
            }
        });
    }

    // 设置上横条监听控制
    @SuppressLint("ClickableViewAccessibility")
    private void setBarListener() {
        AtomicBoolean isFilp = new AtomicBoolean(false);
        AtomicInteger xx = new AtomicInteger();
        AtomicInteger yy = new AtomicInteger();
        AtomicInteger paramsX = new AtomicInteger();
        AtomicInteger paramsY = new AtomicInteger();
        smallView.bar.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    xx.set((int) event.getRawX());
                    yy.set((int) event.getRawY());
                    paramsX.set(smallViewParams.x);
                    paramsY.set(smallViewParams.y);
                    isFilp.set(false);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    int x = (int) event.getRawX();
                    int y = (int) event.getRawY();
                    int flipX = x - xx.get();
                    int flipY = y - yy.get();
                    // 适配一些机器将点击视作小范围移动(小于4的圆内不做处理)
                    if (!isFilp.get()) {
                        if (flipX * flipX + flipY * flipY < 16) return true;
                        isFilp.set(true);
                    }
                    // 拖动限制，避免拖到状态栏
                    if (y < statusBarHeight + 10) return true;
                    // 更新
                    updateSite(paramsX.get() + flipX, paramsY.get() + flipY);
                    break;
                }
                case MotionEvent.ACTION_UP:
                    // 拖完马上记住位置
                    if (!isFilp.get()) showControlPanel();
                    else clientController.saveDevice();
                    break;
            }
            return true;
        });
    }

    // 设置按钮监听
    private void setButtonListener() {
        smallView.buttonBack.setOnClickListener(v -> clientController.handleAction("buttonBack", null, 0));
        smallView.buttonHome.setOnClickListener(v -> clientController.handleAction("buttonHome", null, 0));
        smallView.buttonSwitch.setOnClickListener(v -> clientController.handleAction("buttonSwitch", null, 0));
    }

    // 控制面板单独开一个全屏悬浮窗
    private void showControlPanel() {
        if (controlPanel != null) return;
        controlPanel = new ControlPanel(AppData.applicationContext, null, device, clientController, this);
        controlPanel.show();
    }

    @Override
    public void onPanelClosed() {
        controlPanel = null;
    }

    @Override
    public boolean isFull() {
        return false;
    }

    @Override
    public boolean isAutoRotate() {
        return false;
    }

    @Override
    public void setAutoRotate(boolean isAuto) {
    }

    @Override
    public void setNavBarShown(boolean isShow) {
        clientController.setNavBarShown(isShow);
        showNavBar(isShow);
    }

    private void showNavBar(boolean isShow) {
        smallView.navBar.setVisibility(isShow ? View.VISIBLE : View.GONE);
    }

    // 设置悬浮窗大小拖动按钮监听控制
    @SuppressLint("ClickableViewAccessibility")
    private void setReSizeListener() {
        smallView.reSize.setOnTouchListener((v, event) -> {
            int sizeX = (int) (event.getRawX() - smallViewParams.x);
            int sizeY = (int) (event.getRawY() - smallViewParams.y);
            int length = Math.max(sizeX, sizeY);
            ViewGroup.LayoutParams textureViewLayoutParams = clientController.getTextureView().getLayoutParams();
            if (textureViewLayoutParams.width < textureViewLayoutParams.height)
                device.smallLength = length;
            else device.smallLengthLan = length;
            updateMaxSize();
            if (event.getActionMasked() == MotionEvent.ACTION_UP) clientController.saveDevice();
            return true;
        });
    }

    private void updateSite(int x, int y) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(8);
        byteBuffer.putInt(x);
        byteBuffer.putInt(y);
        byteBuffer.flip();
        clientController.handleAction("updateSite", byteBuffer, 0);
    }

    public void updateView(int x, int y) {
        smallViewParams.x = x;
        smallViewParams.y = y;
        // 排队中的位置更新可能晚于小窗被移除(切最小化、断开)
        if (!isShow || !smallView.getRoot().isAttachedToWindow()) return;
        try {
            AppData.windowManager.updateViewLayout(smallView.getRoot(), smallViewParams);
        } catch (Exception ignored) {
        }
    }

    private void updateMaxSize() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(8);
        byteBuffer.putInt(device.smallLength);
        byteBuffer.putInt(device.smallLengthLan);
        byteBuffer.flip();
        clientController.handleAction("updateMaxSize", byteBuffer, 0);
    }

    public boolean isShow() {
        return isShow;
    }

    // 设置键盘监听
    private void setKeyEvent() {
        smallView.editText.setInputType(InputType.TYPE_NULL);
        smallView.editText.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                clientController.handleAction("writeByteBuffer", ControlPacket.createKeyEvent(event.getKeyCode(), event.getMetaState()), 0);
                return true;
            }
            return false;
        });
    }

    // 检查画面是否超出
    public void checkSizeAndSite() {
        if (!isShow) return;
        DisplayMetrics screenSize = PublicTools.getScreenSize();
        int screenMaxWidth = screenSize.widthPixels - 50;
        int screenMaxHeight = screenSize.heightPixels - statusBarHeight - 50;
        ViewGroup.LayoutParams textureViewLayoutParams = clientController.getTextureView().getLayoutParams();
        int width = textureViewLayoutParams.width;
        int height = textureViewLayoutParams.height;
        int startX = smallViewParams.x;
        int startY = smallViewParams.y;
        // 检测到大小超出
        if (width > screenMaxWidth + 200 || height > screenMaxHeight + 200) {
            int maxLength = Math.min(screenMaxWidth, screenMaxHeight);
            if (width < height) device.smallLength = maxLength;
            else device.smallLengthLan = maxLength;
            updateMaxSize();
            updateSite(0, statusBarHeight);
            return;
        }
        // 检测到位置超出过多
        int halfWidth = (int) (width * 0.5);
        if (startX < -1 * halfWidth) updateSite(-1 * halfWidth + 50, startY);
        if (startX > screenSize.widthPixels - halfWidth)
            updateSite(screenSize.widthPixels - halfWidth - 50, startY);
        if (startY < statusBarHeight / 2) updateSite(startX, statusBarHeight);
        if (startY > screenSize.heightPixels - 100)
            updateSite(startX, screenSize.heightPixels - 200);
    }

    @Override
    public void getOutline(View view, Outline outline) {
        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AppData.applicationContext.getResources().getDimension(R.dimen.cron));
    }

    private static int statusBarHeight = 0;

    static {
        @SuppressLint("InternalInsetResource") int resourceId = AppData.applicationContext.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = AppData.applicationContext.getResources().getDimensionPixelSize(resourceId);
        }
    }

}
