# corral-app

**下一代 UI 的安卓客户端。** 代码尚未开工。

## 先读这个

**[`docs/需求索引.md`](docs/需求索引.md)** —— App 侧全部相关需求的索引，
以及需求真相源在哪、怎么查。**开工前必读。**

主线是 **`029 左中右三页架构`**（左收藏 / 中会话 / 右设置）——
这条 2026-08-12 就裁定了，标着「只入库不施工」，一直没人做。**它就是本仓要建的骨架。**

## 需求真相源在哪

**不在本仓。** 在 [`corral-core`](https://github.com/Florious95/corral-core)：

| 位置 | 是什么 |
|---|---|
| `requirement-base/` | **原始文档真相源**，56 条编号条目，**只增不删**。入口 `requirement-base/INDEX.md` |
| `requirement-wiki/` | LLM Wiki 概念网络，活库可改可删。入口 `requirement-wiki/wiki/index.md` |
| `CLAUDE.md` | 工程红线与纪律 |
| `taskbook.yaml` + `.team/evidence/*.json` | 任务状态的权威：前者说「做了什么」，后者说「凭什么算做完」 |

**查需求的顺序是既定纪律：先撞库，再问用户。** 先翻 wiki，撞不到再翻 base，还没有才去问。

**不要把需求原文复制进本仓。** 复制出来的那份必然漂移，而真相源只应有一份。

## 三个仓库的关系

| 仓库 | 装什么 |
|---|---|
| [`corral-core`](https://github.com/Florious95/corral-core) | 当前 App 的全部代码 + 需求维基 + 任务书 + 证据链。**决定「对话体验」的那一层。** |
| [`corral-serve`](https://github.com/Florious95/corral-serve) | 服务端 daemon（Go），跑在宿主机上桥接 tmux |
| `corral-app`（本仓） | 新 UI 的壳 |

## 唯一的硬约束

**本仓不得修改 core 的代码。**

新 UI 引用 core 提供的能力，但**没有改它的权限**。撞到必须改 core 的情况时：
**停下开单，不要在壳里复制一份 core 的逻辑改改用。**

理由是这个项目付过学费的：外壳每改一轮，就有一次把已经修好的对话体验弄回归的风险
（v5 五个修复 QA 全 PASS 却引入输入框闪烁回归；v6 三条倒退被全量回退）。
把 core 放在另一个仓库、只按版本依赖，是为了让「改不了」成为物理事实而不是纪律。

**绕过这条约束的代价不是 core 被改坏，是 core 被架空** —— 逻辑在壳里被复制一份，
回归照样发生，而且查不出来。

### 边界怎么划

近似判据：**`@Composable` 是壳，其余是核。**

参考数据：当前 App 里 `termview/`（终端仿真与渲染，1532 行）**零个 `@Composable`** ——
「你看到的那个画面」天生就已经是 core 的形状。
反过来，`session/`、`pairing/`、`diag/` 三个包各有一两个 Screen/Route 骑在线上，
拆的时候是**切开**，不是搬。

## License

Apache-2.0
