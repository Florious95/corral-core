# corral-core · maven

三核（`core-protocol` / `core-terminal` / `core-conn`）的发布产物，标准 maven 布局。
⛔ 这是产物分支，**不放源码**；源码在 `main`。

用法（Gradle）：

```kotlin
repositories {
    maven { url = uri("https://raw.githubusercontent.com/Florious95/corral-core/maven/") }
}
dependencies {
    implementation("dev.agentmirror.core:core-protocol:20260822.0")
    implementation("dev.agentmirror.core:core-terminal:20260822.0")
    implementation("dev.agentmirror.core:core-conn:20260822.0")
}
```

版本 `20260822.0` 对应 tag `baseline-20260822-release`（真机金标准已过的稳定基线）。
