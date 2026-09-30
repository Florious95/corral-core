# release 包（本地 debug vs release 性能对比）

席位：`pb-impl`。工作树：`.worktrees/wt-pb-core2` HEAD=`ff603db8a`。未 commit / 未 push。

## 改了哪一行

`app/app/build.gradle.kts`：

- `android.signingConfigs.create("releasePerfCompare")`：`storeFile` = `~/.android/debug.keystore`，口令/别名为 SDK 公开固定值；keystore 不存在则 `require` 响亮失败。
- 注释：**仅供本地性能对比，⛔ 不可用于分发**。
- `buildTypes.release.signingConfig` 接到上述配置。
- `isMinifyEnabled` 仍为 `false`。未开混淆/资源裁剪，未动 PerfTrace / 产品逻辑。

## APK

路径：`.team/artifacts/apk-release/app-release.apk`  
md5：`0907d6881bb1e034ef33a49f89afaa44`  
字节：`35044459`

构建：`./gradlew :app:assembleRelease --offline` → `BUILD SUCCESSFUL in 1m 8s`  
日志：`.team/nodes/pb-core/tmp/assemble-release.log`

## 自证原始输出

### 1. apksigner

```
$ ~/Library/Android/sdk/build-tools/35.0.0/apksigner verify --print-certs .team/artifacts/apk-release/app-release.apk
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: ea427eb4e14f95654a66802b6558fbbf6f93f1ca69d8117795fb7cef376cb13b
Signer #1 certificate SHA-1 digest: 58b7db903f34417cbe019cd8835b597f6f8046c5
Signer #1 certificate MD5 digest: d9e5573ff2bd38667d1388a99dfd5a09
```

（DN 为 Android Debug，真签上了。）

### 2. dex strings

```
$ unzip -p <apk> 'classes*.dex' | strings | grep -c …
PerfTrace 17
addBinaryListener 1
debug.agentmirror.perftrace 1
```

仪表与白屏修复符号都在。

### 3. aapt badging

```
$ aapt dump badging <apk> | head -3
package: name='dev.agentmirror.app' versionCode='1' versionName='0.1.0' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'
sdkVersion:'26'
targetSdkVersion:'35'
```

`aapt dump badging | grep -i debug` → `(no application-debuggable line)`  
release **没有** `application-debuggable` 标记。

## 没做到的

- 未用独立 release 密钥（按派单用 debug keystore）。
- 未开 minify（按派单保持 false）。
- 未装到模拟器/真机、未做性能对比（本格只出包）。
- 未 git commit/push。
