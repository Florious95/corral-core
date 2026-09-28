package dev.agentmirror.fixture.truthful

/** 包级 KDoc。 */
object Clean

/** 引用的 `Clean` 与 `Real` 真实存在。 */
object Real

/** 真实 flag `--listen` 与真实类 `Clean`。 */
fun bodyRun() {
    // 函数体内普通注释：真实符号 `Clean`、真实类 `Real`、真实路径 docs/real-body.md、真实 flag --listen
    println(Clean)
}
