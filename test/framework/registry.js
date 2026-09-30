// registry.js — 用例注册与选择性执行（FIELD.md 能力 4）。
//
// 用例 = { name, tags:[], fn }。fn 是异步函数，接收一个 context 对象
// { ctx, fixtures, step } —— fixtures 为共享环境（daemon/tmux），step(name, fn)
// 记录步骤日志并执行。注册后按标签选择：CLI 传 --tag a,b 则只跑含任一标签的用例。
//
// 设计意图：用例文件（cases/*.test.js）只做 define()，不自己跑；
// runner 负责收集、过滤、执行、报告。这样选择性执行与结果持久化与用例解耦。

class CaseRegistry {
  constructor() {
    this.cases = [];
  }

  // define 注册一个用例。
  // @contract
  // @pre name 非空；fn 为 async 函数
  // @post 追加到 this.cases
  // @err name 重复抛 Error（防误注册）
  define({ name, tags = [], fn, description = '', localOnly = false }) {
    if (!name) throw new Error('case requires a name');
    if (this.cases.some((c) => c.name === name)) throw new Error(`duplicate case name: ${name}`);
    // localOnly：纯本地文件分析，不需要 daemon/tmux 隔离环境（机器眼算子考卷用）。
    // runner 据此跳过环境 setup/teardown。
    this.cases.push({ name, tags, fn, description, localOnly });
    return this;
  }

  // select 按标签过滤。无标签参数 → 全部。标签用逗号分隔。
  // 语义：命中任一标签即选中（OR）。
  select(tagSpec) {
    if (!tagSpec) return [...this.cases];
    const tags = tagSpec.split(',').map((t) => t.trim()).filter(Boolean);
    if (tags.length === 0) return [...this.cases];
    return this.cases.filter((c) => c.tags.some((t) => tags.includes(t)));
  }

  list() {
    return this.cases.map(({ name, tags, description }) => ({ name, tags, description }));
  }
}

// globalRegistry 为整个测试工程共享的注册表（cases/ 与 runner 都引用它）。
const globalRegistry = new CaseRegistry();

module.exports = { CaseRegistry, globalRegistry };
