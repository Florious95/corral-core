// T3-3 回归红测 fixture：同一文件多个 @contract 符号，其中一个残缺（必须红）。
// 返工 #1（w-t3c-verify 实证）：旧实现按「文件」union 标签，前一个完整 @contract
// 的 @err/@inv 掩盖了后一个残缺符号的缺失——单符号四格 fixture 永远撞不到这条。
module github.com/remote-agent/fixture-contract-multi

go 1.26.5
