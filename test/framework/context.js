// context.js — 用例与 runner 之间的共享环境句柄。
//
// 隔离依赖环：cases/*.test.js 需要知道 runner 起好的隔离环境（端口/token/tmux），
// 但 runner 又要 require 用例文件。若用例直接 require('../runner') 会形成
// 循环依赖，且 runner 模块在 cases 加载时尚未定义 shared。独立 context 模块
// 打破该环：runner 在 beforeAll 写入，用例在 fn() 运行时读取。
//
// 注意：用例 fn 的 ctx 参数也已提供这些句柄（ctx.daemon/ctx.tmuxInfo/...），
// 但用 examples 演示两种取法；新用例优先用 ctx，减少对模块级状态的依赖。

const state = {
  port: null,
  token: null,
  workDir: null,
  tmuxInfo: null,
  daemon: null,
};

function setEnvironment(env) {
  Object.assign(state, env);
}

function getEnvironment() {
  return state;
}

module.exports = { setEnvironment, getEnvironment };
