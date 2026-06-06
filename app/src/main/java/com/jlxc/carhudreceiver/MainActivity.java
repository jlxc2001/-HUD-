package com.jlxc.carhudreceiver;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int PORT = 45678;
    private static final int REQ_LOCATION = 2001;
    private static final long OVERLAY_HIDE_DELAY_MS = 3000L;
    private static final long DOUBLE_TAP_MS = 320L;

    private FrameLayout root;
    private FrameLayout projectionLayer;
    private FrameLayout statusOverlay;
    private FrameLayout settingsOverlay;
    private ImageView imageView;
    private TextView centerText;
    private TextView topText;
    private TextView bottomText;
    private TextView cornerText;
    private TextView speedText;
    private TextView timeText;
    private Button settingsButton;
    private Switch homeSwitch;

    private SharedPreferences prefs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService serverExecutor;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private long lastFrameTime = 0L;
    private int frameCount = 0;
    private int lastBytes = 0;
    private String lastSource = "--";
    private int scaleMode = 0; // 0 fit, 1 fill
    private boolean mirrorMode = false;
    private boolean speedEnabled = false;
    private boolean timeEnabled = false;
    private boolean overlayVisible = true;
    private long lastTapTime = 0L;

    private LocationManager locationManager;
    private float currentSpeedKmh = -1f;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final ImageView.ScaleType[] scaleTypes = new ImageView.ScaleType[]{
            ImageView.ScaleType.FIT_CENTER,
            ImageView.ScaleType.CENTER_CROP
    };
    private final String[] scaleNames = new String[]{"适应", "铺满"};

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatusText();
            refreshProjectionWidgets();
            uiHandler.postDelayed(this, 1000L);
        }
    };

    private final Runnable hideOverlayRunnable = new Runnable() {
        @Override
        public void run() {
            if (settingsOverlay != null && settingsOverlay.getVisibility() == View.VISIBLE) return;
            // 未接收到第一帧之前，等待说明、IP、端口和设置按钮必须一直显示，
            // 方便用户填写发送端地址。只有收到导航画面后，才启用 3 秒自动隐藏。
            if (frameCount == 0) {
                setOverlayVisible(true);
                return;
            }
            setOverlayVisible(false);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location != null && location.hasSpeed()) {
                currentSpeedKmh = location.getSpeed() * 3.6f;
                refreshProjectionWidgets();
            }
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("hud_receiver_settings", MODE_PRIVATE);
        loadSettings();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemUi();

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        buildUi();
        applySettingsToUi();
        startServer();
        uiHandler.post(tickRunnable);
        resetOverlayAutoHideTimer();
        if (speedEnabled) startSpeedUpdatesIfAllowed();
    }

    private void loadSettings() {
        mirrorMode = prefs.getBoolean("mirrorMode", false);
        scaleMode = prefs.getInt("scaleMode", 0);
        if (scaleMode < 0 || scaleMode >= scaleTypes.length) scaleMode = 0;
        speedEnabled = prefs.getBoolean("speedEnabled", false);
        timeEnabled = prefs.getBoolean("timeEnabled", false);
    }

    private void saveSettings() {
        prefs.edit()
                .putBoolean("mirrorMode", mirrorMode)
                .putInt("scaleMode", scaleMode)
                .putBoolean("speedEnabled", speedEnabled)
                .putBoolean("timeEnabled", timeEnabled)
                .apply();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        projectionLayer = new FrameLayout(this);
        projectionLayer.setBackgroundColor(Color.BLACK);
        root.addView(projectionLayer, new FrameLayout.LayoutParams(-1, -1));

        imageView = new ImageView(this);
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setScaleType(scaleTypes[scaleMode]);
        imageView.setAdjustViewBounds(false);
        projectionLayer.addView(imageView, new FrameLayout.LayoutParams(-1, -1));

        speedText = makeProjectionText(36, true);
        speedText.setGravity(Gravity.LEFT);
        speedText.setPadding(dp(18), dp(8), dp(18), dp(8));
        speedText.setBackground(makePanelBg(70));
        FrameLayout.LayoutParams speedLp = new FrameLayout.LayoutParams(-2, -2);
        speedLp.gravity = Gravity.LEFT | Gravity.BOTTOM;
        speedLp.leftMargin = dp(22);
        speedLp.bottomMargin = dp(22);
        projectionLayer.addView(speedText, speedLp);

        timeText = makeProjectionText(30, true);
        timeText.setGravity(Gravity.RIGHT);
        timeText.setPadding(dp(18), dp(8), dp(18), dp(8));
        timeText.setBackground(makePanelBg(70));
        FrameLayout.LayoutParams timeLp = new FrameLayout.LayoutParams(-2, -2);
        timeLp.gravity = Gravity.RIGHT | Gravity.TOP;
        timeLp.rightMargin = dp(22);
        timeLp.topMargin = dp(22);
        projectionLayer.addView(timeText, timeLp);

        statusOverlay = new FrameLayout(this);
        root.addView(statusOverlay, new FrameLayout.LayoutParams(-1, -1));

        centerText = new TextView(this);
        centerText.setTextColor(Color.rgb(0, 245, 212));
        centerText.setTextSize(18);
        centerText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        centerText.setGravity(Gravity.CENTER);
        centerText.setText(makeWaitingText());
        centerText.setShadowLayer(10f, 0f, 0f, Color.rgb(0, 245, 212));
        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(-1, -2);
        centerLp.gravity = Gravity.CENTER;
        centerLp.leftMargin = dp(32);
        centerLp.rightMargin = dp(32);
        statusOverlay.addView(centerText, centerLp);

        topText = makeHudText(13, true);
        topText.setText("HUD接收端  |  NAVI CARD LINK");
        topText.setPadding(dp(14), dp(8), dp(14), dp(8));
        topText.setBackground(makePanelBg(120));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-2, -2);
        topLp.gravity = Gravity.TOP | Gravity.LEFT;
        topLp.leftMargin = dp(12);
        topLp.topMargin = dp(10);
        statusOverlay.addView(topText, topLp);

        cornerText = makeHudText(12, false);
        cornerText.setGravity(Gravity.RIGHT);
        cornerText.setPadding(dp(14), dp(8), dp(14), dp(8));
        cornerText.setBackground(makePanelBg(120));
        FrameLayout.LayoutParams cornerLp = new FrameLayout.LayoutParams(-2, -2);
        cornerLp.gravity = Gravity.TOP | Gravity.RIGHT;
        cornerLp.rightMargin = dp(110);
        cornerLp.topMargin = dp(10);
        statusOverlay.addView(cornerText, cornerLp);

        settingsButton = new Button(this);
        settingsButton.setText("设置");
        settingsButton.setTextColor(Color.WHITE);
        settingsButton.setTextSize(13);
        settingsButton.setAllCaps(false);
        settingsButton.setBackground(makeButtonBg());
        settingsButton.setOnClickListener(v -> showSettingsPanel());
        FrameLayout.LayoutParams setLp = new FrameLayout.LayoutParams(dp(88), dp(46));
        setLp.gravity = Gravity.TOP | Gravity.RIGHT;
        setLp.rightMargin = dp(12);
        setLp.topMargin = dp(10);
        statusOverlay.addView(settingsButton, setLp);

        bottomText = makeHudText(12, false);
        bottomText.setPadding(dp(14), dp(8), dp(14), dp(8));
        bottomText.setBackground(makePanelBg(120));
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(-1, -2);
        bottomLp.gravity = Gravity.BOTTOM;
        bottomLp.leftMargin = dp(12);
        bottomLp.rightMargin = dp(12);
        bottomLp.bottomMargin = dp(10);
        statusOverlay.addView(bottomText, bottomLp);

        buildSettingsPanel();

        root.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_UP) {
                onUserInteractionOnScreen();
            }
            if (event.getAction() == MotionEvent.ACTION_UP) handleTap();
            return true;
        });

        setContentView(root);
        refreshStatusText();
        refreshProjectionWidgets();
    }

    private TextView makeHudText(int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setTextColor(Color.argb(230, 190, 255, 245));
        tv.setTextSize(sp);
        tv.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        tv.setShadowLayer(5f, 0f, 0f, Color.argb(180, 0, 245, 212));
        return tv;
    }

    private TextView makeProjectionText(int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setTextColor(Color.argb(240, 0, 245, 212));
        tv.setTextSize(sp);
        tv.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        tv.setShadowLayer(12f, 0f, 0f, Color.argb(220, 0, 245, 212));
        return tv;
    }

    private GradientDrawable makePanelBg(int alpha) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(alpha, 0, 10, 12));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.argb(150, 0, 245, 212));
        return bg;
    }

    private GradientDrawable makeButtonBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(130, 0, 18, 22));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.argb(190, 0, 245, 212));
        return bg;
    }

    private String makeWaitingText() {
        String ipText = getLocalIpSummary();
        if (TextUtils.isEmpty(ipText)) {
            ipText = "未获取到 IPv4\n请确认接收端已连接 WiFi/热点";
        }
        return "HUD接收端已启动\n\n" +
                "监听端口：" + PORT + "\n" +
                "本机地址：\n" + ipText + "\n\n" +
                "发送端填写接收端 IP 和端口\n" +
                "等待导航卡片画面...";
    }

    private void buildSettingsPanel() {
        settingsOverlay = new FrameLayout(this);
        settingsOverlay.setVisibility(View.GONE);
        settingsOverlay.setClickable(true);
        settingsOverlay.setBackgroundColor(Color.argb(210, 0, 0, 0));
        root.addView(settingsOverlay, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setPadding(dp(18), dp(18), dp(18), dp(18));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(18), dp(22), dp(18));
        card.setBackground(makeSettingsBg());
        scrollView.addView(card, new ScrollView.LayoutParams(-1, -2));

        TextView title = makeSettingsTitle("HUD接收端 设置");
        card.addView(title);

        TextView subtitle = makeSettingsHint("接收发送端传来的导航卡片图像，并在接收端屏幕上以 HUD 方式显示。单击屏幕会显示状态栏，3 秒无触摸后自动隐藏。");
        card.addView(subtitle);

        Switch mirrorSwitch = makeSwitch("HUD反射倒像模式", "用于没有系统级镜像的设备。开启后，投影画面、GPS车速和时间会左右镜像，经过挡风玻璃反射后变正。", mirrorMode);
        mirrorSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mirrorMode = isChecked;
            saveSettings();
            applySettingsToUi();
        });
        card.addView(mirrorSwitch);

        TextView modeLabel = makeSettingsLabel("显示模式");
        card.addView(modeLabel);

        LinearLayout modeRow = makeRow();
        modeRow.addView(makeActionButton("适应模式", () -> {
            scaleMode = 0;
            saveSettings();
            applySettingsToUi();
            Toast.makeText(this, "已切换为适应模式", Toast.LENGTH_SHORT).show();
        }));
        modeRow.addView(makeActionButton("铺满模式", () -> {
            scaleMode = 1;
            saveSettings();
            applySettingsToUi();
            Toast.makeText(this, "已切换为铺满模式", Toast.LENGTH_SHORT).show();
        }));
        card.addView(modeRow);

        Switch speedSwitch = makeSwitch("车速表", "使用 GPS 速度显示 km/h。首次开启需要授予定位权限。车速表会跟随 HUD反射倒像模式。", speedEnabled);
        speedSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            speedEnabled = isChecked;
            saveSettings();
            if (speedEnabled) startSpeedUpdatesIfAllowed(); else stopSpeedUpdates();
            applySettingsToUi();
        });
        card.addView(speedSwitch);

        Switch timeSwitch = makeSwitch("时间显示", "在投影画面右上角显示当前时间。时间显示会跟随 HUD反射倒像模式。", timeEnabled);
        timeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            timeEnabled = isChecked;
            saveSettings();
            applySettingsToUi();
        });
        card.addView(timeSwitch);

        homeSwitch = makeSwitch("主页开关", "开启后会打开系统的默认主页/Launcher 设置。请选择“HUD接收端”作为默认主页应用。关闭时也会打开同一页面，方便切换回原来的桌面。", isDefaultHomeApp());
        homeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            openHomeAppSettings();
            uiHandler.postDelayed(this::refreshHomeSwitchState, 800L);
        });
        card.addView(homeSwitch);

        card.addView(makeSettingsLabel("关于该软件"));
        TextView about = makeSettingsHint(
                "软件名：HUD接收端\n" +
                "作者：江灵夏草\n" +
                "B站主页：https://space.bilibili.com/130914376\n" +
                "抖音：JLXC2001\n" +
                "X（原推特）：jlxc2001\n\n" +
                "说明：本软件用于接收同一局域网内发送端传来的导航卡片截图，并将其作为 HUD 画面显示。建议将接收端连接到稳定 WiFi/热点，并在路由器或热点管理中为接收端设置静态 IP，避免每次上车后 IP 改变。发送端与接收端必须处在同一局域网，且网络不要开启 AP 隔离。\n\n" +
                "推荐用法：发送端运行在车机上，接收端运行在用于风挡投影的 Android 设备上。端口默认 45678。"
        );
        card.addView(about);

        LinearLayout closeRow = makeRow();
        closeRow.addView(makeActionButton("关闭设置", () -> {
            hideSettingsPanel();
        }));
        card.addView(closeRow);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        settingsOverlay.addView(scrollView, lp);
    }

    private GradientDrawable makeSettingsBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(245, 7, 15, 20));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.argb(210, 0, 245, 212));
        return bg;
    }

    private TextView makeSettingsTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(24);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 0, 0, dp(10));
        return tv;
    }

    private TextView makeSettingsLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.rgb(0, 245, 212));
        tv.setTextSize(17);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(14), 0, dp(6));
        return tv;
    }

    private TextView makeSettingsHint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.argb(230, 220, 255, 250));
        tv.setTextSize(14);
        tv.setLineSpacing(0, 1.12f);
        tv.setPadding(0, dp(4), 0, dp(8));
        return tv;
    }

    private Switch makeSwitch(String title, String desc, boolean checked) {
        Switch sw = new Switch(this);
        sw.setText(title + "\n" + desc);
        sw.setTextColor(Color.WHITE);
        sw.setTextSize(15);
        sw.setPadding(0, dp(8), 0, dp(8));
        sw.setChecked(checked);
        return sw;
    }

    private LinearLayout makeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(6), 0, dp(6));
        return row;
    }

    private Button makeActionButton(String text, final Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setBackground(makeButtonBg());
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        lp.setMargins(dp(5), 0, dp(5), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void showSettingsPanel() {
        setOverlayVisible(false);
        settingsOverlay.setVisibility(View.VISIBLE);
        settingsOverlay.bringToFront();
        hideSystemUi();
    }

    private void hideSettingsPanel() {
        settingsOverlay.setVisibility(View.GONE);
        showOverlayTemporary();
        hideSystemUi();
    }

    private void onUserInteractionOnScreen() {
        if (settingsOverlay != null && settingsOverlay.getVisibility() == View.VISIBLE) return;
        showOverlayTemporary();
    }

    private void showOverlayTemporary() {
        setOverlayVisible(true);
        resetOverlayAutoHideTimer();
    }

    private void resetOverlayAutoHideTimer() {
        uiHandler.removeCallbacks(hideOverlayRunnable);
        // 未收到画面时不要自动隐藏任何提示文字或设置按钮；
        // 收到第一帧之后，再按 3 秒无触摸自动隐藏。
        if (frameCount == 0) {
            setOverlayVisible(true);
            return;
        }
        uiHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS);
    }

    private void setOverlayVisible(boolean visible) {
        overlayVisible = visible;
        if (statusOverlay != null) statusOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        hideSystemUi();
    }

    private void handleTap() {
        long now = System.currentTimeMillis();
        if (now - lastTapTime < DOUBLE_TAP_MS) {
            lastTapTime = 0L;
            showSettingsPanel();
            return;
        }
        lastTapTime = now;
    }

    private void applySettingsToUi() {
        if (imageView != null) imageView.setScaleType(scaleTypes[scaleMode]);
        if (projectionLayer != null) {
            projectionLayer.post(() -> {
                projectionLayer.setPivotX(projectionLayer.getWidth() / 2f);
                projectionLayer.setPivotY(projectionLayer.getHeight() / 2f);
                projectionLayer.setScaleX(mirrorMode ? -1f : 1f);
            });
        }
        refreshStatusText();
        refreshProjectionWidgets();
        hideSystemUi();
    }

    private void refreshProjectionWidgets() {
        if (speedText != null) {
            if (speedEnabled) {
                String speed = currentSpeedKmh < 0 ? "--" : String.format(Locale.CHINA, "%.0f", currentSpeedKmh);
                speedText.setText(speed + " km/h");
                speedText.setVisibility(View.VISIBLE);
            } else {
                speedText.setVisibility(View.GONE);
            }
        }
        if (timeText != null) {
            if (timeEnabled) {
                timeText.setText(timeFormat.format(new Date()));
                timeText.setVisibility(View.VISIBLE);
            } else {
                timeText.setVisibility(View.GONE);
            }
        }
    }

    private void refreshStatusText() {
        long now = System.currentTimeMillis();
        long ageMs = lastFrameTime == 0L ? -1L : now - lastFrameTime;
        boolean online = ageMs >= 0L && ageMs < 3500L;
        String link = online ? "ONLINE" : "WAITING";
        int linkColor = online ? Color.rgb(0, 245, 212) : Color.rgb(255, 176, 0);
        if (topText != null) {
            topText.setTextColor(linkColor);
            topText.setText("HUD接收端  |  " + link);
        }

        String ipText = getLocalIpSummaryOneLine();
        if (TextUtils.isEmpty(ipText)) ipText = "IP: --";
        if (cornerText != null) cornerText.setText(ipText + "\nPORT: " + PORT);

        String ageText = ageMs < 0L ? "--" : String.format(Locale.CHINA, "%.1fs", ageMs / 1000f);
        if (bottomText != null) {
            bottomText.setText(String.format(Locale.CHINA,
                    "MODE:%s   MIRROR:%s   GPS:%s   TIME:%s   FRAMES:%d   LAST:%s   SIZE:%.1fKB   SOURCE:%s   点击显示状态 / 双击打开设置",
                    scaleNames[scaleMode], mirrorMode ? "ON" : "OFF", speedEnabled ? "ON" : "OFF", timeEnabled ? "ON" : "OFF",
                    frameCount, ageText, lastBytes / 1024f, lastSource));
        }

        if (frameCount == 0 && centerText != null) {
            centerText.setVisibility(View.VISIBLE);
            centerText.setText(makeWaitingText());
        }
    }

    private boolean isDefaultHomeApp() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolveInfo = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return resolveInfo != null
                    && resolveInfo.activityInfo != null
                    && getPackageName().equals(resolveInfo.activityInfo.packageName);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void refreshHomeSwitchState() {
        if (homeSwitch == null) return;
        boolean checked = isDefaultHomeApp();
        if (homeSwitch.isChecked() != checked) {
            homeSwitch.setOnCheckedChangeListener(null);
            homeSwitch.setChecked(checked);
            homeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                openHomeAppSettings();
                uiHandler.postDelayed(this::refreshHomeSwitchState, 800L);
            });
        }
    }

    private void openHomeAppSettings() {
        Toast.makeText(this, "请在系统默认主页设置中选择或取消 HUD接收端", Toast.LENGTH_LONG).show();
        try {
            Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                startActivity(intent);
            } catch (Exception ignored) {
                Toast.makeText(this, "无法打开系统设置，请手动进入默认应用/主页设置", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startServer() {
        if (running.get()) return;
        running.set(true);
        serverExecutor = Executors.newSingleThreadExecutor();
        serverExecutor.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (running.get()) {
                    Socket socket = serverSocket.accept();
                    handleClient(socket);
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setOverlayVisible(true);
                    centerText.setVisibility(View.VISIBLE);
                    centerText.setText("接收服务启动失败\n" + e.getMessage());
                });
            }
        });
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(2200);
            String source = socket.getInetAddress() == null ? "--" : socket.getInetAddress().getHostAddress();
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String headers = readHeaders(in);
            int contentLength = parseContentLength(headers);
            if (contentLength <= 0 || contentLength > 3_500_000) {
                writeResponse(out, 400, "bad content length");
                socket.close();
                return;
            }

            byte[] body = readExact(in, contentLength);
            writeResponse(out, 200, "ok");
            socket.close();

            final Bitmap bitmap = BitmapFactory.decodeByteArray(body, 0, body.length);
            if (bitmap != null) runOnUiThread(() -> updateFrame(bitmap, body.length, source));
        } catch (Exception ignored) {
            try { socket.close(); } catch (Exception ignored2) {}
        }
    }

    private String readHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int state = 0;
        int b;
        while ((b = in.read()) != -1) {
            baos.write(b);
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = 0;
            if (baos.size() > 8192) break;
        }
        return baos.toString("UTF-8");
    }

    private int parseContentLength(String headers) {
        String[] lines = headers.split("\\r?\\n");
        for (String line : lines) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                String key = line.substring(0, idx).trim();
                if ("content-length".equalsIgnoreCase(key)) {
                    try { return Integer.parseInt(line.substring(idx + 1).trim()); } catch (Exception ignored) {}
                }
            }
        }
        return -1;
    }

    private byte[] readExact(InputStream in, int len) throws Exception {
        byte[] data = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(data, off, len - off);
            if (r < 0) throw new RuntimeException("unexpected eof");
            off += r;
        }
        return data;
    }

    private void writeResponse(OutputStream out, int code, String msg) throws Exception {
        String status = code == 200 ? "OK" : "ERROR";
        String body = msg == null ? "" : msg;
        String resp = "HTTP/1.1 " + code + " " + status + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "Connection: close\r\n\r\n" + body;
        out.write(resp.getBytes("UTF-8"));
        out.flush();
    }

    private void updateFrame(Bitmap bitmap, int bytes, String source) {
        Bitmap old = null;
        if (imageView.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
            old = ((android.graphics.drawable.BitmapDrawable) imageView.getDrawable()).getBitmap();
        }
        imageView.setImageBitmap(bitmap);
        if (old != null && old != bitmap && !old.isRecycled()) old.recycle();

        boolean firstFrame = frameCount == 0;
        frameCount++;
        lastFrameTime = System.currentTimeMillis();
        lastBytes = bytes;
        lastSource = TextUtils.isEmpty(source) ? "--" : source;
        centerText.setVisibility(View.GONE);
        refreshStatusText();

        // 第一帧到达后，才正式进入 HUD 显示状态，并启动 3 秒无触摸自动隐藏。
        if (firstFrame) {
            showOverlayTemporary();
        }
        hideSystemUi();
    }

    private void startSpeedUpdatesIfAllowed() {
        if (!speedEnabled) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        try {
            if (locationManager == null) return;
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener);
            }
        } catch (Exception e) {
            Toast.makeText(this, "车速表启动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopSpeedUpdates() {
        currentSpeedKmh = -1f;
        try {
            if (locationManager != null) locationManager.removeUpdates(locationListener);
        } catch (Exception ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                startSpeedUpdatesIfAllowed();
            } else {
                speedEnabled = false;
                saveSettings();
                applySettingsToUi();
                Toast.makeText(this, "未授予定位权限，车速表已关闭", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String getLocalIpSummary() {
        List<String> items = getLocalIpv4List();
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s).append(':').append(PORT);
        }
        return sb.toString();
    }

    private String getLocalIpSummaryOneLine() {
        List<String> items = getLocalIpv4List();
        if (items.isEmpty()) return "IP: --";
        return "IP: " + items.get(0) + ":" + PORT;
    }

    private List<String> getLocalIpv4List() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.startsWith("127.")) result.add(nif.getName() + " " + ip);
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        refreshStatusText();
        refreshProjectionWidgets();
        if (speedEnabled) startSpeedUpdatesIfAllowed();
        refreshHomeSwitchState();
        resetOverlayAutoHideTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (speedEnabled) stopSpeedUpdates();
    }

    @Override
    protected void onDestroy() {
        running.set(false);
        uiHandler.removeCallbacksAndMessages(null);
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (serverExecutor != null) serverExecutor.shutdownNow();
        stopSpeedUpdates();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
