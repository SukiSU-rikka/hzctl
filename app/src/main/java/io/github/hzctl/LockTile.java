package io.github.hzctl;

import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 控制中心：一键开关「接管刷新率」 */
public class LockTile extends TileService {
    private final Runnable onSvc = this::update;

    @Override
    public void onStartListening() {
        App.addListener(onSvc);
        update();
    }

    @Override
    public void onStopListening() {
        App.removeListener(onSvc);
    }

    @Override
    public void onClick() {
        SharedPreferences p = App.prefs;
        if (p == null) return;
        p.edit().putBoolean(Cfg.ENABLED, !p.getBoolean(Cfg.ENABLED, false)).apply();
        update();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        SharedPreferences p = App.prefs;
        if (p == null) {
            t.setState(Tile.STATE_UNAVAILABLE);
            t.setSubtitle("模块未激活");
        } else {
            boolean on = p.getBoolean(Cfg.ENABLED, false);
            t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            int target = p.getInt(Cfg.TARGET, Rates.UNLOCK);
            String mode = p.getInt(Cfg.MODE, Cfg.MODE_LOCK) == Cfg.MODE_LOCK ? "锁定 " : "最高 ";
            t.setSubtitle(on ? mode + target + " Hz" : "跟随系统");
        }
        t.updateTile();
    }
}
