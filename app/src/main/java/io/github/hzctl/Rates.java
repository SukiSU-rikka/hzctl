package io.github.hzctl;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class Rates {
    private Rates() {}

    static final int UNLOCK = 165;

    /** 刷新率分档配色：越高越偏青绿，60 以下偏红 */
    static int color(float hz) {
        if (hz >= 160) return 0xFF22C7A9;
        if (hz >= 140) return 0xFF2BB4E8;
        if (hz >= 110) return 0xFF6C8CFF;
        if (hz >= 85) return 0xFFF2A93B;
        return 0xFFEF5B5B;
    }

    static Display display(Context c) {
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        return dm.getDisplay(Display.DEFAULT_DISPLAY);
    }

    /** 当前分辨率下系统公开的刷新率档位（去重、升序） */
    static List<Integer> listed(Display d) {
        List<Integer> out = new ArrayList<>();
        Display.Mode cur = d.getMode();
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight())
                continue;
            int r = Math.round(m.getRefreshRate());
            if (!out.contains(r)) out.add(r);
        }
        Collections.sort(out);
        return out;
    }
}
