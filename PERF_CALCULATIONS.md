# Pure Android Calculation Performance (v0.6)

## Scope

These are **host JVM measurements, not Android device or OCR latency measurements**.
They cannot explain or promise to eliminate the reported approximately one-second phone
delay. Capture, bitmap conversion/resampling, ML Kit, OCR parsing, scheduling, UI rendering,
asset loading and image decoding are excluded. Measurements used an isolated host compiler rather than Gradle.

Measured on Linux/x86-64 (virtualized AMD Ryzen 7 7800X3D), OpenJDK 17.0.20.1,
Kotlin compiler 2.1.20, `-Xms512m -Xmx512m`. Before is the actual unmodified engine
compiled before editing and retained as a private local baseline JAR.
The after engine was compiled separately. Both use the same final harness,
1,000 warm-up operations **per workload**, followed by five equal-sized measurement batches.
Tables report the median batch mean, not per-call p50/p95. This is an observational
microbenchmark, not JMH or a CI timing gate; host load and JIT decisions still affect results.

## Changes

- CP-only binary-search probes no longer construct `Stats`, compute HP/stat product, or
  recalculate square roots. Each ranking builds 16 defense roots and 16 stamina roots;
  each spread computes its CP prefix once. CPM squares are precomputed per calculator.
- The exact expression remains `((baseAttack * sqrt(baseDefense)) * sqrt(baseStamina)) * (cpm * cpm) / 10`,
  with the same floor, minimum CP, integer-range validation and binary search through
  plateaus. There is no approximate inverse formula or changed rounding. Final `Stats`
  still use the original calculation, and Shedinja changes HP only, not CP stamina.
- `rankIVs` retains sorted results in a synchronized, access-ordered, 32-entry LRU.
  Key: Pokemon ID, attack, defense, stamina, CP cap, maximum level. Name, evolution/variant
  metadata and selected IVs are not keys. Validation still happens before a hit.
  Multipliers belong to the calculator, so separate calculators never share cached data.
- The entire lookup/miss/publication is synchronized, including access-order changes.
  Concurrent cold calls cannot publish partial results or duplicate the same calculation.
  Tradeoff: different cold keys on one calculator serialize; there is no parallel ranking pool.
- Returned lists use `Collections.unmodifiableList`, including protected sublists/iterators.
  Entries and their `IVs`/`Stats` contain only immutable scalar values. No defensive list copy
  or sort is needed on a hit. The public `List<RankedIVs>` signature is unchanged; attempts
  to mutate it through a cast now throw rather than corrupting a later assessment.
- Cache lifetime is the existing repository-owned `Calculations` lifetime. There is no
  global cache. The optional constructor cache limit defaults to 32; repositories select 8 on low-RAM devices. At most 32 retained
  lists / 131,072 spreads, approximately **13 MB on ART** depending on object layout and
  alignment, plus small map/key/array overhead. This is an estimate, not measured phone heap.
  A cold miss also temporarily owns one new ranking and sorting workspace before eviction;
  caller-retained evicted lists are naturally outside the cache's retention bound.
  The eight-entry low-RAM limit retains at most 32,768 spreads (roughly one quarter of the estimate); large families may churn rather than retain all rankings.
- The bar scanner reuses the pixel classification within a contiguous run instead of
  evaluating RGB/alpha thresholds twice for every interior run pixel. Color predicates,
  blending, endpoint tolerances, grouping and ambiguity rules are unchanged. No additional
  image-sized classification buffer is allocated.

The bounded cache still misses when switching to an evicted family. Eevee's nine forms
across three leagues occupy 27 entries. Changing Shadow or IVs on that family reuses those
entries. Switching among many families can cause churn; there is intentionally no unbounded
memoization. `ScanAssessments` still rebuilds its catalog map and `Projections` still searches
the sorted list for selected IVs. Their measured residual cost did not justify another index
and lifetime contract in this change.

## Assessment Results

All times are milliseconds per operation. Cold means a new `Calculations` instance with
an empty ranking cache, **not cold JIT or cold app startup**. Warm reuses one calculator.
Toggle alternates normal/Shadow on that same calculator. Catalog and scan inputs are
loaded/prepared before timing. Each assessment includes all forward normal evolutions and
Great/Ultra/Little leagues (1500/2500/500). Cases use effective level 20: Shinx 4/12/15,
Giratina Altered 8/11/6 (1820 CP / 173 HP), Eevee 1/15/14. Shinx/Eevee readings are
generated from these canonical stats, not asserted to be the user's captured readings.

| Workload | Before ms | After ms | Before allocated B/op | After allocated B/op |
| --- | ---: | ---: | ---: | ---: |
| Shinx rank, cold (1500/50) | 0.737234 | 0.370767 | 587688 | 589112 |
| Shinx rank, warm (1500/50) | 0.749938 | 0.000371 | 587190 | 54 |
| Shinx family, cold | 4.804626 | 3.926805 | 5412633 | 5417561 |
| Shinx family, warm | 4.739324 | 0.045256 | 5412073 | 115833 |
| Shinx family, toggle | 4.865566 | 0.056174 | 5413357 | 117597 |
| Giratina Altered, cold | 1.618130 | 1.327497 | 1871241 | 1873633 |
| Giratina Altered, warm | 1.574843 | 0.037521 | 1870681 | 103313 |
| Giratina Altered, toggle | 1.566114 | 0.041076 | 1871405 | 104005 |
| Eevee family, cold | 13.968709 | 12.605294 | 16049601 | 16062393 |
| Eevee family, warm | 13.984303 | 0.062020 | 16049089 | 149849 |
| Eevee family, toggle | 14.021359 | 0.099898 | 16050593 | 151657 |

Batch sizes: 100 for cold rank, 1,000 for warm rank, 30 for each assessment workload.
Warm repeated assessments improve approximately 105x / 42x / 225x for these three cases;
cold assessments improve approximately 18% / 18% / 10%. Sorting and final rank objects
still dominate part of a miss. HotSpot can eliminate old temporary `Stats` allocations:
the source-level probe allocation removal therefore does **not** imply a reduction in this
host's measured cold allocation. The new cache/root arrays actually add small cold overhead.
Allocated bytes are per-thread `ThreadMXBean` counters, not retained heap or Android ART data.

## Bar Results

The detector receives each **complete fixture raster in memory**. These checked-in fixtures
are privacy-preserving appraisal crops, not full phone screenshots. RLE decoding and allocation
of the source `IntArray` occur before timing. There is no ROI shortcut inside the benchmark.
Five batches of 100 detections follow 1,000 warm-up detections for each image.

| Fixture | Pixels | Before ms | After ms | Before allocated B/op | After allocated B/op |
| --- | --- | ---: | ---: | ---: | ---: |
| Giratina FFmpeg | 215x150 | 0.084599 | 0.081230 | 36632 | 39489 |
| Giratina ImageIO | 215x150 | 0.084864 | 0.068621 | 27600 | 30136 |
| Shinx native | 560x340 | 0.366710 | 0.311268 | 96792 | 95376 |
| Shinx analyzed | 360x220 | 0.153502 | 0.136873 | 58528 | 57568 |
| S23 native | 540x405 | 0.397623 | 0.375442 | 138656 | 137600 |
| S23 nearest | 364x273 | 0.186632 | 0.187875 | 88432 | 88399 |
| S23 bilinear | 364x273 | 0.225228 | 0.184234 | 87031 | 86043 |

The small nearest-neighbor regression is effectively unchanged at this measurement scale;
there is no claim of universal detector speedup or allocation reduction. Allocation differences
reflect JVM escape-analysis/JIT behavior as well as the still-existing run/group objects.
Both versions return 8/11/6 for Giratina, 4/12/15 for Shinx, and 1/15/14 for S23.

## Reproduction

From the project root, set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to the Android SDK with platform 35. Populate the Gradle dependency cache by running the Android unit-test build first. `GRADLE_USER_HOME` defaults to `$HOME/.gradle`. Choose an existing scratch directory outside the repository:

```bash
SCRATCH="${TMPDIR:-/tmp}"
OUT="$SCRATCH/pokemog-calculations-after.jar" bash scripts/benchmark-android-calculations.sh
ENGINE_JAR="$SCRATCH/pokemog-calculations-before.jar" OUT="$SCRATCH/pokemog-benchmark-before.jar" bash scripts/benchmark-android-calculations.sh
TESTS_ONLY=1 OUT="$SCRATCH/pokemog-calculations-tests.jar" bash scripts/benchmark-android-calculations.sh
```

For 0.9.0, the current harness and any `ENGINE_JAR` must both use `dev.pokemog.android`. Historical pre-rename artifacts mentioned above require their historical harness; they were not renamed or regenerated. The current standalone regression run passes 62 tests; historical measurements below are not new performance claims.

The host-only source lives in `android/benchmarks/`, outside Android test source sets because it uses JDK management APIs. The script uses `JAVA_HOME` and invokes cached Kotlin compiler JARs
directly. It writes only the requested `OUT` JAR, never Gradle outputs. The old JAR
is a local baseline artifact, not a checked-in binary: to repeat the comparison elsewhere,
retain a pre-change compiled engine before editing. `ENGINE_JAR` recompiles only the common
benchmark harness, using friend paths to call the unchanged internal pure assessment entry point.
Run before and after sequentially, not concurrently. The benchmark compiles the real repository
against `android.jar` but never instantiates Android Context or performs asset I/O in timed work.

## Correctness

Existing stats, ranking, inference, projection, scan-domain and bar fixtures are unmodified.
Final isolated verification: **56 JVM tests passed** (the original 50 plus six new tests).
Added tests cover calculation-only cache keys, metadata-independent hits, separate multiplier
owners, all base-stat changes, species/Shedinja identity, CP/level caps, access-ordered eviction
at 32 entries, mutation rejection, concurrent publication, selected-IV changes and canonical
Shedinja Shadow invariance. An independent copy of the original full-Stats probe algorithm
compares entire ranked lists with exact JVM equality, including all 13 canonical family forms
at three caps, existing parity cases, low-CP plateaus, tied ranks, empty results and overflow.
There are no performance thresholds in tests. Android device timing, full-screen capture timing,
OCR latency, release build integration and phone heap profiling remain for parent/device validation.
