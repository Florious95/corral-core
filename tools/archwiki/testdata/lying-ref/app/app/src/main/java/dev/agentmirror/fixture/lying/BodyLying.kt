package dev.agentmirror.fixture.lying

/** 包级 KDoc。 */
object Clean

/** 谎称类 `MissingPlain`、路径 docs/fake-plain.md、flag `--no-such-plain-flag`（KDoc 内谎报 flag）。 */
object Plain

/** 谎称类 `MissingBody`、路径 config/bad-body.yaml、flag `--no-such-body-flag`。 */
fun bodyRun() {
    // 函数体内普通 // 注释（旧扫描器完全不可见）：谎称 `MissingBodyComment` 与 config/bad-body.yaml 与 --no-such-body-flag
    println(Clean)
}
