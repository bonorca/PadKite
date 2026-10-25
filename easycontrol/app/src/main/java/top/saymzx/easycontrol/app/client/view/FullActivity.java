package top.saymzx.easycontrol.app.client.view;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;

import androidx.appcompat.app.AppCompatActivity;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Objects;

import top.saymzx.easycontrol.app.client.Client;
import top.saymzx.easycontrol.app.client.tools.ClientController;
import top.saymzx.easycontrol.app.client.tools.ControlPacket;
import top.saymzx.easycontrol.app.databinding.ActivityFullBinding;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.entity.Device;
import top.saymzx.easycontrol.app.helper.ViewTools;

public class FullActivity extends AppCompatActivity implements SensorEventListener, ControlPanel.Host {
    private boolean isClose = false;
    private Device device;
    private ClientController clientController;
    private ActivityFullBinding activityFullBinding;
    private boolean autoRotate;
    private ControlPanel controlPanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ViewTools.setStatusAndNavBar(this);
        if (AppData.setting.getSetFullScreen()) ViewTools.setFullScreen(this);
        activityFullBinding = ActivityFullBinding.inflate(this.getLayoutInflater());
        setContentView(activityFullBinding.getRoot());
        String uuid = getIntent().getStringExtra("uuid");
        device = Client.getDevice(uuid);
        clientController = Client.getClientController(uuid);
        if (device == null || clientController == null) {
            finish();
            return;
        }
        clientController.setFullView(this);
        // 初始化
        showNavBar(clientController.isNavBarShown());
        // 从上次的横竖屏开始：自动旋转开着，之后跟着重力走；关着就一直锁在这个方向
        autoRotate = device.autoRotate;
        if (device.fullOrientation != -1) {
            lastOrientation = device.fullOrientation;
            setRequestedOrientation(device.fullOrientation);
        } else if (!autoRotate) lockOrientation();
        if (!Objects.equals(device.startApp, "")) {
            activityFullBinding.buttonHome.setVisibility(View.GONE);
            activityFullBinding.buttonSwitch.setVisibility(View.GONE);
        }
        // 按键监听
        setButtonListener();
        setFloatButton();
        setKeyEvent();
        // 更新textureView；双击“全屏”等竞态下 textureView 可能仍挂在旧父容器上，
        // 先解除再添加，避免 “The specified child already has a parent” 崩溃
        View textureView = clientController.getTextureView();
        ViewParent oldParent = textureView.getParent();
        if (oldParent instanceof ViewGroup) ((ViewGroup) oldParent).removeView(textureView);
        activityFullBinding.textureViewLayout.addView(textureView, 0);
        // 切换回全屏时恢复光标覆盖层
        if (clientController.isMouseMode()) clientController.showCursorOverlay();
        activityFullBinding.textureViewLayout.post(this::updateMaxSize);
        // 页面自动旋转
        AppData.sensorManager.registerListener(this, AppData.sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onPause() {
        AppData.sensorManager.unregisterListener(this);
        if (device == null || clientController == null) {
            super.onPause();
            return;
        }
        if (controlPanel != null) controlPanel.hide(false);
        if (isChangingConfigurations())
            activityFullBinding.textureViewLayout.removeView(clientController.getTextureView());
        else if (!isClose)
            clientController.handleAction(device.fullToMiniOnRunning ? "changeToMini" : "changeToSmall", ByteBuffer.wrap("changeToFull".getBytes()), 0);
        super.onPause();
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {
        if (device != null && clientController != null)
            activityFullBinding.textureViewLayout.post(this::updateMaxSize);
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
    }

    @Override
    public void onBackPressed() {
    }

    private void updateMaxSize() {
        int width = activityFullBinding.textureViewLayout.getMeasuredWidth();
        int height = activityFullBinding.textureViewLayout.getMeasuredHeight();
        ByteBuffer byteBuffer = ByteBuffer.allocate(8);
        byteBuffer.putInt(width);
        byteBuffer.putInt(height);
        byteBuffer.flip();
        clientController.handleAction("updateMaxSize", byteBuffer, 0);
        if (!device.customResolutionOnConnect && device.changeResolutionOnRunning)
            clientController.handleAction("writeByteBuffer", ControlPacket.createChangeResolutionEvent((float) width / height), 0);
    }

    public void hide() {
        if (device == null || clientController == null) return;
        try {
            isClose = true;
            activityFullBinding.textureViewLayout.removeView(clientController.getTextureView());
            finish();
        } catch (Exception ignored) {
        }
    }

    // 设置按钮监听
    private void setButtonListener() {
        activityFullBinding.buttonBack.setOnClickListener(v -> clientController.handleAction("buttonBack", null, 0));
        activityFullBinding.buttonHome.setOnClickListener(v -> clientController.handleAction("buttonHome", null, 0));
        activityFullBinding.buttonSwitch.setOnClickListener(v -> clientController.handleAction("buttonSwitch", null, 0));
    }

    // ===== 控制面板 =====
    private void showControlPanel() {
        if (controlPanel != null) return;
        activityFullBinding.buttonFloat.setVisibility(View.GONE);
        controlPanel = new ControlPanel(this, activityFullBinding.getRoot(), device, clientController, this);
        controlPanel.show();
    }

    @Override
    public void onPanelClosed() {
        controlPanel = null;
        activityFullBinding.buttonFloat.setVisibility(View.VISIBLE);
        moveFloatButton(false, false);
        scheduleTuck();
    }

    @Override
    public boolean isFull() {
        return true;
    }

    @Override
    public boolean isAutoRotate() {
        return autoRotate;
    }

    // 关掉就锁在当前方向；开关和方向都记到设备上
    @Override
    public void setAutoRotate(boolean isAuto) {
        autoRotate = isAuto;
        device.autoRotate = isAuto;
        clientController.saveDevice();
        lastOrientation = -1;
        if (!isAuto) lockOrientation();
    }

    private void lockOrientation() {
        int orientation = orientationOf(getWindowManager().getDefaultDisplay().getRotation());
        lastOrientation = orientation;
        setRequestedOrientation(orientation);
        // 分屏/小窗模式下系统不理会方向请求，用户看到的不是这个方向，别记
        if (isInMultiWindow()) return;
        device.fullOrientation = orientation;
        clientController.saveDevice();
    }

    private boolean isInMultiWindow() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode();
    }

    // 设备相对自然方向转了多少(Surface.ROTATION_*)换算成 SCREEN_ORIENTATION_*，自然方向是竖屏还是横屏的设备都适用
    private int orientationOf(int rotation) {
        Display display = getWindowManager().getDefaultDisplay();
        int displayRotation = display.getRotation();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        boolean isNaturalPortrait = ((displayRotation == Surface.ROTATION_0 || displayRotation == Surface.ROTATION_180) && metrics.heightPixels > metrics.widthPixels)
                || ((displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) && metrics.widthPixels > metrics.heightPixels);
        switch (rotation) {
            case Surface.ROTATION_90:
                return isNaturalPortrait ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            case Surface.ROTATION_180:
                return isNaturalPortrait ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            case Surface.ROTATION_270:
                return isNaturalPortrait ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            default:
                return isNaturalPortrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }
    }

    @Override
    public void setNavBarShown(boolean isShow) {
        clientController.setNavBarShown(isShow);
        showNavBar(isShow);
    }

    private void showNavBar(boolean isShow) {
        activityFullBinding.navBar.setVisibility(isShow ? View.VISIBLE : View.GONE);
        activityFullBinding.textureViewLayout.post(this::updateMaxSize);
    }

    // ===== 悬浮按钮：点一下打开面板；拖到哪松手就吸到最近的一条边，闲置几秒缩进边里一半、变淡 =====
    private static final int EDGE_LEFT = 0;
    private static final int EDGE_TOP = 1;
    private static final int EDGE_RIGHT = 2;
    private static final int EDGE_BOTTOM = 3;
    private final Runnable tuckRunnable = () -> moveFloatButton(true, true);

    private void scheduleTuck() {
        activityFullBinding.buttonFloat.removeCallbacks(tuckRunnable);
        if (controlPanel == null) activityFullBinding.buttonFloat.postDelayed(tuckRunnable, 2500);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setFloatButton() {
        View button = activityFullBinding.buttonFloat;
        View root = activityFullBinding.getRoot();
        int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        float[] start = new float[4];
        boolean[] isDrag = new boolean[1];
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    button.removeCallbacks(tuckRunnable);
                    button.animate().cancel();
                    button.setAlpha(1f);
                    start[0] = event.getRawX();
                    start[1] = event.getRawY();
                    start[2] = button.getX();
                    start[3] = button.getY();
                    isDrag[0] = false;
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - start[0];
                    float dy = event.getRawY() - start[1];
                    if (!isDrag[0] && dx * dx + dy * dy > touchSlop * touchSlop) isDrag[0] = true;
                    if (isDrag[0]) {
                        int size = button.getLayoutParams().width;
                        button.setX(Math.max(0, Math.min(root.getWidth() - size, start[2] + dx)));
                        button.setY(Math.max(0, Math.min(root.getHeight() - size, start[3] + dy)));
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                    // 缩着的时候点露出来的一半也直接打开面板
                    if (isDrag[0]) {
                        snapFloatButton(button.getX(), button.getY());
                        moveFloatButton(false, true);
                        scheduleTuck();
                    } else showControlPanel();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    moveFloatButton(false, true);
                    scheduleTuck();
                    break;
            }
            return true;
        });
        // 缩在左右边时露出的一半正好在系统返回手势的区域里，不排除的话横着拖会触发返回
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            button.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    button.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, right - left, bottom - top))));
        // 画面区域变化(旋转、开关导航栏)时重新贴边
        activityFullBinding.textureViewLayout.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                moveFloatButton(false, false);
                scheduleTuck();
            }
        });
    }

    // 悬浮按钮能贴的范围：画面区域(不压导航栏)
    private Rect floatArea() {
        View area = activityFullBinding.textureViewLayout;
        return new Rect(area.getLeft(), area.getTop(), area.getRight(), area.getBottom());
    }

    // 松手的位置离哪条边最近就记哪条边，和在这条边上的位置(0-1)
    private void snapFloatButton(float x, float y) {
        Rect area = floatArea();
        int size = activityFullBinding.buttonFloat.getLayoutParams().width;
        float centerX = x + size / 2f;
        float centerY = y + size / 2f;
        float[] distance = new float[]{centerX - area.left, centerY - area.top, area.right - centerX, area.bottom - centerY};
        int edge = EDGE_LEFT;
        for (int i = 1; i < distance.length; i++) if (distance[i] < distance[edge]) edge = i;
        boolean isVertical = edge == EDGE_LEFT || edge == EDGE_RIGHT;
        float range = isVertical ? area.height() - size : area.width() - size;
        float offset = isVertical ? y - area.top : x - area.left;
        AppData.setting.setFloatEdge(edge);
        AppData.setting.setFloatPos(range > 0 ? Math.max(0f, Math.min(1f, offset / range)) : 0f);
    }

    // 摆到记住的边上。tuck：缩进边里一半(左右的屏幕边才缩，其他只变淡)
    private void moveFloatButton(boolean tuck, boolean animate) {
        View button = activityFullBinding.buttonFloat;
        View root = activityFullBinding.getRoot();
        Rect area = floatArea();
        // 第一次布局时按钮自己可能还没量过，用布局里写死的大小
        int size = button.getLayoutParams().width;
        int edge = AppData.setting.getFloatEdge();
        float pos = AppData.setting.getFloatPos();
        boolean isVertical = edge == EDGE_LEFT || edge == EDGE_RIGHT;
        float x = isVertical ? (edge == EDGE_LEFT ? area.left : area.right - size) : area.left + Math.max(0, area.width() - size) * pos;
        float y = isVertical ? area.top + Math.max(0, area.height() - size) * pos : (edge == EDGE_TOP ? area.top : area.bottom - size);
        // 挡在挖孔上就沿着这条边挪开
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowInsets insets = root.getRootWindowInsets();
            DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
            if (cutout != null) {
                int[] location = new int[2];
                root.getLocationInWindow(location);
                for (Rect hole : cutout.getBoundingRects()) {
                    hole.offset(-location[0], -location[1]);
                    if (!Rect.intersects(hole, new Rect((int) x, (int) y, (int) x + size, (int) y + size))) continue;
                    if (isVertical) {
                        float before = hole.top - size;
                        y = before >= area.top && (y - before < hole.bottom - y || hole.bottom > area.bottom - size) ? before : hole.bottom;
                    } else {
                        float before = hole.left - size;
                        x = before >= area.left && (x - before < hole.right - x || hole.right > area.right - size) ? before : hole.right;
                    }
                }
            }
        }
        // 只在左右的屏幕边缩进：上下边是下拉通知、回桌面的系统手势区，没法排除，缩进去露出的一半一拖就触发手势
        boolean canTuck = (edge == EDGE_LEFT && area.left <= 0) || (edge == EDGE_RIGHT && area.right >= root.getWidth());
        if (tuck && canTuck) x += edge == EDGE_LEFT ? -size / 2f : size / 2f;
        float alpha = !tuck ? 1f : canTuck ? 0.6f : 0.35f;
        if (animate) button.animate().x(x).y(y).alpha(alpha).setStartDelay(0).setDuration(tuck ? 300 : 200).start();
        else {
            button.animate().cancel();
            button.setX(x);
            button.setY(y);
            button.setAlpha(alpha);
        }
    }

    private int lastOrientation = -1;

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (!autoRotate || Sensor.TYPE_ACCELEROMETER != sensorEvent.sensor.getType()) return;
        if (isInMultiWindow()) return;
        float[] values = sensorEvent.values;
        float x = values[0];
        float y = values[1];
        // 加速度计的轴跟着设备的自然方向走：先算出转了多少，再按自然方向换算
        int rotation;
        if (x > -3 && x < 3 && y >= 4.5) rotation = Surface.ROTATION_0;
        else if (y > -3 && y < 3 && x >= 4.5) rotation = Surface.ROTATION_90;
        else if (y > -3 && y < 3 && x <= -4.5) rotation = Surface.ROTATION_270;
        else if (x > -3 && x < 3 && y <= -4.5) rotation = Surface.ROTATION_180;
        else return;
        int newOrientation = orientationOf(rotation);

        if (lastOrientation != newOrientation) {
            lastOrientation = newOrientation;
            device.fullOrientation = newOrientation;
            clientController.saveDevice();
            setRequestedOrientation(newOrientation);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    // 设置键盘监听；面板打开时返回键用来关面板，平时照常发给被控端
    private void setKeyEvent() {
        activityFullBinding.editText.requestFocus();
        activityFullBinding.editText.setInputType(InputType.TYPE_NULL);
        activityFullBinding.editText.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && controlPanel != null) {
                if (event.getAction() == KeyEvent.ACTION_UP) controlPanel.hide(true);
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                clientController.handleAction("writeByteBuffer", ControlPacket.createKeyEvent(event.getKeyCode(), event.getMetaState()), 0);
                return true;
            }
            return false;
        });
    }
}
