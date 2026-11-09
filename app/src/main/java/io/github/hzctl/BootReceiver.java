package io.github.hzctl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后恢复悬浮窗（如果关机前开着） */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        if (App.local(c).getBoolean("overlay", false)) OverlayService.start(c);
        if (App.local(c).getBoolean("sf", false)) new Thread(() -> Root.sfIndicator(true)).start();
    }
}
