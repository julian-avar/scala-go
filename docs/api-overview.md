The API of scala-go's libraries. The [documentation](docs/index.html) explains how to use them.

- `scalago.go` (`scalago-library`, added by `ScalaGoModule`): declaring Go facades and exports
  (`GoImport`, `GoExport`), Go errors, and Go data shared with Go code (`GoString`, `GoSlice`,
  `GoMap`, `GoChan`, `GoPtr`).
- `scalago.gears` (`scalago-gears`): Go channels and contexts in gears code.
- `scalago.gostd` (`scalago-gostd`): generated facades of Go standard library packages.
