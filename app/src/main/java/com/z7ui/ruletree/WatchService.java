package com.z7ui.ruletree;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * 常驻监听。三条腿走路，缺一条都可能漏事件：
 *
 * 1. 网络回调：正常情况下最快，但实测在小米上关 Wi-Fi 后收不到 onLost
 * 2. 每 5 秒轮询一次连接状态：兜底，状态一变就补发
 * 3. 电源事件用运行时接收器（隐式广播在 manifest 里收不到，这是另一回事）
 *
 * 三个来源都走 notifyWifi()，由它做去重，避免同一个变化被触发两次。
 */
public class WatchService extends Service {

    private static final String CHANNEL = "watcher";
    private static final int NOTI_ID = 2001;
    private static final long POLL_MS = 5000L;
    /** 连上 Wi-Fi 后再等一会儿才判断，避开「可用但还没连接」的瞬间。 */
    private static final long CONNECT_SETTLE_MS = 1200L;

    private static boolean started = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Boolean lastWifi = null;
    private int wifiSeq = 0;

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private BroadcastReceiver powerReceiver;
    private BroadcastReceiver btReceiver;

    public static void start(Context c) {
        ContextCompat.startForegroundService(c, new Intent(c, WatchService.class));
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, WatchService.class));
    }

    /** 有启用的规则才挂常驻通知；一条都没有就把服务停掉，别在通知栏留东西。 */
    public static void sync(Context c) {
        int enabled = 0;
        for (Model.Rule r : RuleStore.load(c)) {
            if (r.enabled) enabled++;
        }
        // 定时规则不依赖这个服务，任何时候都重排一遍
        TimeScheduler.sync(c);
        // 顺便挂上兜底闹钟：服务被系统清掉后，15 分钟内会被拉回来
        Watchdog.schedule(c);
        if (enabled > 0) start(c);
        else stop(c);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        if (started) return START_STICKY;
        started = true;

        lastWifi = wifiConnected();
        Logx.i(this, "监听启动，当前 Wi-Fi=" + lastWifi);

        registerWifiCallback();
        registerPowerReceiver();
        registerBluetoothReceiver();

        handler.removeCallbacks(poll);
        handler.postDelayed(poll, POLL_MS);
        return START_STICKY;
    }

    // ---------------- Wi-Fi ----------------

    private boolean wifiConnected() {
        try {
            ConnectivityManager m = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (m == null) return false;
            Network n = m.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities caps = m.getNetworkCapabilities(n);
            return caps != null
                    && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    private void notifyWifi(boolean connected, String from) {
        if (lastWifi != null && lastWifi == connected) return;
        lastWifi = connected;
        Logx.i(this, "Wi-Fi 变为 " + (connected ? "已连接" : "已断开") + "（来源 " + from + "）");

        if (!connected) {
            Engine.fire(this, "wifi_state", "disconnected");
            return;
        }

        // onAvailable 只是「网络可用」，这时 getActiveNetworkInfo 往往还没转成已连接，
        // 立刻判断条件会得到 false。等一会儿再确认一次。
        final int token = ++wifiSeq;
        handler.postDelayed(() -> {
            if (token != wifiSeq) return;          // 等待期间状态又变了
            if (!wifiConnected()) {
                Logx.i(WatchService.this, "等待 1.2 秒后仍不是已连接，跳过这次触发");
                return;
            }
            Engine.fire(WatchService.this, "wifi_state", "connected");
        }, CONNECT_SETTLE_MS);
    }

    private void registerWifiCallback() {
        if (wifiCallback != null) return;
        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                notifyWifi(true, "网络回调");
            }

            @Override
            public void onLost(Network network) {
                notifyWifi(false, "网络回调");
            }
        };
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            cm.registerNetworkCallback(req, wifiCallback);
        } catch (Exception e) {
            Logx.i(this, "注册网络回调失败: " + e);
        }
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            notifyWifi(wifiConnected(), "轮询");
            handler.postDelayed(this, POLL_MS);
        }
    };

    // ---------------- 电源 ----------------

    private void registerPowerReceiver() {
        if (powerReceiver != null) return;
        powerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_POWER_CONNECTED.equals(a)) {
                    Logx.i(WatchService.this, "电源已接入");
                    Engine.fire(WatchService.this, "power_state", "connected");
                } else if (Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                    Logx.i(WatchService.this, "电源已断开");
                    Engine.fire(WatchService.this, "power_state", "disconnected");
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        ContextCompat.registerReceiver(this, powerReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    // ---------------- 蓝牙 ----------------

    /**
     * ACL 广播只有运行时注册收得到，所以要挂在这个服务里。
     * 读设备名和 MAC 需要 BLUETOOTH_CONNECT，没授权时拿到的都是 null，
     * 这条规则如果限定了设备就匹配不上，不会误触发。
     */
    private void registerBluetoothReceiver() {
        if (btReceiver != null) return;
        btReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i) {
                String a = i.getAction();
                boolean connected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(a);
                if (!connected && !BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) return;

                BluetoothDevice dev = null;
                try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice.class);
                    } else {
                        dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    }
                } catch (Exception ignored) {
                }

                String mac = null;
                String name = null;
                try {
                    if (dev != null) {
                        mac = dev.getAddress();
                        name = dev.getName();
                    }
                } catch (Exception e) {
                    Logx.i(WatchService.this, "读蓝牙设备信息失败（缺 BLUETOOTH_CONNECT）");
                }
                Logx.i(WatchService.this, "蓝牙" + (connected ? "连上" : "断开") + " "
                        + (name != null ? name : (mac != null ? mac : "未知设备")));
                Engine.fireBluetooth(WatchService.this,
                        connected ? "connected" : "disconnected", mac);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        ContextCompat.registerReceiver(this, btReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    // ---------------- 前台通知 ----------------

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "规则监听",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("规则树在监听")
                .setContentText("Wi-Fi、电源、蓝牙和定时会触发规则")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTI_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            ServiceCompat.startForeground(this, NOTI_ID, n, 0);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (cm != null && wifiCallback != null) {
            try {
                cm.unregisterNetworkCallback(wifiCallback);
            } catch (Exception ignored) {
            }
            wifiCallback = null;
        }
        if (powerReceiver != null) {
            try {
                unregisterReceiver(powerReceiver);
            } catch (Exception ignored) {
            }
            powerReceiver = null;
        }
        if (btReceiver != null) {
            try {
                unregisterReceiver(btReceiver);
            } catch (Exception ignored) {
            }
            btReceiver = null;
        }
        started = false;
        Logx.i(this, "监听服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
