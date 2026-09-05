# tools/tsnetbind — gomobile 绑定构建（可复现）

`agentmirror.dev/tsnetbind` 是 tsnet 用户态节点的 gomobile 绑定最小包装，产物为
`app/app/libs/tsnetbind.aar`（Go → `jni/arm64-v8a/libgojni.so` + Java 绑定类）。

`Node.PeerSnapshot(knownID, cursor)` 是主机发现的 typed seam：它读取完整
`Status.Peer`，先按 StableID 全表命中，再返回确定性的最多 256 行 literal IPv4
窗口和续扫 cursor。调用失败必须 fail-closed；客户端绝不退化为扫描 `100.64.0.0/10`。

## 重建（一条命令，可复现）

```sh
bash tools/tsnetbind/build.sh
```

前置：

- Go 1.26+（go.mod 声明 `go 1.26.5`）
- Android SDK + NDK（脚本自动取 SDK 下版本号最高的 NDK；r21 实测可用）
- `gomobile`/`gobind` 缺失时脚本自动 `go install` 安装

产物直接覆盖写入 `app/app/libs/tsnetbind.aar`。

### 模拟器调试（x86_64）

默认仅 arm64-v8a（004 体积优先，minSdk 26 时代实机几乎全 arm64）。
模拟器需 x86_64 时：

```sh
ABIS="android/arm64,android/amd64" bash tools/tsnetbind/build.sh
```

## 16KB 对齐（Android 15+ 上架硬性要求）

**为什么必须有**：Android 15（API 35）及以上对未按 16KB 对齐的 native 库在加载时**强制拒绝**
（`Aligned16KB` 是上架阻断项，不是 lint 洁癖）。ELF 的 LOAD 段 `p_align` 必须 ≥ 16384，
且 `(p_offset − p_vaddr) % 16384 == 0`，否则 `dlopen` 直接失败，App 在 Android 15+ 无法加载
tsnet 功能。

**怎么做的**：Go 的 c-shared 构建走**外部链接**（NDK clang 出最终 ELF），所以把页面大小参数
透传给外部链接器：

```sh
-ldflags "-s -w -extldflags=-Wl,-z,max-page-size=16384"
```

`build.sh` 里已固化该参数。实测对照（2026-08-11，`aarch64-linux-android-readelf -l`）：

| LOAD 段 | 修复前 | 修复后 |
|---------|--------|--------|
| #1 | `Align 0x1000`（4096 = 4KB） | `Align 0x4000`（16384 = 16KB） |
| #2 | `Align 0x1000`（4096 = 4KB），`(off−vaddr)%16K≠0` | `Align 0x4000`（16384 = 16KB），`(off−vaddr)%16K=0` |

**红线**：不得用 lint 豁免掩盖此问题（lint 豁免不会让 ELF 变成 16KB 对齐，只是骗过检查，
上架仍会被 Google Play 拒）。必须重建产物本身。

### 对齐自检（阳性对照）

重建后必须用客观工具确认，不是看 lint 不报：

```sh
# 解包 AAR
unzip -o -q app/app/libs/tsnetbind.aar 'jni/*'
# NDK 的 readelf（或 llvm-readelf）
readelf -l jni/arm64-v8a/libgojni.so   # LOAD 段 Align 必须为 0x4000
objdump -p jni/arm64-v8a/libgojni.so   # LOAD 行 align 2**14
```

两条 LOAD 段 `Align` 均为 `0x4000` 才算对齐。

## 单测

```sh
cd tools/tsnetbind && go test ./...
```

锁 `parseInterfaces` 的跨语言 wire 契约（与 Kotlin `TsnetInterfaceCodec` 同一格式）。
