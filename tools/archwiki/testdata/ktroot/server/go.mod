// ktroot 红测 fixture：Kotlin 源码根在 src/main/kotlin（不是 java）——
// 扫描根必须跨模块覆盖 app/*/src/main/{java,kotlin} 两种形态。
module github.com/remote-agent/fixture-ktroot

go 1.26.5
