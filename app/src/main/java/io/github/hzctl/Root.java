package io.github.hzctl;

import java.io.BufferedReader;
import java.io.InputStreamReader;

final class Root {
    private Root() {}

    /** 以 root 跑一条命令；拿不到 root 或失败返回 null。别在主线程调用 */
    static String run(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return p.waitFor() == 0 ? sb.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** SurfaceFlinger 自带的刷新率指示器（开发者选项「显示刷新率」同款） */
    static boolean sfIndicator(boolean on) {
        return run("service call SurfaceFlinger 1034 i32 " + (on ? 1 : 0)) != null;
    }
}
