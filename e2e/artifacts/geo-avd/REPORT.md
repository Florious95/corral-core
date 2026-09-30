# 几何等价 AVD 标定 + 最右列截断复现报告

- 日期：2026-08-13
- 新建 AVD：`agentmirror_geo_1260x2800`（`emulator-5558`），**长期保留，供以后所有几何相关缺陷复用**
- 分辨率：1260×2800，密度：**480dpi（第一次试就命中，未需换密度桶）**
- APK 构建自 HEAD `c84b73950371a2bc8c5bf0029ebbfcf7fbc6a217`（与本轮其余测试同 sha）

## 密度标定

方法：进会话填满内容后，量终端 View 内容区高度 ÷ 行数 = 行高，与用户真机量到的 **20px** 比对。

- 内容区 bounds：`[0,216][1260,2356]`，高度 2140px
- 服务端 pane：126×107（107 行）
- 行高 = 2140 ÷ 107 = **20.0px**，与用户真机行高**精确相等**
- 交叉验证：`tmux capture-pane -p` 实际输出 107 行，与 pane_height 一致；
  machine_eye `bottomMarginPx=5`（健康值）

**480dpi 一次标定成功，几何等价确认。** 证据：`03-pane-480dpi.txt`、`03-app-480dpi.png`、
`03-capture-480dpi.txt`。

## 最右列截断复现测试

在已标定的 AVD 上，向隔离 tmux pane（126 列）直接写入两组精确到列宽边界的内容
（bash 直写，非经 Claude Code，便于精确控制字节数）：

| 测试内容 | 列数 | 视觉右缘标记 | machine_eye rightMarginPx |
|---|---|---|---|
| 119×`A` + `<END>`（5 字符） | 124 | `<END>` 完整可见 | — |
| 126×`B`（纯 ASCII，正好等于 pane 列数） | 126 | 铺满到边缘 | **1px** |
| 62×`中` + `X`（半宽标记） | 125 | `X` 完整可见，未被压扁/截断 | — |
| 63×`中`（纯 CJK，正好等于 pane 列数） | 126 | 铺满到边缘 | **1px** |

**四组内容（ASCII 与 CJK 各两档，均含精确到边界列的用例）均未观察到右列截断、
字形被压扁、或提前/推迟换行。** `rightMarginPx` 稳定在 1px（视口 1260px 中内容画到 1258/1259px，
基本贴到真实右边界），与 w-dev-cols 扫真实用户截图「从未出现截断，均留 100px+ 空白」的
结论方向一致——但这次是**主动把内容撑到刚好等于服务端上报列数**的强制边界测试，
仍然没有出现截断，说明渲染管线在"内容恰好用满全部列宽"时依然能正确画到真实边缘，
未见到 off-by-one 或半宽字形错位的证据。

**结论：在几何标定后的设备上，未复现"最右列截断"。**

如实报告：这不排除该缺陷需要更特殊的触发条件（例如结合 resize/捏合/特定字体回退路径），
但就"内容单纯填满到列宽边界"这一最直接的复现路径，未见异常。

证据：`05-app-width-test.png`、`06-app-cjk-width-test.png` + 对应 machine_eye JSON 输出（内联于本报告，
未额外落盘 JSON 文件——脚本命令附后供复算）。

```
node -e "
const {analyzeFrame} = require('./framework/machine_eye/space.js');
console.log(analyzeFrame('e2e/artifacts/geo-avd/05-app-width-test.png'));
console.log(analyzeFrame('e2e/artifacts/geo-avd/06-app-cjk-width-test.png'));
"
```

## 纪律核对

- 隔离 tmux + 隔离 daemon（`AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS` 收窄扫描），App 正常配对，
  未手改 SharedPreferences。
- 产品代码零改动；未切分支、未 commit、未 push。
- token 未出现在任何输出/截图/本报告。
- **AVD `agentmirror_geo_1260x2800` 按 leader 指示长期保留，未删除**；隔离 daemon/tmux/App 进程已清理。
