//go:build !unix

package rt

import "os"

func exitCode(state *os.ProcessState) int32 { return int32(state.ExitCode()) }

// terminate kills the process: there is no gentler way on these systems.
func terminate(p *os.Process, force bool) { p.Kill() }
