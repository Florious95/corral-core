# corral-app

**下一代 UI 的安卓客户端。** 目前为空，等待开工。

## 它和另外两个仓库的关系

| 仓库 | 装什么 |
|---|---|
| [`corral-core`](https://github.com/Florious95/corral-core) | 当前 App 的全部代码 + 需求维基 + 任务书。**决定「对话体验」的那一层。** |
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

## 边界怎么划

近似判据：**`@Composable` 是壳，其余是核。**
参考数据：当前 App 里 `termview/`（终端仿真与渲染，1532 行）**零个 `@Composable`** ——
「你看到的那个画面」天生就已经是 core 的形状。

## License

Apache-2.0
