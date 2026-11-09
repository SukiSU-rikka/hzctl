package io.github.hzctl;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 入口（libxposed API 102），作用域：系统框架。
 *
 * 刷新率在 system_server 里最后一站是
 *   LocalDisplayAdapter$LocalDisplayDevice.setDesiredDisplayModeSpecsLocked(DesiredDisplayModeSpecs)
 * DisplayModeDirector 汇总完所有投票（用户设置 120 上限、省电、温控、应用请求……）后，结果从这里交给 SurfaceFlinger。
 * 在这里换成一份改过的副本：baseModeId 指向目标模式，primary / appRequest 的范围改成目标值，
 * 原对象不动，关闭接管后原样放行即可恢复。
 */
public class HzModule extends XposedModule {
    static final String TAG = "HzCtl";
    private static final int TYPE_INTERNAL = 1; // Display.TYPE_INTERNAL

    /** 每块屏最近一次收到的原始 specs，配置变化时拿来重新下发 */
    private final Map<Object, Object> lastSpecs = Collections.synchronizedMap(new WeakHashMap<>());
    private volatile Method setSpecs;
    private volatile SharedPreferences prefs;
    private Handler bg;
    private volatile String lastNote = "";
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sp, key) -> scheduleReapply();

    public HzModule() {
        super();
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        try {
            Class<?> dev = param.getClassLoader()
                    .loadClass("com.android.server.display.LocalDisplayAdapter$LocalDisplayDevice");
            int n = 0;
            for (Method m : dev.getDeclaredMethods()) {
                if (!m.getName().equals("setDesiredDisplayModeSpecsLocked") || m.getParameterCount() != 1) continue;
                m.setAccessible(true);
                setSpecs = m;
                hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object self = chain.getThisObject();
                    Object spec = chain.getArg(0);
                    if (self == null || spec == null) return chain.proceed();
                    lastSpecs.put(self, spec);
                    Object patched = null;
                    try {
                        patched = patch(self, spec);
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "patch failed", t);
                    }
                    return patched != null ? chain.proceed(new Object[]{patched}) : chain.proceed();
                });
                n++;
            }
            log(Log.INFO, TAG, "hooked " + n + " method(s)");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "init failed", t);
        }
    }

    // ---------------- 改写 ----------------

    private Object patch(Object dev, Object spec) throws Throwable {
        SharedPreferences p = prefs();
        if (p == null) return null;
        if (!p.getBoolean(Cfg.ENABLED, false)) {
            note("off, system policy untouched");
            return null;
        }
        int target = p.getInt(Cfg.TARGET, 0);
        if (target <= 0) return null;
        boolean lock = p.getInt(Cfg.MODE, Cfg.MODE_LOCK) == Cfg.MODE_LOCK;

        Object info = method(dev.getClass(), "getDisplayDeviceInfoLocked").invoke(dev);
        if (info == null || field(info.getClass(), "type").getInt(info) != TYPE_INTERNAL) return null;
        Display.Mode[] modes = (Display.Mode[]) field(info.getClass(), "supportedModes").get(info);
        if (modes == null || modes.length == 0) return null;

        int baseId = field(spec.getClass(), "baseModeId").getInt(spec);
        Display.Mode base = null;
        for (Display.Mode m : modes) if (m.getModeId() == baseId) base = m;

        Display.Mode best = pick(modes, base, target);
        if (best == null && base != null) best = pick(modes, null, target); // 当前分辨率下没有，就放宽分辨率
        if (best == null) {
            note("no " + target + "Hz mode, panel offers: " + describe(modes));
            return null;
        }

        float hz = best.getRefreshRate();
        Object out = copy(spec);
        field(out.getClass(), "baseModeId").setInt(out, best.getModeId());
        retarget(out, "primary", hz, lock);
        retarget(out, "appRequest", hz, lock);
        note((lock ? "lock " : "peak ") + hz + "Hz -> mode #" + best.getModeId()
                + " " + best.getPhysicalWidth() + "x" + best.getPhysicalHeight());
        return out;
    }

    private static Display.Mode pick(Display.Mode[] modes, Display.Mode base, int target) {
        Display.Mode best = null;
        float bestDiff = Float.MAX_VALUE;
        for (Display.Mode m : modes) {
            if (base != null && (m.getPhysicalWidth() != base.getPhysicalWidth()
                    || m.getPhysicalHeight() != base.getPhysicalHeight())) continue;
            float d = Math.abs(m.getRefreshRate() - target);
            if (d < bestDiff) {
                bestDiff = d;
                best = m;
            }
        }
        return bestDiff <= 1.5f ? best : null;
    }

    /** 把 specs.<name>（RefreshRateRanges）整个换成新对象，不碰原来的 */
    private static void retarget(Object spec, String name, float hz, boolean lock) throws Throwable {
        Field f = field(spec.getClass(), name);
        Object ranges = f.get(spec);
        if (ranges == null) return;
        Object phys = field(ranges.getClass(), "physical").get(ranges);
        Object rend = field(ranges.getClass(), "render").get(ranges);
        Class<?> rangeCls = phys.getClass();
        float pMin = lock ? hz : Math.min(field(rangeCls, "min").getFloat(phys), hz);
        float rMin = lock ? hz : Math.min(field(rangeCls, "min").getFloat(rend), hz);

        Constructor<?> rc = rangeCls.getDeclaredConstructor(float.class, float.class);
        rc.setAccessible(true);
        Constructor<?> rsc = ranges.getClass().getDeclaredConstructor(rangeCls, rangeCls);
        rsc.setAccessible(true);
        f.set(spec, rsc.newInstance(rc.newInstance(pMin, hz), rc.newInstance(rMin, hz)));
    }

    private static Object copy(Object spec) throws Throwable {
        Class<?> c = spec.getClass();
        try {
            Constructor<?> k = c.getDeclaredConstructor(c);
            k.setAccessible(true);
            return k.newInstance(spec);
        } catch (NoSuchMethodException ignored) {
        }
        Constructor<?> k0 = c.getDeclaredConstructor();
        k0.setAccessible(true);
        Object o = k0.newInstance();
        method(c, "copyFrom").invoke(o, spec);
        return o;
    }

    // ---------------- 配置变化 → 立即重新下发 ----------------

    private SharedPreferences prefs() {
        SharedPreferences p = prefs;
        if (p != null) return p;
        synchronized (this) {
            if (prefs == null) {
                try {
                    SharedPreferences sp = getRemotePreferences(Cfg.PREFS);
                    sp.registerOnSharedPreferenceChangeListener(prefListener);
                    prefs = sp;
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "remote prefs not ready: " + t);
                }
            }
            return prefs;
        }
    }

    private synchronized void scheduleReapply() {
        if (bg == null) {
            HandlerThread t = new HandlerThread("HzCtl");
            t.start();
            bg = new Handler(t.getLooper());
        }
        bg.removeCallbacksAndMessages(null);
        bg.postDelayed(this::reapply, 150);
    }

    private void reapply() {
        Method m = setSpecs;
        if (m == null) return;
        Map<Object, Object> snap;
        synchronized (lastSpecs) {
            snap = new HashMap<>(lastSpecs);
        }
        for (Map.Entry<Object, Object> e : snap.entrySet()) {
            try {
                Object lock = syncRoot(e.getKey());
                if (lock == null) {
                    log(Log.WARN, TAG, "no sync root, change applies on next mode update");
                    continue;
                }
                // 走反射调用同样会经过上面的 hook，由它负责改写
                synchronized (lock) {
                    m.invoke(e.getKey(), e.getValue());
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "reapply failed", t);
            }
        }
    }

    private static Object syncRoot(Object dev) {
        Object adapter = null;
        try {
            adapter = field(dev.getClass(), "this$0").get(dev);
        } catch (Throwable ignored) {
        }
        if (adapter == null) {
            try {
                adapter = method(dev.getClass(), "getAdapterLocked").invoke(dev);
            } catch (Throwable ignored) {
            }
        }
        if (adapter == null) return null;
        try {
            return method(adapter.getClass(), "getSyncRoot").invoke(adapter);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 小工具 ----------------

    private void note(String msg) {
        if (msg.equals(lastNote)) return;
        lastNote = msg;
        log(Log.INFO, TAG, msg);
    }

    private static String describe(Display.Mode[] modes) {
        StringBuilder sb = new StringBuilder();
        for (Display.Mode m : modes) {
            sb.append('#').append(m.getModeId()).append(' ')
                    .append(m.getPhysicalWidth()).append('x').append(m.getPhysicalHeight())
                    .append('@').append(m.getRefreshRate()).append("; ");
        }
        return sb.toString();
    }

    static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(c.getName() + "." + name);
    }

    static Method method(Class<?> c, String name) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }
}
