# successor5 test — SDK fallback 四态与 required 结构红测

只写 test WT 的 `RED.md`，不改实现/判据/旧账。开工先按总任务书运行 successor5 SDK gate，生成的 WT `app/local.properties` 只含 sdk.dir、0600、未跟踪且不输出值。

除 canonical/fixed-fixture/IMPL-unjudgeable 与 successor4 required 精确集合红测外，必须逐例列前置、动作、两边布尔操作数、期望 exit 与破坏前后结果，并含固定 token：

- `SUCCESSOR5_REQUIRED_EXACT`、`SUCCESSOR5_LEGACY_NEGATIVE`
- `SUCCESSOR5_SDK_SOURCE_WHITELIST`
- `SUCCESSOR5_SDK_FALLBACK_NO_OUTPUT`
- `SUCCESSOR5_SDK_EXTRA_KEY_REJECTED`
- `SUCCESSOR5_SDK_NOT_TRACKED`
- `impl_required=successor5_impl,successor5_bypass`
- `probe_required=successor5_probe`
- `legacy_impl_bypass=absent`、`legacy_probe=absent`
- `extra_key=2`、`duplicate_sdk_dir=2`、`invalid_sdk_dir=2`、`tracked_target=1`
- `--rerun-tasks`、`--no-build-cache`、`-count=1`

结构齿必须拒绝旧 id/argv/额外 required/错误四态绑定。SDK 齿必须证明有效环境优先、无效环境安全 fallback、额外/重复键和无效目录2、成功零输出/最小0600/未跟踪、被跟踪1；不得记录路径。RED.md 不冒充产品事实通过。产物齐后只 report_result 一次。
