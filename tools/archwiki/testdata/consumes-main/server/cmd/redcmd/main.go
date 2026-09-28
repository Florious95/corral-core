// Command redcmd imports config but never declares @consumes — T3-4 must flag
// it as architecture drift (import 了却未声明). This is the must-be-red case:
// a command package cannot hide an undeclared dependency behind package main.
package main

import "github.com/remote-agent/fixture-consumes-main/internal/config"

func main() { _ = config.Load() }
