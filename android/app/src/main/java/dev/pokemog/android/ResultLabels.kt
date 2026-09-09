package dev.pokemog.android

fun levelNumber(level: Double): String = if (level % 1.0 == 0.0) level.toInt().toString() else fmt(level)

/** List only inferred branches and actual values, never interpolate a base-level range. */
fun levelAlternatives(scenarios: List<LevelScenario>): List<String> = listOf(false, true).mapNotNull { buddy ->
    val levels = scenarios.filter { it.activeBuddy == buddy }.flatMap { it.baseLevels }.distinct().sorted()
    if (levels.isEmpty()) null else {
        val condition = if (buddy) "With active Buddy boost (+1)" else "Without active Buddy boost"
        "$condition: base level${if (levels.size == 1) "" else "s"} ${levels.joinToString(", ", transform = ::levelNumber)}"
    }
}

/** Short, result-specific conditions remain visible; general explanations live in the drawer. */
fun shortFeasibility(value: String): String? = when {
    value.contains("No eligible", true) || value.contains("Cannot fit", true) -> "Not eligible"
    value.contains("Already over", true) || value.contains("powering down", true) -> "Over cap"
    value.contains("exceeds the selected", true) -> "Above level limit"
    value.contains("unknown", true) && !value.contains("depends", true) -> "Level unknown"
    value.contains("depends", true) || value.contains("ambig", true) -> "Level uncertain"
    value.contains("unequip", true) -> "Remove buddy boost"
    else -> null
}

fun shortScanIssue(summary: ScanSummary): String = when {
    summary.ivs == null -> "IV bars unreadable. Rescan."
    summary.pokemon == null && summary.message.contains("ambiguous", true) -> "Pokemon form unclear. Rescan."
    summary.pokemon == null -> "Pokemon unreadable. Rescan."
    else -> "Scan unavailable. Rescan."
}

/** Observed effective level includes any active buddy boost; it is not a guessed base level. */
fun effectiveLevelLabel(summary: ScanSummary?): String {
    val levels = summary?.effectiveLevels.orEmpty()
    return when (levels.size) {
        0 -> "Unknown"
        1 -> fmt(levels.single())
        else -> levels.joinToString(", ", transform = ::fmt)
    }
}
