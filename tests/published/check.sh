#!/usr/bin/env sh
# Builds this project, which uses scala-go only through published artifacts (run
# `./mill __.publishLocal` in the repository first), and checks its output: what users get.
set -e
cd "$(dirname "$0")"
../../mill --ticker false app.goBuild
./out/app/goBuild.dest/app > out/actual.txt
diff -u expected.txt out/actual.txt
echo "published artifacts: OK"
