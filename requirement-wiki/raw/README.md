# raw/ —— 源材料层

把你想入库的任何原始材料丢进这里：

- 文章、论文（.md / .pdf）
- 网页剪藏（用浏览器扩展转 markdown）
- 自己的笔记、播客转写、聊天记录
- 子目录任意组织（`raw/papers/`、`raw/notes/`、`raw/web/` 都可以）

## 铁律

**这一层只读。** AI 永远不会修改 `raw/` 下的任何文件。要修订观点就到 `wiki/` 里写新版，并在 wiki 页面里反向引用回这里的源文件。

## 操作

把材料放好，到项目根跑：

```bash
claude
> ingest
```

`wiki-ingest` skill 会扫描 `raw/`、自动判定哪些是 New / Modified / Unchanged / Dangling、报计划等你确认、然后编入 `wiki/`。
