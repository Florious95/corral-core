# 知识基底 · naming（系统编译产物）

## 0. 任务（taskbook.yaml#naming）
- 目标：产品定名 + 仓库元信息统一。两步：
  1. **候选与查重**：拟 3-5 个候选名（气质：开源 CLI 工具、tmux 镜像/舰队望远镜意象、可作安卓 App 名与 Go module 名；避开 herdr/moshi/kittylitter 近似），逐一查重（GitHub 同名仓库/组织、常见包名冲突、商标常识面），**send 一页纸 shortlist（名字+含义+查重结果+你的推荐）给 leader 定夺**，等裁定再动手。
  2. **应用（leader 定名后）**：Go module 路径统一改名（go.mod + 全部 import + 重跑 go build ./... && go test ./...）；Android applicationId 与 app 名字符串资源；根 README.md（产品定位一段+架构总览图引用 docs/wiki+快速开始：服务端一条命令起+APP 扫码连）；服务端二进制名（cmd 目录名与 README 同步）。
- 验收（exit 0 = 过）：`bash -lc 'test -s README.md && cd server && go build ./...'`
- 写范围：`README.md`、`server/`（仅模块名/导入路径/目录名重命名）、`app/`（仅 applicationId 与名称资源）。红线：不改任何逻辑代码；改名后全量门必须仍绿（交件前跑 `bash tools/gate/run.sh` 自查）。

## 1. 现场基
- 暂名 `agentmirror`（module github.com/remote-agent/agentmirror、applicationId dev.agentmirror.app、二进制 agentmirrord）——若 leader 裁定沿用 agentmirror 也算定名，第二步只需补 README 与 GitHub org 名确认。
- **并行纪律**：pairing-ui 席位并行施工 :app 源码——你只动 applicationId/名称资源单点；server 侧改名是大面积 import 重写，动手前 git status 确认无他人在途 server 改动（当前无）。
- 查重手段：curl -s -o /dev/null -w '%{http_code}' https://github.com/<name> 与 https://github.com/<name>/<name>（404=可用）；crates/npm/pypi 同名可顺手看，非硬约束。

## 2. 需求基（指针）
1. requirement-base/entries/001-产品命题-tmux镜像范式.md（产品气质来源）
2. requirement-base/entries/008-生产级定位与开源许可.md（Apache-2.0 开源发布语境）

## 3. 经验基
- shortlist 是裁定件：不发 shortlist 直接定名施工=违纪。
- 改名用工具化手段（gofmt/goimports/sed 全仓一致替换 + 编译验证），禁止手工逐文件。
- 注释红线、净化前缀照旧。

## 4. 沉淀区（唯一允许你追加写入的区域）
