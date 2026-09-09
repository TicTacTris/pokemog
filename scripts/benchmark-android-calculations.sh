#!/usr/bin/env bash
set -euo pipefail
# No Gradle invocation or writes to Android build outputs. Override OUT to retain before/after jars.
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK with platform 35}"
export PATH="$JAVA_HOME/bin:$PATH"
ROOT="$(realpath "$(dirname "$0")/..")"
OUT="${OUT:-${TMPDIR:-/tmp}/pokemog-calculations-perf.jar}"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
JARS=("$CACHE"/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20/*/*.jar
    "$CACHE"/org.jetbrains.kotlin/kotlin-stdlib/2.1.20/*/*.jar
    "$CACHE"/org.jetbrains.kotlin/kotlin-script-runtime/2.1.20/*/*.jar
    "$CACHE"/org.jetbrains.kotlin/kotlin-reflect/*/*/*.jar
    "$CACHE"/org.jetbrains.intellij.deps/trove4j/*/*/*.jar
    "$CACHE"/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/*/*/*.jar
    "$CACHE"/org.jetbrains/annotations/*/*/*.jar)
LIBS=("$CACHE"/org.jetbrains.kotlin/kotlin-stdlib/2.1.20/*/*.jar
    "$CACHE"/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/*/*/*.jar
    "$CACHE"/org.json/json/*/*/*.jar "$CACHE"/junit/junit/4.13.2/*/*.jar
    "$CACHE"/org.hamcrest/hamcrest-core/1.3/*/*.jar
    "$ANDROID_HOME/platforms/android-35/android.jar")
COMPILER_CP=$(IFS=:; printf '%s' "${JARS[*]}")
CP=$(IFS=:; printf '%s' "${LIBS[*]}")
MAIN="$ROOT/android/app/src/main/java/dev/pokemog/android"
TEST="$ROOT/android/app/src/test/java/dev/pokemog/android"
BENCH="$ROOT/android/benchmarks/CalculationsBenchmark.kt"
ls "$(dirname "$OUT")" >/dev/null
if [[ -n "${ENGINE_JAR:-}" ]]; then
    # Re-run precisely the same harness against a retained pre-edit engine, including internal entry points.
    java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
        -classpath "$ENGINE_JAR:$CP" -Xfriend-paths="$ENGINE_JAR" -d "$OUT" "$BENCH"
    java -Xms512m -Xmx512m -cp "$OUT:$ENGINE_JAR:$CP" dev.pokemog.android.CalculationsBenchmark "$ROOT"
    exit 0
fi
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d "$OUT" \
    "$MAIN/Calculations.kt" "$MAIN/Projections.kt" "$MAIN/ScanAssessments.kt" "$MAIN/PokemonRepository.kt" \
    "$MAIN/ResultLabels.kt" "$MAIN/DataPack.kt" "$MAIN/DataPackNetwork.kt" "$MAIN/DataPackStore.kt" "$MAIN/PokemonDataManager.kt" "$MAIN/StartupDataCheck.kt" \
    "$MAIN/AppraisalBarDetector.kt" "$MAIN/AppraisalAutoScan.kt" "$MAIN/ScreenshotText.kt" "$MAIN/ScanTimings.kt" "$MAIN/CaptureFramePixels.kt" "$MAIN/CaptureSizing.kt" \
    "$TEST/CalculationsTest.kt" "$TEST/ProjectionsTest.kt" "$TEST/ScanAssessmentsTest.kt" \
    "$TEST/AppraisalBarDetectorTest.kt" "$BENCH"
java -Xms512m -Xmx512m -cp "$OUT:$CP:$ROOT/android/app/src/test/resources" org.junit.runner.JUnitCore \
    dev.pokemog.android.CalculationsTest dev.pokemog.android.ProjectionsTest dev.pokemog.android.ScanAssessmentsTest dev.pokemog.android.AppraisalBarDetectorTest
if [[ "${TESTS_ONLY:-0}" == 1 ]]; then exit 0; fi
java -Xms512m -Xmx512m -cp "$OUT:$CP" dev.pokemog.android.CalculationsBenchmark "$ROOT"
