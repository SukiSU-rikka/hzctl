package io.github.hzctl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.service.quicksettings.TileService;

/** 只负责让进程活着；悬浮窗本身由 Overlay 管 */
public class OverlayService extends Service {
    private static final String CH = "overlay";
    private static final String ACTION_STOP = "io.github.hzctl.STOP_OVERLAY";

    /** 先把悬浮窗加出来（有可见悬浮窗时允许后台启动前台服务），再起服务保活 */
    static void start(Context c) {
        App.local(c).edit().putBoolean("overlay", true).apply();
        Overlay.show(c);
        try {
            c.startForegroundService(new Intent(c, OverlayService.class));
        } catch (Throwable ignored) {
        }
        pokeTile(c);
    }

    static void stop(Context c) {
        App.local(c).edit().putBoolean("overlay", false).apply();
        c.stopService(new Intent(c, OverlayService.class));
        Overlay.hide();
        pokeTile(c);
    }

    static void pokeTile(Context c) {
        try {
            TileService.requestListeningState(c, new ComponentName(c, OverlayTile.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stop(this);
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "刷新率悬浮窗", NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent close = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("刷新率悬浮窗显示中")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "关闭悬浮窗", close).build())
                .setOngoing(true)
                .build();
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (Throwable ignored) {
        }
        Overlay.show(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Overlay.hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
