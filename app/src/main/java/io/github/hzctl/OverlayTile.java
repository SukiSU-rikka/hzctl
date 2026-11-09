package io.github.hzctl;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.view.Display;

/** 控制中心：开关刷新率悬浮窗，副标题显示当前刷新率 */
public class OverlayTile extends TileService {
    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        if (!Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
            return;
        }
        if (Overlay.showing()) OverlayService.stop(this);
        else OverlayService.start(this);
        update();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(Overlay.showing() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        Display d = Rates.display(this);
        if (d != null) t.setSubtitle(Math.round(d.getMode().getRefreshRate()) + " Hz");
        t.updateTile();
    }
}
