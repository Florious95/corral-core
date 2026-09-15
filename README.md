# corral-core · maven

三核（`core-protocol` / `core-terminal` / `core-conn`）的发布产物，标准 maven 布局。
⛔ 这是产物分支，**不放源码**；源码在 `main`。

用法（Gradle）：

```kotlin
repositories {
    maven { url = uri("https://raw.githubusercontent.com/Florious95/corral-core/maven/") }
}
dependencies {
    implementation("dev.agentmirror.core:core-protocol:20260915.close")
    implementation("dev.agentmirror.core:core-terminal:20260915.close")
    implementation("dev.agentmirror.core:core-conn:20260915.close")
}
```

版本 `20260915.close` 包含 `close_session` / `close_session_result` 协议帧；对应本任务的 corral-core 源码候选。
