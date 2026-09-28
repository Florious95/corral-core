// This file declares a package name that differs from both the directory name
// (agentmirrord) and `main`. It is a foreign file mixed into the command
// package directory — its @consumes must NOT be attributed to this directory.
// The guard (directory-name == package-name, with the precise package-main
// exception) must keep rejecting it; otherwise T3-4 would report a drift on
// internal/other that this directory never imports.
//
// This comment block is deliberately NOT adjacent to the package clause: the
// package declaration below belongs to a different package.
// @consumes internal/other
package somethingelse
