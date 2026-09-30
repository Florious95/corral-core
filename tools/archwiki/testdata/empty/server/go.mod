// 空扫描红测 fixture：server/ 下无任何 .go 文件 → 0 包 → 空扫描视为失败（exit 2）。
module github.com/remote-agent/fixture-empty

go 1.26.5
