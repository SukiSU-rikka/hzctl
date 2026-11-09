# HzCtl 刷新率调节 — 构建说明

基线：AGP 8.13.0 + Gradle 8.13 + compileSdk 37（targetSdk 35），JDK 17，libxposed API/Service 102.0.0。

## GitHub Actions
推送后 `.github/workflows/build.yml` 自动编译，产物在 Actions 页面的 `HzCtl` artifact 里。
签名用仓库里固定的 `app/hzctl.jks`，以后更新可直接覆盖安装。

## 本地
1. `gradle wrapper --gradle-version 8.13`
2. `./gradlew :app:assembleRelease`
3. 产物：`app/build/outputs/apk/release/app-release.apk`

## 检查点
- `unzip -l app-release.apk | grep META-INF/xposed`：必须有 java_init.list、module.prop、scope.list
- APK 里不能有 `io/github/libxposed/api` 的类（compileOnly）；`io/github/libxposed/service` 的类应该在
- 若合并后的 Manifest 里没有 `io.github.libxposed.service.XposedProvider`，App 会一直显示「模块未激活」，
  按 libxposed/service README 手动在 Manifest 里声明
- 若 `Chain.getArg` / `proceed(Object[])` 的签名与 102.0.0 不符，按实际签名改 `HzModule.java`，逻辑不动

## 运行时排查
LSPosed 日志搜 `HzCtl`：
- `hooked 2 method(s)`：两道都挂上了（1 说明 SurfaceControl 那道没找到）
- `lock 165.0Hz -> mode #N ...`：已改写下发
- `no 165Hz mode, panel offers: ...`：屏幕驱动没报 165 的模式，把这行完整发回来
- `SF <- mode X phys[...] from <调用者>  => rewritten ...`：第二道拦截生效，<调用者> 就是往 SurfaceFlinger 下发策略的代码
- 打开后若 `dumpsys SurfaceFlinger` 的 displayManagerPolicy 仍是 [120,120]，且日志里没有新的调用者，说明 ColorOS 在 native 层改的
