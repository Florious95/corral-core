/*
 * 机器眼 Layer 1 · 巡检入口：加载所有场景，统一导出。
 */

'use strict';

// 加载场景定义（副作用：注册进 scenario.js 注册表）。
require('./scenarios/s1_open_send');

const scenario = require('./scenario');
const ratchet = require('./ratchet');

module.exports = { scenario, ratchet };
