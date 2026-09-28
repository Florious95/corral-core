# v5 输入框闪烁回归根因

## 单一根因

v5 的根因是 D-31 把设备字号恢复放进 `TermSurfaceView.presenter` setter：会话层复用的 presenter 仍保存旧 View 的 viewport 时，新 View 尚未布局，setter 就同步调用 `onFontSizeChanged`，用“旧 viewport + 新 cell size”重算行列并沿 `termview -> session` 反向边发出错误 resize；IME 导致 View/viewport 变化和终端增行重绘会放大这条无 View 几何依据的 resize/redraw 往返，表现为疯狂闪烁。

## 代码行级证据

以下行号均取冻结的 `v5-failed` commit `2874c54`：

- `app/app/src/main/java/dev/agentmirror/app/session/SessionScreen.kt:200-203`：session 层在 AndroidView factory 中创建尚未布局的 `TermSurfaceView`，随即绑定长期持有的 `viewModel.presenter`。
- `app/app/src/main/java/dev/agentmirror/app/termview/TermSurfaceView.kt:62-71`：v5 setter 在绑定时读取 CellSizeStore，并同步调用 `value.onFontSizeChanged(...)`；此时新 View 尚未提供自身 viewport。
- `app/app/src/main/java/dev/agentmirror/app/termview/TermViewPresenter.kt:157-160,173-187`：presenter 持久保存上个 View 写入的 viewport；字号变化会立即用这个 viewport 重算 rows/cols，并调用 `onResizeRequest`。
- `app/app/src/main/java/dev/agentmirror/app/session/SessionViewModel.kt:62-66`：该回调不是 View 内部动作，而是 session 层网络 resize；发送成功还同步 resize emulator，形成跨层 resize/redraw 往返。
- v2 的 `TermSurfaceView.kt:54-61` setter 只装帧回调并 postFrame，不加载字号、不调用 `onFontSizeChanged`，所以绑定没有 session resize 副作用。

D-28 只改变 Canvas 裁剪，未进入 session 反向边；D-38 的 visibility hook 不是本探针命中的绑定期错误 resize。探针直接观察 D-31 唯一新增的跨边副作用，因此结论不依赖这两个伴随改动。

## 根因探针

文件：`app/app/src/test/kotlin/dev/agentmirror/app/termview/TermSurfaceSessionBindingRegressionTest.kt`

探针先给 presenter 写入旧 viewport `800×480`，再保存字号 `14×28`，随后模拟 SessionScreen 在新 View 尚未布局时绑定该 presenter。守门契约是：绑定本身不得向 session 发 resize。

v5 与 v2 的测试文件 SHA-1 均为 `dd94c202de72d7ebe044eb82b51901bc606087ea`，不是两份不同探针。

## v5-failed 实跑原始输出（命中，失败）

命令：
```text
cd /tmp/v5-tree/app
bash -lc 'env -u TEAM_AGENT_* ./gradlew :app:testDebugUnitTest --tests dev.agentmirror.app.termview.TermSurfaceSessionBindingRegressionTest 2>&1 | tee /tmp/rootcause-flicker-v5-v5-probe.log; exit ${PIPESTATUS[0]}'
```

退出码：`1`

```text
> Task :terminal:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :terminal:processResources NO-SOURCE
> Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:processDebugNavigationResources
> Task :app:checkDebugAarMetadata
> Task :app:compileDebugNavigationResources
> Task :app:generateDebugResValues
> Task :app:mapDebugSourceSetPaths
> Task :app:generateDebugResources
> Task :terminal:compileKotlin
> Task :terminal:compileJava NO-SOURCE
> Task :terminal:classes UP-TO-DATE
> Task :terminal:jar
> Task :app:packageDebugResources
> Task :app:createDebugCompatibleScreenManifests
> Task :app:extractDeepLinksDebug
> Task :app:parseDebugLocalResources
> Task :app:mergeDebugResources
> Task :app:processDebugMainManifest
> Task :app:processDebugManifest
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebug
> Task :app:javaPreCompileDebugUnitTest
> Task :app:mergeDebugShaders
> Task :app:compileDebugShaders NO-SOURCE
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:mergeDebugAssets
> Task :app:processDebugUnitTestManifest
> Task :app:preReleaseBuild UP-TO-DATE
> Task :app:createReleaseCompatibleScreenManifests
> Task :app:generateReleaseResValues
> Task :app:extractDeepLinksRelease
> Task :app:processReleaseMainManifest
> Task :app:processReleaseManifest
> Task :app:processDebugManifestForPackage
> Task :app:processDebugResources
> Task :app:packageDebugUnitTestForUnitTest
> Task :app:generateDebugUnitTestConfig

> Task :app:compileDebugKotlin
w: file:///private/tmp/v5-tree/app/app/src/main/java/dev/agentmirror/app/MainActivity.kt:61:60 'static field SOFT_INPUT_ADJUST_RESIZE: Int' is deprecated. Deprecated in Java.
w: file:///private/tmp/v5-tree/app/app/src/main/java/dev/agentmirror/app/conn/Frames.kt:65:25 This declaration needs opt-in. Its usage should be marked with '@kotlinx.serialization.ExperimentalSerializationApi' or '@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)'
w: file:///private/tmp/v5-tree/app/app/src/main/java/dev/agentmirror/app/conn/Frames.kt:69:48 This declaration needs opt-in. Its usage should be marked with '@kotlinx.serialization.ExperimentalSerializationApi' or '@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)'
w: file:///private/tmp/v5-tree/app/app/src/main/java/dev/agentmirror/app/conn/Frames.kt:69:50 This declaration needs opt-in. Its usage should be marked with '@kotlinx.serialization.ExperimentalSerializationApi' or '@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)'
w: file:///private/tmp/v5-tree/app/app/src/main/java/dev/agentmirror/app/pairing/PairingScreen.kt:459:22 'fun Modifier.menuAnchor(): Modifier' is deprecated. Use overload that takes ExposedDropdownMenuAnchorType and enabled parameters.

> Task :app:compileDebugJavaWithJavac NO-SOURCE
> Task :app:processDebugJavaRes
> Task :app:bundleDebugClassesToCompileJar
> Task :app:bundleDebugClassesToRuntimeJar

> Task :app:compileDebugUnitTestKotlin
w: file:///private/tmp/v5-tree/app/app/src/test/java/dev/agentmirror/app/PairingUxTest.kt:44:19 'fun createComposeRule(effectContext: CoroutineContext = ...): ComposeContentTestRule' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.
w: file:///private/tmp/v5-tree/app/app/src/test/java/dev/agentmirror/app/pairing/CameraPermissionCardTest.kt:38:19 'fun createComposeRule(effectContext: CoroutineContext = ...): ComposeContentTestRule' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.
w: file:///private/tmp/v5-tree/app/app/src/test/java/dev/agentmirror/app/pairing/PairingScreenClockPumpTest.kt:71:19 'fun createComposeRule(effectContext: CoroutineContext = ...): ComposeContentTestRule' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.
w: file:///private/tmp/v5-tree/app/app/src/test/java/dev/agentmirror/app/service/OnScreenFallbackPumpTest.kt:64:19 'fun createComposeRule(effectContext: CoroutineContext = ...): ComposeContentTestRule' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.
w: file:///private/tmp/v5-tree/app/app/src/test/java/dev/agentmirror/app/session/AttachmentButtonTest.kt:39:19 'fun createComposeRule(effectContext: CoroutineContext = ...): ComposeContentTestRule' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.
w: file:///private/tmp/v5-tree/app/app/src/test/java/dev/agentmirror/app/session/KeyBarFitTest.kt:51:19 'fun createComposeRule(effectContext: CoroutineContext = ...): ComposeContentTestRule' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.
w: file:///private/tmp/v5-tree/app/app/src/test/kotlin/dev/agentmirror/app/workspace/StateBadgeTest.kt:55:19 'fun createComposeRule(effectContext: CoroutineContext = ...): ComposeContentTestRule' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.
w: file:///private/tmp/v5-tree/app/app/src/test/kotlin/dev/agentmirror/app/workspace/WorkspaceWiringTest.kt:55:23 'fun createComposeRule(effectContext: CoroutineContext = ...): ComposeContentTestRule' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes

> Task :app:testDebugUnitTest

TermSurfaceSessionBindingRegressionTest > bindingPresenterDoesNotResizeSessionFromItsStaleViewport FAILED
    java.lang.AssertionError at TermSurfaceSessionBindingRegressionTest.kt:54

1 test completed, 1 failed

> Task :app:testDebugUnitTest FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file:///private/tmp/v5-tree/app/app/build/reports/tests/testDebugUnitTest/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 8s
36 actionable tasks: 36 executed
```

失败 XML 的完整断言消息：

```text
java.lang.AssertionError: binding emitted session resize(s): [(17, 57)]
```

`17 = 480 / 28`，`57 = 800 / 14`，逐值证明 setter 使用的是旧 viewport 与新字号。

## v2-baseline 实跑原始输出（不命中，通过）

命令：
```text
cd app
bash -lc 'env -u TEAM_AGENT_* ./gradlew :app:testDebugUnitTest --tests dev.agentmirror.app.termview.TermSurfaceSessionBindingRegressionTest 2>&1 | tee /tmp/rootcause-flicker-v5-v2-probe.log; exit ${PIPESTATUS[0]}'
```

退出码：`0`

```text
> Task :terminal:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :terminal:compileKotlin UP-TO-DATE
> Task :terminal:compileJava NO-SOURCE
> Task :terminal:processResources NO-SOURCE
> Task :terminal:classes UP-TO-DATE
> Task :terminal:jar UP-TO-DATE
> Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:processDebugNavigationResources UP-TO-DATE
> Task :app:compileDebugNavigationResources UP-TO-DATE
> Task :app:generateDebugResValues UP-TO-DATE
> Task :app:mapDebugSourceSetPaths UP-TO-DATE
> Task :app:generateDebugResources UP-TO-DATE
> Task :app:mergeDebugResources UP-TO-DATE
> Task :app:packageDebugResources UP-TO-DATE
> Task :app:parseDebugLocalResources UP-TO-DATE
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:processDebugMainManifest UP-TO-DATE
> Task :app:processDebugManifest UP-TO-DATE
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:compileDebugJavaWithJavac NO-SOURCE
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest UP-TO-DATE
> Task :app:mergeDebugShaders UP-TO-DATE
> Task :app:compileDebugShaders NO-SOURCE
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:mergeDebugAssets UP-TO-DATE
> Task :app:packageDebugUnitTestForUnitTest UP-TO-DATE
> Task :app:processDebugUnitTestManifest UP-TO-DATE
> Task :app:generateDebugUnitTestConfig UP-TO-DATE
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:preReleaseBuild UP-TO-DATE
> Task :app:createReleaseCompatibleScreenManifests UP-TO-DATE
> Task :app:generateReleaseResValues UP-TO-DATE
> Task :app:extractDeepLinksRelease UP-TO-DATE
> Task :app:processReleaseMainManifest UP-TO-DATE
> Task :app:processReleaseManifest UP-TO-DATE
> Task :app:bundleDebugClassesToCompileJar
> Task :app:bundleDebugClassesToRuntimeJar

> Task :app:compileDebugUnitTestKotlin
w: file:///Volumes/nvme/Projects/%E8%BF%9C%E7%A8%8BAgent%E5%AE%89%E5%8D%93/app/app/src/test/java/dev/agentmirror/app/PairingUxTest.kt:44:19 'fun createComposeRule(effectContext: CoroutineContext = ...): ComposeContentTestRule' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.
w: file:///Volumes/nvme/Projects/%E8%BF%9C%E7%A8%8BAgent%E5%AE%89%E5%8D%93/app/app/src/test/kotlin/dev/agentmirror/app/workspace/WorkspaceWiringTest.kt:55:23 'fun createComposeRule(effectContext: CoroutineContext = ...): ComposeContentTestRule' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
36 actionable tasks: 4 executed, 32 up-to-date
```

