/*
 * 机器眼 · 符号算子：解析 uiautomator dump XML → 控件 bounds / 文本集 / 输入框位置。
 *
 * 用途：
 * - 输入框位置（IME 挤压）：EditText bounds 随档位变化。
 * - 终端 View bounds：判断 IME 挤压后的内容区。
 * - 会话列表 / 控件文本集：列表正确性、陈旧。
 * - 兜底槽字符（D-35）：fallbackChars（'?' 或形近等价）——从文本集找非正常字符。
 *
 * 判不出（XML 解析失败 / 找不到目标控件）→ INDETERMINATE，不猜。
 */

'use strict';

const fs = require('fs');
const { INDETERMINATE } = require('./video');

/** 解析 bounds="[l,t][r,b]" → {left,top,right,bottom}。 */
function parseBounds(s) {
  const m = s.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
  if (!m) return null;
  return { left: Number(m[1]), top: Number(m[2]), right: Number(m[3]), bottom: Number(m[4]) };
}

/** 递归遍历 XML 节点树，收集所有 <node>。用正则按 <node 切分（uiautomator dump 是单行 XML）。 */
function collectNodes(xml) {
  const nodes = [];
  const re = /<node\b[^>]*>/g;
  let m;
  while ((m = re.exec(xml)) !== null) {
    const tag = m[0];
    const attr = (name) => {
      const am = tag.match(new RegExp(name + '="([^"]*)"'));
      return am ? am[1] : '';
    };
    const bounds = parseBounds(attr('bounds'));
    nodes.push({
      text: attr('text'),
      resourceId: attr('resource-id'),
      className: attr('class'),
      contentDesc: attr('content-desc'),
      bounds,
      clickable: attr('clickable') === 'true',
      focused: attr('focused') === 'true',
    });
  }
  return nodes;
}

/**
 * 分析 uiautomator dump XML。
 *
 * @param {string} xmlPath  UI dump XML 路径
 * @param {object} opts { roles?: {terminalView?: string, inputField?: string} }
 *   roles 指定「终端 View」和「输入框」的 class 匹配（默认终端 = View 中最大 bounds、
 *   输入框 = EditText）。
 * @returns {status:'OK', ...} | INDETERMINATE
 */
function analyzeDump(xmlPath, opts = {}) {
  let xml;
  try {
    xml = fs.readFileSync(xmlPath, 'utf8');
  } catch (e) {
    return INDETERMINATE(`dump 不可读: ${e.message}`);
  }
  const nodes = collectNodes(xml);
  if (nodes.length === 0) return INDETERMINATE('XML 中未解析到任何 node');

  // 有文本的节点 → textSet。
  const textSet = [...new Set(nodes.map((n) => n.text).filter((t) => t && t.length > 0))];

  // 终端 View：排除全屏外层（top=0 且左右满宽的 FrameLayout/LinearLayout），选「内容区 View」。
  // 实证（ime REPORT.md）：终端 View bounds = [0,254][1080,1896]（聚焦），即 top=254（状态栏下沿）、
  // 非全屏。外层 FrameLayout top=0 会污染选择。选法：class=android.view.View 且 top>0 且面积最大的。
  const viewNodes = nodes.filter(
    (n) => n.className === 'android.view.View' && n.bounds && n.bounds.top > 0,
  );
  const terminalView = viewNodes.sort((a, b) => {
    const areaA = (a.bounds.right - a.bounds.left) * (a.bounds.bottom - a.bounds.top);
    const areaB = (b.bounds.right - b.bounds.left) * (b.bounds.bottom - b.bounds.top);
    return areaB - areaA;
  })[0];

  // 输入框：EditText（focused 优先）。
  const inputField = nodes.find((n) => n.className === 'android.widget.EditText') || null;

  // 兜底槽字符：textSet 里含 '?' 或疑似缺字占位（D-35 兜底是 '?'）。
  const fallbackChars = [...new Set(
    textSet.filter((t) => t.includes('?')),
  )];

  // 输入框 bounds（有则返回）。
  const inputBounds = inputField ? inputField.bounds : null;
  const terminalBounds = terminalView ? terminalView.bounds : null;

  if (!terminalBounds && !inputBounds) {
    return INDETERMINATE('未找到终端 View 或输入框 bounds');
  }

  return {
    status: 'OK',
    controls: nodes
      .filter((n) => n.text || n.contentDesc)
      .map((n) => ({
        text: n.text || n.contentDesc,
        className: n.className,
        bounds: n.bounds,
      })),
    textSet,
    fallbackChars,
    boundsByRole: {
      terminalView: terminalBounds,
      inputField: inputBounds,
    },
    focusedField: inputField?.focused ?? false,
  };
}

module.exports = { analyzeDump, parseBounds, collectNodes };
