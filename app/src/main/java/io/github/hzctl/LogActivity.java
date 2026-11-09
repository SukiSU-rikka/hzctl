package io.github.hzctl;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

/** 诊断日志：环境 + 模块日志 + dumpsys 关键行，一键复制 */
public class LogActivity extends AppCompatActivity {
    private TextView body;
    private MaterialButton btnRefresh;
    private String text = "";

    /** 一次 su 跑完所有采集；整条命令作为 su -c 的单个参数传入，管道不会被拆 */
    private static final String COLLECT =
            "echo '== 模块日志 (" + Cfg.LOG_FILE + ")'; "
            + "if [ -f " + Cfg.LOG_FILE + " ]; then tail -n 400 " + Cfg.LOG_FILE + "; else echo '(还没有日志：模块未加载，或装上后还没重启)'; fi; "
            + "echo; echo '== dumpsys display'; "
            + "dumpsys display | grep -iE 'DesiredDisplayModeSpecs|ActiveSfDisplayMode|mActiveModeId|renderFrameRate|PRIORITY_|supportedModes|mDisplayModeSpecs|userPreferred' | cut -c1-600 | head -60; "
            + "echo; echo '== dumpsys SurfaceFlinger'; "
            + "dumpsys SurfaceFlinger | grep -iE 'displayManagerPolicy|activeMode|peak-refresh|FPS min|DynFPS|refresh rate|RefreshRate|frameRateOverride|gameMode' | cut -c1-600 | head -60; "
            + "echo; echo '== 刷新率相关 settings'; "
            + "for t in global secure system; do settings list $t | grep -iE 'refresh|fps|frame|hz' | sed \"s/^/$t: /\"; done; "
            + "echo; echo '== 系统属性'; "
            + "getprop | grep -iE 'refresh|fps|frame_rate|display\\.mode|vendor\\.display' | head -40; exit 0";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_log);
        View root = findViewById(R.id.logRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, ins) -> {
            Insets i = ins.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        body = findViewById(R.id.logBody);
        btnRefresh = findViewById(R.id.btnRefresh);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnRefresh.setOnClickListener(v -> collect());
        findViewById(R.id.btnCopy).setOnClickListener(v -> copy());
        findViewById(R.id.btnClear).setOnClickListener(v -> clear());
        collect();
    }

    private String header() {
        StringBuilder sb = new StringBuilder();
        sb.append("== 环境\n");
        sb.append("机型 ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("系统 ").append(Build.DISPLAY).append("，Android ").append(Build.VERSION.RELEASE)
                .append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("HzCtl ").append(BuildConfigLite.VERSION).append('\n');
        SharedPreferences p = App.prefs;
        if (p == null) {
            sb.append("模块：未激活（App 没收到 LSPosed 服务）\n");
        } else {
            sb.append("模块：已激活，").append(App.framework()).append('\n');
            sb.append("配置：enabled=").append(p.getBoolean(Cfg.ENABLED, false))
                    .append(" target=").append(p.getInt(Cfg.TARGET, 0))
                    .append(" mode=").append(p.getInt(Cfg.MODE, Cfg.MODE_LOCK) == Cfg.MODE_LOCK ? "lock" : "peak")
                    .append('\n');
        }
        Display d = getDisplay();
        if (d != null) {
            Display.Mode m = d.getMode();
            sb.append("当前：mode #").append(m.getModeId()).append(' ')
                    .append(m.getPhysicalWidth()).append('x').append(m.getPhysicalHeight())
                    .append(" @").append(m.getRefreshRate()).append("Hz，渲染 ").append(d.getRefreshRate()).append("Hz\n");
            sb.append("应用可见模式：");
            for (Display.Mode x : d.getSupportedModes())
                sb.append(" #").append(x.getModeId()).append(' ').append(x.getPhysicalWidth()).append('x')
                        .append(x.getPhysicalHeight()).append('@').append(Math.round(x.getRefreshRate()));
            sb.append('\n');
        }
        return sb.append('\n').toString();
    }

    private void collect() {
        btnRefresh.setEnabled(false);
        body.setText("采集中…");
        String head = header();
        new Thread(() -> {
            String out = Root.run(COLLECT);
            String all = head + (out != null ? out : "== 采集失败：没有拿到 root 权限，请在 SukiSU 里给「刷新率调节」授权\n");
            runOnUiThread(() -> {
                text = all;
                body.setText(all);
                btnRefresh.setEnabled(true);
            });
        }).start();
    }

    private void copy() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("HzCtl 诊断", text));
        Toast.makeText(this, "已复制 " + text.length() + " 个字符", Toast.LENGTH_SHORT).show();
    }

    private void clear() {
        new Thread(() -> {
            boolean ok = Root.run("rm -f " + Cfg.LOG_FILE) != null;
            runOnUiThread(() -> {
                Toast.makeText(this, ok ? "已清空模块日志" : "没有 root 权限，清空失败", Toast.LENGTH_SHORT).show();
                collect();
            });
        }).start();
    }
}
