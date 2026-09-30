// scratch 探针模块：把归档的 agentstate 决策层（docs/archive/agentstate-round4/）原样
// 搬进来跑真实红测。决策逻辑字节不变，仅 import 路径指向本地 protocol 副本
// （stateoracleprobe/protocol，从 server/internal/protocol/state.go 原样拷贝，零改动）。
// 目的：t.oracle 的根因探针必须在「回退后的化石」上命中（红），退出码落 probe-red.log。
module stateoracleprobe

go 1.26
