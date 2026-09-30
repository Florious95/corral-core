#!/usr/bin/env bash
# 构建 tsnetbind.aar：gomobile 绑定 tsnet 用户态节点，输出到 app/app/libs/。
# 前置：Go 1.26+、Android SDK + NDK（r21 实测可用）。gomobile/gobind 缺失时自动安装。
# 实测基线（2026-08-09，M 系 mac）：arm64 stripped AAR 8.0MB，冷构建 2m35s / 热 11s。
# 详见 docs/decisions/app-tsnet.md。
set -euo pipefail
cd "$(dirname "$0")"

export PATH="$(go env GOPATH)/bin:$PATH"
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
export ANDROID_HOME
# 未显式指定 NDK 时取 SDK 下版本号最高的一个。
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"

command -v gomobile >/dev/null || go install golang.org/x/mobile/cmd/gomobile@latest
command -v gobind   >/dev/null || go install golang.org/x/mobile/cmd/gobind@latest

# 默认仅 arm64-v8a（004 体积优先，minSdk 26 时代实机几乎全 arm64）。
# 模拟器调试需 x86_64 时：ABIS="android/arm64,android/amd64" ./build.sh
ABIS="${ABIS:-android/arm64}"

# -ldflags "-s -w" 去符号表/调试信息：.so 30.1MB→21.9MB，AAR 14.2MB→8.0MB。
# 16KB 对齐（2026-08-11，fix-tsnetbind-align）：Android 15+（API 35）对未按 16KB
# 对齐的 native 库在加载时强制拒绝（linker 要求 LOAD 段 p_align>=0x4000），这是上架
# 硬性要求。Go 的 c-shared 外部链接经 NDK clang 出最终 ELF，故必须把 -z max-page-size
# 透传给外部链接器：-extldflags=-Wl,-z,max-page-size=16384。修复前后实测 LOAD 段
# Align 0x1000(4KB)→0x4000(16KB)（readelf -l 验证）。不能靠 lint 豁免——那是把上架
# 阻断项藏起来。
gomobile bind -target="$ABIS" -androidapi 26 \
  -ldflags "-s -w -extldflags=-Wl,-z,max-page-size=16384" \
  -o ../../app/app/libs/tsnetbind.aar .

ls -la ../../app/app/libs/tsnetbind.aar
