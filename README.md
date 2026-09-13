# corral-app

`corral-app` 是 corral 的唯一 Android App 日常开发入口，默认 `main` 包含已接受的完整 App UI、输入、恢复、branding 和资源。产品需求与共享核心实现仍以 [`corral-core`](https://github.com/Florious95/corral-core) 为准；服务端 daemon 在 [`corral-serve`](https://github.com/Florious95/corral-serve)。

## 从 main 构建

需要 JDK 17 和 Android SDK。源码入口为 `app/src/main/`，核心依赖由远端 Maven 发布物提供，不依赖本机 `.team` 目录：

```bash
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

`settings.gradle.kts` 将 Maven 源固定到发布提交 `7bdd02c1e1914a99df77ab8f1d8c2aab1652fbd5`；`app/build.gradle.kts` 固定三核版本 `20260913.52fc4bd`，对应接受的 corral-core commit `52fc4bd72e5d60fcf24cfa1ae43811ad23e4cfb7`。不要改回 `20260822.0`，也不要在本仓复制核心算法。

## 已接受跨仓基线

- core：`52fc4bd72e5d60fcf24cfa1ae43811ad23e4cfb7`
- serve：`ca2bef1f47049760fd0748802a42decb29690646`
- App 图标：`@mipmap/ic_launcher`、`@mipmap/ic_launcher_round`，实际 adaptive 资源在 `app/src/main/res/`；应用 label 为 `corral`。

需求索引见 [`docs/需求索引.md`](docs/需求索引.md)。

## License

Apache-2.0
