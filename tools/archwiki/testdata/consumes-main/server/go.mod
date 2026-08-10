// T3-4 命令包盲区红测 fixture：命令包（package main，目录名 != 包名）的
// @consumes 声明必须能被读取，且防串味守卫必须保留。
//   * cmd/agentmirrord — 命令包声明了与 import 一致的 @consumes（修复目标：修复前必红、修复后转绿）
//   * cmd/agentmirrord/mixed.go — 目录混入声明别的包名的文件，其 @consumes 不得被误归属（防串味守卫）
//   * cmd/redcmd — 命令包 import 了内部包却未声明 @consumes（必红）
module github.com/remote-agent/fixture-consumes-main

go 1.26.5
