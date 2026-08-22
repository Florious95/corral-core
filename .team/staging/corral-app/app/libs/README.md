# libs/ — 预构建产物（勿手改）

- `tsnetbind.aar`：**构建产物**，由 `tools/tsnetbind/` 重建，禁止手工修改。
  内容：gomobile 绑定的 tsnet 用户态节点（Go → arm64-v8a native + Java 绑定类），
  入库是为了其他席位/开发者**无需 Go 工具链**即可编译 `:app`（leader 追认 2026-08-09）。
- 重建命令：`bash tools/tsnetbind/build.sh`（前置：Go 1.26+、Android SDK+NDK）。
- 当前版本：tailscale.com v1.102.2，arm64-v8a，stripped，8.0MB。
- **16KB 对齐（fix-tsnetbind-align，2026-08-11）**：Android 15+ 对未按 16KB 对齐的 native 库
  加载时强制拒绝，这是上架硬性要求。构建链已加 `-extldflags=-Wl,-z,max-page-size=16384`，
  实测 libgojni.so LOAD 段 Align 0x1000(4KB)→0x4000(16KB)。详见 tools/tsnetbind/README.md。
- 许可：tailscale/x-mobile 系 BSD-3-Clause，gVisor netstack Apache-2.0——均 Apache-2.0
  兼容；分发需附版权声明（NOTICE 汇总项）。详见 docs/decisions/app-tsnet.md。
