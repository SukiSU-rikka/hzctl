package io.github.hzctl;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.android.material.color.DynamicColors;

import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class App extends Application implements XposedServiceHelper.OnServiceListener {
    /** LSPosed 递过来的远程配置；为 null 表示模块没激活 */
    static volatile SharedPreferences prefs;
    static volatile XposedService service;

    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService s) {
        service = s;
        try {
            prefs = s.getRemotePreferences(Cfg.PREFS);
        } catch (Throwable t) {
            prefs = null;
        }
        notifyChanged();
    }

    @Override
    public void onServiceDied(XposedService s) {
        service = null;
        prefs = null;
        notifyChanged();
    }

    static String framework() {
        XposedService s = service;
        if (s == null) return "";
        try {
            return s.getFrameworkName() + " " + s.getFrameworkVersion();
        } catch (Throwable t) {
            return "LSPosed";
        }
    }

    /** 只在本 App 里用的设置（悬浮窗相关） */
    static SharedPreferences local(Context c) {
        return c.getApplicationContext().getSharedPreferences("local", MODE_PRIVATE);
    }

    static void addListener(Runnable r) { listeners.add(r); }

    static void removeListener(Runnable r) { listeners.remove(r); }

    private static void notifyChanged() {
        main.post(() -> { for (Runnable r : listeners) r.run(); });
    }
}
