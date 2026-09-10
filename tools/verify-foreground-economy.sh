#!/usr/bin/env bash
# Standalone JVM checks only; this does not replace Android/Robolectric/device gates.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -d app/src/main/java/dev/agentmirror/app/termview ]]; then
  module=app
elif [[ -d app/app/src/main/java/dev/agentmirror/app/termview ]]; then
  module=app/app
else
  echo 'Run from a corral-app or corral-core checkout.' >&2; exit 2
fi
command -v kotlinc >/dev/null
command -v java >/dev/null
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
src="$module/src/main/java/dev/agentmirror/app/termview"
test="$module/src/test/kotlin/dev/agentmirror/app/termview"
# Annotation only: execute the actual checked-in test bodies, with their check() assertions.
cat > "$work/Test.kt" <<'KOTLIN'
package org.junit
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Test
KOTLIN
cat > "$work/Main.kt" <<'KOTLIN'
import dev.agentmirror.app.termview.*
fun main() {
    var count = 0
    for (type in listOf(BoxBlockGeometryCacheTest::class.java, TermDrawControlSessionTest::class.java)) {
        for (method in type.declaredMethods.filter { it.getAnnotation(org.junit.Test::class.java) != null }.sortedBy { it.name }) {
            method.invoke(type.getDeclaredConstructor().newInstance())
            println("PASS ${type.simpleName}.${method.name}")
            count++
        }
    }
    check(count == 11) { "Unexpected discovered test count: $count" }
    println("RESULT $count passed (standalone JVM; not Android/JUnit runtime)")
}
KOTLIN
kotlinc "$src/BoxBlockGeometry.kt" "$src/BoxBlockGeometryCache.kt" \
  "$src/TermDrawControlSession.kt" "$test/BoxBlockGeometryCacheTest.kt" \
  "$test/TermDrawControlSessionTest.kt" "$work/Test.kt" "$work/Main.kt" \
  -include-runtime -d "$work/checks.jar"
java -jar "$work/checks.jar"
if [[ "${1:-}" == --bench ]]; then
  kotlinc "$src/BoxBlockGeometry.kt" "$src/BoxBlockGeometryCache.kt" \
    tools/verification/GeometryBench.kt -include-runtime -d "$work/bench.jar"
  java -cp "$work/bench.jar" dev.agentmirror.app.termview.GeometryBench
fi
