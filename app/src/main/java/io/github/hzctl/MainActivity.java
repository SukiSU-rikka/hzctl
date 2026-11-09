package io.github.hzctl;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView liveHz, liveSub, statusTitle, statusSub, modeHint, chipHint;
    private ImageView statusIcon;
    private MaterialCardView statusCard;
    private MaterialSwitch swEnable, swOverlay, swRender, swPass, swSf;
    private ChipGroup chips;
    private MaterialButtonToggleGroup modeGroup;
    private View ctlBody;
    private RateScale scale;

    private final List<Integer> rates = new ArrayList<>();
    private List<Integer> listed = new ArrayList<>();
    private boolean binding;
    private boolean pendingOverlay;

    private final Runnable onSvc = this::refreshState;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateLive();
            h.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, ins) -> {
            Insets i = ins.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        liveHz = findViewById(R.id.liveHz);
        liveSub = findViewById(R.id.liveSub);
        scale = findViewById(R.id.scale);
        statusCard = findViewById(R.id.statusCard);
        statusIcon = findViewById(R.id.statusIcon);
        statusTitle = findViewById(R.id.statusTitle);
        statusSub = findViewById(R.id.statusSub);
        ctlBody = findViewById(R.id.ctlBody);
        swEnable = findViewById(R.id.swEnable);
        chips = findViewById(R.id.chips);
        chipHint = findViewById(R.id.chipHint);
        modeGroup = findViewById(R.id.modeGroup);
        modeHint = findViewById(R.id.modeHint);
        swOverlay = findViewById(R.id.swOverlay);
        swRender = findViewById(R.id.swRender);
        swPass = findViewById(R.id.swPass);
        swSf = findViewById(R.id.swSf);

        findViewById(R.id.btnLog).setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        buildChips();
        wireControls();
        wireDisplay();
        App.addListener(onSvc);
        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingOverlay && Settings.canDrawOverlays(this)) {
            pendingOverlay = false;
            askNotifications();
            OverlayService.start(this);
        }
        syncLocal();
        h.post(tick);
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        App.removeListener(onSvc);
        super.onDestroy();
    }

    // ---------------- 实时读数 ----------------

    private void updateLive() {
        Display d = getDisplay();
        if (d == null) return;
        Display.Mode m = d.getMode();
        float mode = m.getRefreshRate();
        liveHz.setText(String.valueOf(Math.round(mode)));
        liveHz.setTextColor(Rates.color(mode));
        liveSub.setText("渲染帧率 " + Math.round(d.getRefreshRate()) + " Hz，分辨率 "
                + m.getPhysicalWidth() + "×" + m.getPhysicalHeight());
        scale.setCurrent(mode);
    }

    // ---------------- 档位 ----------------

    private void buildChips() {
        Display d = getDisplay();
        if (d != null) listed = Rates.listed(d);
        rates.clear();
        rates.addAll(listed);
        if (!rates.contains(Rates.UNLOCK)) {
            rates.add(Rates.UNLOCK);
            chipHint.setVisibility(View.VISIBLE);
        }
        rates.sort(Integer::compare);
        scale.setStops(rates);

        for (int r : rates) {
            Chip c = (Chip) getLayoutInflater().inflate(R.layout.chip_rate, chips, false);
            c.setId(View.generateViewId());
            c.setTag(r);
            c.setText(listed.contains(r) ? r + " Hz" : r + " Hz *");
            chips.addView(c);
        }
    }

    private int defaultTarget() {
        return rates.isEmpty() ? Rates.UNLOCK : rates.get(rates.size() - 1);
    }

    private int selectedTarget() {
        int id = chips.getCheckedChipId();
        if (id == View.NO_ID) return defaultTarget();
        return (int) chips.findViewById(id).getTag();
    }

    private void checkTarget(int hz) {
        for (int i = 0; i < chips.getChildCount(); i++) {
            View c = chips.getChildAt(i);
            if (Integer.valueOf(hz).equals(c.getTag())) {
                chips.check(c.getId());
                return;
            }
        }
        if (chips.getChildCount() > 0) chips.check(chips.getChildAt(chips.getChildCount() - 1).getId());
    }

    // ---------------- 控件 ----------------

    private void wireControls() {
        swEnable.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            SharedPreferences p = prefsOrWarn();
            if (p == null) return;
            p.edit().putBoolean(Cfg.ENABLED, on)
                    .putInt(Cfg.TARGET, selectedTarget())
                    .putInt(Cfg.MODE, modeGroup.getCheckedButtonId() == R.id.btnPeak ? Cfg.MODE_PEAK : Cfg.MODE_LOCK)
                    .apply();
            updateHints();
        });
        chips.setOnCheckedStateChangeListener((g, ids) -> {
            if (binding || ids.isEmpty()) return;
            SharedPreferences p = prefsOrWarn();
            if (p != null) p.edit().putInt(Cfg.TARGET, selectedTarget()).apply();
            updateHints();
        });
        modeGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (binding || !checked) return;
            SharedPreferences p = prefsOrWarn();
            if (p != null) p.edit().putInt(Cfg.MODE, id == R.id.btnPeak ? Cfg.MODE_PEAK : Cfg.MODE_LOCK).apply();
            updateHints();
        });
    }

    private void wireDisplay() {
        SharedPreferences local = App.local(this);
        swOverlay.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            if (!on) {
                OverlayService.stop(this);
                return;
            }
            if (!Settings.canDrawOverlays(this)) {
                setQuiet(swOverlay, false);
                pendingOverlay = true;
                Toast.makeText(this, "打开「显示在其他应用的上层」后返回即可", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            askNotifications();
            OverlayService.start(this);
        });
        swRender.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            local.edit().putBoolean("render", on).apply();
            Overlay.reconfigure();
        });
        swPass.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            local.edit().putBoolean("pass", on).apply();
            Overlay.reconfigure();
        });
        swSf.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            new Thread(() -> {
                boolean ok = Root.sfIndicator(on);
                runOnUiThread(() -> {
                    if (ok) {
                        local.edit().putBoolean("sf", on).apply();
                    } else {
                        setQuiet(swSf, !on);
                        Toast.makeText(this, "没有拿到 root 权限，请在 SukiSU 里授权", Toast.LENGTH_LONG).show();
                    }
                });
            }).start();
        });
    }

    private void syncLocal() {
        SharedPreferences local = App.local(this);
        binding = true;
        swOverlay.setChecked(Overlay.showing());
        swRender.setChecked(local.getBoolean("render", true));
        swPass.setChecked(local.getBoolean("pass", false));
        swSf.setChecked(local.getBoolean("sf", false));
        binding = false;
    }

    private void askNotifications() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    // ---------------- 模块状态 ----------------

    private SharedPreferences prefsOrWarn() {
        SharedPreferences p = App.prefs;
        if (p == null) {
            Toast.makeText(this, "模块还没激活，改动不会生效", Toast.LENGTH_SHORT).show();
            refreshState();
        }
        return p;
    }

    private void refreshState() {
        SharedPreferences p = App.prefs;
        boolean active = p != null;
        binding = true;

        statusTitle.setText(active ? "模块已激活" : "模块未激活");
        statusSub.setText(active
                ? App.framework() + "，作用于系统框架"
                : "在 LSPosed 里启用本模块，勾选「系统框架」，然后重启手机");
        statusIcon.setImageResource(active ? R.drawable.ic_ok : R.drawable.ic_warn);
        int bg = MaterialColors.getColor(statusCard, active
                ? com.google.android.material.R.attr.colorPrimaryContainer
                : com.google.android.material.R.attr.colorErrorContainer);
        int fg = MaterialColors.getColor(statusCard, active
                ? com.google.android.material.R.attr.colorOnPrimaryContainer
                : com.google.android.material.R.attr.colorOnErrorContainer);
        statusCard.setCardBackgroundColor(bg);
        statusTitle.setTextColor(fg);
        statusSub.setTextColor(fg);
        statusIcon.setColorFilter(fg);

        setEnabledDeep(ctlBody, active);
        if (active) {
            swEnable.setChecked(p.getBoolean(Cfg.ENABLED, false));
            checkTarget(p.getInt(Cfg.TARGET, defaultTarget()));
            modeGroup.check(p.getInt(Cfg.MODE, Cfg.MODE_LOCK) == Cfg.MODE_PEAK ? R.id.btnPeak : R.id.btnLock);
        } else {
            checkTarget(defaultTarget());
            modeGroup.check(R.id.btnLock);
        }
        binding = false;
        updateHints();
    }

    private void updateHints() {
        int t = selectedTarget();
        boolean lock = modeGroup.getCheckedButtonId() != R.id.btnPeak;
        modeHint.setText(lock
                ? "始终保持 " + t + " Hz。省电、温控、视频降帧都不再生效，更耗电。"
                : "最高可到 " + t + " Hz。画面静止或应用请求低帧率时，系统仍会自动降下来。");
        SharedPreferences p = App.prefs;
        boolean on = p != null && p.getBoolean(Cfg.ENABLED, false);
        scale.setTarget(on ? t : -1);
    }

    private void setQuiet(CompoundButton b, boolean v) {
        binding = true;
        b.setChecked(v);
        binding = false;
    }

    private static void setEnabledDeep(View v, boolean en) {
        v.setEnabled(en);
        v.setAlpha(en ? 1f : 0.5f);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                c.setEnabled(en);
                if (c instanceof ViewGroup) setEnabledDeep2((ViewGroup) c, en);
            }
        }
    }

    private static void setEnabledDeep2(ViewGroup g, boolean en) {
        g.setEnabled(en);
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            c.setEnabled(en);
            if (c instanceof ViewGroup) setEnabledDeep2((ViewGroup) c, en);
        }
    }
}
