package io.github.hzctl;

/** 模块（system_server）和界面共用的配置键，走 LSPosed Remote Preferences */
final class Cfg {
    static final String PREFS = "hzctl";
    static final String ENABLED = "enabled";   // boolean 是否接管
    static final String TARGET = "target";     // int 目标刷新率 Hz
    static final String MODE = "mode";         // int 见下
    static final int MODE_LOCK = 0;            // 锁定：最低 = 最高 = 目标
    static final int MODE_PEAK = 1;            // 上限：最高 = 目标，允许系统降频

    private Cfg() {}
}
