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
    /** displayToken -> 物理屏 id，用来在 SurfaceControl 层查 SF 的模式表 */
    private final Map<Object, Long> tokenPhys = Collections.synchronizedMap(new WeakHashMap<>());
    /** 每个调用者最近一条日志，去重用 */
    private final Map<String, String> callerNotes = Collections.synchronizedMap(new HashMap<>());
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sp, key) -> scheduleReapply();

    public HzModule() {
        super();
    }

    // ---------------- 日志：LSPosed 日志 + /data/system/hzctl/hzctl.log（App 用 root 读） ----------------

    static final String LOG_FILE = Cfg.LOG_FILE;
    private static final long LOG_MAX = 256 * 1024;
    private static Handler logH;
    private static final java.text.SimpleDateFormat TS =
            new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US);

    void L(int prio, String msg) {
        L(prio, msg, null);
    }

    void L(int prio, String msg, Throwable t) {
        try {
            if (t == null) log(prio, TAG, msg);
            else log(prio, TAG, msg, t);
        } catch (Throwable ignored) {
        }
        String lvl = prio >= Log.ERROR ? "E" : prio == Log.WARN ? "W" : "I";
        StringBuilder line = new StringBuilder();
        synchronized (TS) {
            line.append(TS.format(new java.util.Date()));
        }
        line.append(' ').append(lvl).append(' ').append(msg).append('\n');
        if (t != null) line.append(Log.getStackTraceString(t)).append('\n');
        fileAppend(line.toString());
    }

    private static synchronized void fileAppend(String text) {
        if (logH == null) {
            HandlerThread th = new HandlerThread("HzCtlLog");
            th.start();
            logH = new Handler(th.getLooper());
        }
        logH.post(() -> {
            try {
                java.io.File f = new java.io.File(LOG_FILE);
                java.io.File dir = f.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                if (f.length() > LOG_MAX) { // 超过上限只保留后一半
                    byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
                    int from = all.length / 2;
                    while (from < all.length && all[from] != '\n') from++;
                    java.nio.file.Files.write(f.toPath(),
                            java.util.Arrays.copyOfRange(all, Math.min(from + 1, all.length), all.length));
                }
                try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                    w.write(text);
                }
            } catch (Throwable ignored) {
            }
        });
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
                    rememberToken(self);
                    Object patched = null;
                    try {
                        patched = patch(self, spec);
                    } catch (Throwable t) {
                        L(Log.ERROR, "patch failed", t);
                    }
                    return patched != null ? chain.proceed(new Object[]{patched}) : chain.proceed();
                });
                n++;
            }
            n += hookSurfaceControl();
            L(Log.INFO, "======== system_server start, HzCtl " + BuildConfigLite.VERSION
                    + ", " + android.os.Build.MODEL + " / " + android.os.Build.DISPLAY
                    + ", SDK " + android.os.Build.VERSION.SDK_INT + " ========");
            L(Log.INFO, "hooked " + n + " method(s)");
        } catch (Throwable t) {
            L(Log.ERROR, "init failed", t);
        }
    }

    /**
     * 第二道：SurfaceControl.setDesiredDisplayModeSpecs 是 Java 层交给 SurfaceFlinger 的最后一步。
     * ColorOS 有自己的代码绕过 DisplayModeDirector 直接调它，把策略压成 [120,120]，
     * 所以这里不管谁调，都按配置改写，并把调用者记进日志。
     */
    private int hookSurfaceControl() {
        int n = 0;
        try {
            for (Method m : Class.forName("android.view.SurfaceControl").getDeclaredMethods()) {
                if (!m.getName().equals("setDesiredDisplayModeSpecs") || m.getParameterCount() != 2) continue;
                hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object token = chain.getArg(0);
                    Object arg = chain.getArg(1);
                    if (token == null || arg == null) return chain.proceed();
                    String caller = caller();
                    // Android 17 / ColorOS 这里传的是 DesiredDisplayModeSpecs[]，逐个改
                    if (arg instanceof Object[]) {
                        Object[] in = (Object[]) arg;
                        Object[] out = (Object[]) java.lang.reflect.Array.newInstance(
                                in.getClass().getComponentType(), in.length);
                        boolean changed = false;
                        for (int i = 0; i < in.length; i++) {
                            Object pt = safePatchSc(token, in[i], caller, "[" + i + "/" + in.length + "]");
                            out[i] = pt != null ? pt : in[i];
                            changed |= pt != null;
                        }
                        return changed ? chain.proceed(new Object[]{token, out}) : chain.proceed();
                    }
                    Object pt = safePatchSc(token, arg, caller, "");
                    return pt != null ? chain.proceed(new Object[]{token, pt}) : chain.proceed();
                });
                n++;
            }
        } catch (Throwable t) {
            L(Log.ERROR, "SurfaceControl hook failed", t);
        }
        return n;
    }

    private void rememberToken(Object dev) {
        try {
            Object tok = method(dev.getClass(), "getDisplayTokenLocked").invoke(dev);
            long pid = field(dev.getClass(), "mPhysicalDisplayId").getLong(dev);
            if (tok != null) tokenPhys.put(tok, pid);
        } catch (Throwable ignored) {
        }
    }

    private volatile boolean specShapeLogged;

    private Object safePatchSc(Object token, Object spec, String caller, String idx) {
        if (spec == null) return null;
        try {
            return patchSc(token, spec, caller, idx);
        } catch (Throwable t) {
            if (!specShapeLogged) { // 结构不认识时，把字段表打出来，下次照着改
                specShapeLogged = true;
                StringBuilder sb = new StringBuilder("spec class " + spec.getClass().getName() + " fields:");
                for (Class<?> k = spec.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                    for (Field f : k.getDeclaredFields()) sb.append(' ').append(f.getType().getSimpleName()).append(' ').append(f.getName()).append(';');
                L(Log.WARN, sb.toString());
                L(Log.ERROR, "sc patch failed", t);
            }
            return null;
        }
    }

    private Object patchSc(Object token, Object spec, String caller, String idx) throws Throwable {
        caller = idx + " " + caller;
        String in = "mode " + field(spec.getClass(), "defaultMode").getInt(spec) + " "
                + rangeStr(field(spec.getClass(), "primaryRanges").get(spec));

        SharedPreferences p = prefs();
        if (p == null || !p.getBoolean(Cfg.ENABLED, false)) {
            noteCaller(caller, "SF <- " + in + " (off) from " + caller);
            return null;
        }
        int target = p.getInt(Cfg.TARGET, 0);
        if (target <= 0) return null;
        boolean lock = p.getInt(Cfg.MODE, Cfg.MODE_LOCK) == Cfg.MODE_LOCK;

        Long pid = tokenPhys.get(token);
        if (pid == null && tokenPhys.size() == 1) {
            synchronized (tokenPhys) {
                pid = tokenPhys.values().iterator().next();
            }
        }
        if (pid == null) {
            noteCaller(caller, "unknown display token, from " + caller);
            return null;
        }
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        Object dyn = method(sc, "getDynamicDisplayInfo").invoke(null, pid);
        Object[] modes = (Object[]) field(dyn.getClass(), "supportedDisplayModes").get(dyn);
        int curId = field(spec.getClass(), "defaultMode").getInt(spec);

        Object cur = null;
        for (Object m : modes) if (field(m.getClass(), "id").getInt(m) == curId) cur = m;
        Object best = pickSc(modes, cur, target);
        if (best == null && cur != null) best = pickSc(modes, null, target);
        if (best == null) {
            noteCaller(caller, "SF has no " + target + "Hz mode");
            return null;
        }
        float hz = scRate(best);
        Object out = copy(spec);
        field(out.getClass(), "defaultMode").setInt(out, field(best.getClass(), "id").getInt(best));
        retarget(out, "primaryRanges", hz, lock);
        retarget(out, "appRequestRanges", hz, lock);
        noteCaller(caller, "SF <- " + in + " from " + caller + "  => rewritten to sfMode "
                + field(best.getClass(), "id").getInt(best) + " " + rangeStr(field(out.getClass(), "primaryRanges").get(out)));
        return out;
    }

    private static Object pickSc(Object[] modes, Object cur, int target) throws Throwable {
        Object best = null;
        float bestDiff = Float.MAX_VALUE;
        for (Object m : modes) {
            if (cur != null && (field(m.getClass(), "width").getInt(m) != field(cur.getClass(), "width").getInt(cur)
                    || field(m.getClass(), "height").getInt(m) != field(cur.getClass(), "height").getInt(cur))) continue;
            float d = Math.abs(scRate(m) - target);
            if (d < bestDiff) {
                bestDiff = d;
                best = m;
            }
        }
        return bestDiff <= 1.5f ? best : null;
    }

    private static float scRate(Object mode) throws Throwable {
        for (String n : new String[]{"peakRefreshRate", "refreshRate", "vsyncRate"}) {
            try {
                return field(mode.getClass(), n).getFloat(mode);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException("refresh rate of " + mode.getClass().getName());
    }

    private static String rangeStr(Object ranges) {
        try {
            Object ph = field(ranges.getClass(), "physical").get(ranges);
            Object re = field(ranges.getClass(), "render").get(ranges);
            return "phys[" + field(ph.getClass(), "min").getFloat(ph) + "," + field(ph.getClass(), "max").getFloat(ph)
                    + "] render[" + field(re.getClass(), "min").getFloat(re) + "," + field(re.getClass(), "max").getFloat(re) + "]";
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 调用栈里第一个不属于 SurfaceControl / 本模块 / hook 框架的帧，就是真正的调用者 */
    private static String caller() {
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        boolean pastStub = false;
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            // 先跳过本模块和 LSPosed 的调用链，直到被 hook 的 setDesiredDisplayModeSpecs 本身
            if (!pastStub) {
                if (e.getMethodName().equals("setDesiredDisplayModeSpecs")) pastStub = true;
                continue;
            }
            if (c.startsWith("android.os.") || c.contains("ExternalSyntheticLambda") || c.contains("PooledLambda")
                    || c.contains("OmniFunction"))
                continue;
            if (taken > 0) sb.append(" <- ");
            sb.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName());
            if (++taken == 5) break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    private void noteCaller(String caller, String msg) {
        if (msg.equals(callerNotes.get(caller))) return;
        callerNotes.put(caller, msg);
        L(Log.INFO, msg);
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
        note("DMD <- base #" + baseId + " " + rangeStr(field(spec.getClass(), "primary").get(spec))
                + "  => " + (lock ? "lock " : "peak ") + hz + "Hz mode #" + best.getModeId()
                + " " + best.getPhysicalWidth() + "x" + best.getPhysicalHeight());
        noteModes(modes);
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
                    L(Log.WARN, "remote prefs not ready: " + t);
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
        SharedPreferences sp = prefs;
        if (sp != null) {
            L(Log.INFO, "config changed: enabled=" + sp.getBoolean(Cfg.ENABLED, false)
                    + " target=" + sp.getInt(Cfg.TARGET, 0)
                    + " mode=" + (sp.getInt(Cfg.MODE, Cfg.MODE_LOCK) == Cfg.MODE_LOCK ? "lock" : "peak"));
        }
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
                    L(Log.WARN, "no sync root, change applies on next mode update");
                    continue;
                }
                // 走反射调用同样会经过上面的 hook，由它负责改写
                synchronized (lock) {
                    m.invoke(e.getKey(), e.getValue());
                }
            } catch (Throwable t) {
                L(Log.ERROR, "reapply failed", t);
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

    private volatile boolean modesLogged;

    private void noteModes(Display.Mode[] modes) {
        if (modesLogged) return;
        modesLogged = true;
        L(Log.INFO, "panel modes: " + describe(modes));
    }

    private void note(String msg) {
        if (msg.equals(lastNote)) return;
        lastNote = msg;
        L(Log.INFO, msg);
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
