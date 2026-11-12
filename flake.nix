{
  description = "scala-go: compile Scala 3 to Go";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      # The Mill version build.mill pins (`//| mill-version: x`).
      millVersion =
        let
          prefix = "//| mill-version: ";
          lines = nixpkgs.lib.splitString "\n" (builtins.readFile ./build.mill);
        in
        nixpkgs.lib.removePrefix prefix (nixpkgs.lib.findFirst (nixpkgs.lib.hasPrefix prefix) "" lines);
    in
    {
      # The JDK CI uses, which Mill also runs on (`mill-jvm-version: system`), Go, and nixpkgs' Mill:
      # nothing is downloaded that needs nix-ld on NixOS.
      devShells = forAllSystems (
        pkgs:
        let
          jdk = pkgs.jdk25;
          mill = pkgs.mill.override { jre = jdk; };
          # `./mill` uses the Mill in this folder rather than downloading one (a native binary, which
          # NixOS cannot run without nix-ld), under each name the launcher can look for.
          millDownloads = pkgs.runCommand "mill-downloads" { } ''
            mkdir $out
            for suffix in "" -native-linux-amd64 -native-linux-aarch64 -native-mac-amd64 -native-mac-aarch64; do
              ln -s ${mill}/bin/mill $out/${mill.version}$suffix
            done
          '';
        in
        assert nixpkgs.lib.assertMsg (mill.version == millVersion) ''
          build.mill pins Mill ${millVersion}, nixpkgs has Mill ${mill.version}: change the
          `mill-version` in build.mill (and the `./mill` launcher), or lock another nixpkgs.
        '';
        {
          default = pkgs.mkShell {
            packages = [
              jdk
              mill
              pkgs.go
            ];
            JAVA_HOME = jdk.home;
            MILL_FINAL_DOWNLOAD_FOLDER = millDownloads;
          };
        }
      );

      # The runtime job of CI (.github/workflows/ci.yml) on this system's Go: gofmt, vet, unit tests
      # under the race detector, vet on the cross-compilation targets. The runtime has no
      # dependencies, so this needs no network.
      checks = forAllSystems (pkgs: {
        runtime = pkgs.stdenv.mkDerivation {
          name = "scalago-runtime-check";
          src = ./runtime;
          nativeBuildInputs = [ pkgs.go ];
          dontConfigure = true;
          dontInstall = true;
          buildPhase = ''
            export HOME=$TMPDIR GOCACHE=$TMPDIR/go-build GOPATH=$TMPDIR/go GOTOOLCHAIN=local GOFLAGS=-mod=mod
            unformatted=$(gofmt -l .)
            if [ -n "$unformatted" ]; then echo "gofmt: $unformatted"; exit 1; fi
            go vet ./...
            go test -race ./...
            for target in linux/amd64 linux/arm64 darwin/arm64 windows/amd64 linux/riscv64; do
              CGO_ENABLED=0 GOOS=''${target%/*} GOARCH=''${target#*/} go vet ./...
            done
            touch $out
          '';
        };
      });

      formatter = forAllSystems (pkgs: pkgs.nixfmt-tree);
    };
}
