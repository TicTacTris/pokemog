package dev.pokemog.android

import java.text.Normalizer
import java.util.Locale

data class ScanResult(val text: String, val candidates: List<String>, val ivs: IVs?, val cp: Int?, val hp: Int?, val timings: ScanTimings? = null, val autoAnchors: AutoScanAnchors? = null)

private val accents = Regex("[\\u0300-\\u036f]")
private val nonName = Regex("[^a-z0-9]+")
private val suffix = Regex("(?:\\s+\\([^()]+\\))+$")
private val whitespace = Regex("[\\s\\p{Z}\\u0085]+")
private val slashSpaces = Regex(" */ *")
private val slashWhitespace = Regex("[\\s\\p{Z}\\u0085]*/[\\s\\p{Z}\\u0085]*")
private val lineBreaks = Regex("\\r\\n|[\\n\\r\\u000b\\u000c\\u0085\\u2028\\u2029]")
private const val number = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)"
private val numeric = Regex("($number)(?: */ *($number))?")
private const val token = "[^ ]+(?: +(?:kg|g|cm|m|lbs?|ft)(?= |$))?"
private val forward = Regex("^[ :]*($token)", RegexOption.IGNORE_CASE)
private val backward = Regex("($token) *$", RegexOption.IGNORE_CASE)
private val labels = Regex("(?<![a-zA-Z0-9_])CP(?![a-zA-Z_])|(?<![a-zA-Z_])HP(?![a-zA-Z_])", RegexOption.IGNORE_CASE)
private val measurement = Regex("[0-9.,]+ *(?:kg|g|cm|m|lbs?|ft)", RegexOption.IGNORE_CASE)
private val unit = Regex("kg|g|cm|m|lbs?|ft", RegexOption.IGNORE_CASE)
private val fragment = Regex("[0-9OoIl|.,+-]+")
private val date = Regex("(?:[0-9]{1,2}[/.-][0-9]{1,2}[/.-][0-9]{4}|[0-9]{4}[/.-][0-9]{1,2}[/.-][0-9]{1,2})")
private val separateHpPattern = Regex("^$number *HP(?![a-zA-Z0-9_])", RegexOption.IGNORE_CASE)

private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .replace(accents, "")
    .lowercase(Locale.ROOT).replace("\u2640", " female ").replace("\u2642", " male ")
    .replace(nonName, " ").trim()

internal fun catalogBaseName(name: String): String = name.replace(suffix, "").trim()
internal fun catalogNameGroup(name: String): String = normalize(catalogBaseName(name))

/** Immutable catalog snapshot; preserves order, duplicate names and all forms. */
internal class PokemonNameIndex(pokemon: List<Pokemon>) {
    private val names = pokemon.map { it.id to catalogNameGroup(it.name) }

    fun candidates(text: String): List<String> {
        val words = " ${normalize(text)} "
        return names.filter { (_, name) -> name.isNotEmpty() && words.contains(" $name ") }.map { it.first }
    }

    fun matcher(ids: Set<String>): (String) -> Boolean {
        val selected = names.filter { it.first in ids }.map { it.second }.filter { it.isNotEmpty() }.distinct()
        return { text ->
            val words = " ${normalize(text)} "
            selected.any { words.contains(" $it ") }
        }
    }
}

/** Upright OCR coordinates in the analyzed image, not the original capture. */
data class OcrRegion(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Exact names are candidates only, including dialogue/nicknames and every possible form. */
internal fun parseScreenshotText(
    text: String,
    pokemon: List<Pokemon>,
    regions: List<OcrRegion> = emptyList(),
    nameIndex: PokemonNameIndex = PokemonNameIndex(pokemon),
): ScanResult {
    val candidates = nameIndex.candidates(text)

    // Numeric OCR only normalizes whitespace, never characters such as O or fullwidth digits.
    fun spaces(value: String) = value.replace(whitespace, " ").trim().replace(slashSpaces, "/")
    fun unrelated(value: String) = measurement.matches(value) || date.matches(value)
    fun suspect(value: String?) = value != null && value.any { it in '0'..'9' } && !unrelated(value)
    // Prefer complete lines over their contained elements, especially for injured-HP fractions.
    val boxes = regions.filter { it.left >= 0 && it.top >= 0 && it.right > it.left && it.bottom > it.top }.distinct()
    val outer = boxes.filter { box -> boxes.none { other ->
        other != box && other.left <= box.left && other.top <= box.top &&
            other.right >= box.right && other.bottom >= box.bottom &&
            spaces(other.text).contains(spaces(box.text)) &&
            (other.left < box.left || other.top < box.top || other.right > box.right || other.bottom > box.bottom ||
                spaces(other.text).length > spaces(box.text).length)
    } }
    val geometryLabels = outer.flatMap { box -> labels.findAll(box.text).map { it.value.uppercase(Locale.ROOT) }.toList() }.toSet()
    fun adjacent(first: OcrRegion, second: OcrRegion): Boolean {
        val height = minOf(first.bottom.toLong() - first.top, second.bottom.toLong() - second.top)
        val overlapY = minOf(first.bottom, second.bottom).toLong() - maxOf(first.top, second.top)
        val overlapX = minOf(first.right, second.right).toLong() - maxOf(first.left, second.left)
        return (second.left.toLong() - first.right in 0..height && overlapY * 2 >= height) ||
            (second.top.toLong() - first.bottom in 0..height &&
                overlapX * 2 >= minOf(first.right.toLong() - first.left, second.right.toLong() - second.left))
    }
    fun detachedUnit(box: OcrRegion) = outer.any { other ->
        other != box && unit.matches(spaces(other.text)) &&
            measurement.matches("${spaces(box.text)} ${spaces(other.text)}") && adjacent(box, other)
    }
    fun numericContinuation(box: OcrRegion) = outer.any { other ->
        other != box && fragment.matches(spaces(other.text)) && !unrelated(spaces(other.text)) && !detachedUnit(other) &&
            (adjacent(box, other) || adjacent(other, box))
    }
    val readings = mapOf("CP" to mutableListOf<Int?>(), "HP" to mutableListOf<Int?>())
    fun add(label: String, raw: String) {
        val match = numeric.matchEntire(spaces(raw))
        val current = match?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        val maxText = match?.groups?.get(2)?.value
        val maximum = if (maxText == null) current else maxText.replace(",", "").toIntOrNull()
        readings.getValue(label).add(maximum?.takeIf {
            current != null && it >= 10 && current <= it && (label == "HP" || maxText == null)
        })
    }
    fun labeled(source: String, geometric: Set<String> = emptySet(), sourceBox: OcrRegion? = null) {
        // Slash-adjacent whitespace belongs to a fraction; other line breaks delimit UI fields.
        val lines = source.replace(slashWhitespace, "/")
            .split(lineBreaks)
            .map(::spaces).filter { it.isNotEmpty() }
        val consumedLines = mutableSetOf<Int>()
        fun measurementLine(index: Int): Boolean {
            val value = lines.getOrNull(index) ?: return false
            val next = lines.getOrNull(index + 1) ?: return false
            return unit.matches(next) && measurement.matches("$value $next")
        }
        fun continuationLine(index: Int) = lines.getOrNull(index)?.let {
            fragment.matches(it) && !unrelated(it) && !measurementLine(index)
        } == true
        for ((index, input) in lines.withIndex()) {
            val standalone = input.removeSuffix(":").trim().uppercase(Locale.ROOT)
            if (standalone in readings) {
                if (standalone in geometric) continue
                val neighbors = if (standalone == "HP") listOf(index - 1, index + 1) else listOf(index + 1)
                for (neighbor in neighbors) {
                    val value = lines.getOrNull(neighbor) ?: continue
                    if (neighbor !in consumedLines && ' ' !in value && !labels.containsMatchIn(value) && suspect(value) && !measurementLine(neighbor)) {
                        val continued = fragment.matches(value) && listOf(neighbor - 1, neighbor + 1).any {
                            it != index && it !in consumedLines && continuationLine(it)
                        }
                        if (continued) readings.getValue(standalone).add(null) else add(standalone, value)
                        consumedLines.add(neighbor)
                    }
                }
                continue
            }
            // Retain the established ASCII boundaries, including compact CP1820 / 173HP.
            var consumedUntil = -1
            for (labelMatch in labels.findAll(input)) {
                val label = labelMatch.value.uppercase(Locale.ROOT)
                val after = forward.find(input.substring(labelMatch.range.last + 1))?.groups?.get(1)
                val before = if (label == "HP") backward.find(input.substring(0, labelMatch.range.first))?.groups?.get(1) else null
                // Consume entire suspect tokens so malformed fractions cannot yield a numeric fragment.
                if (before != null && before.range.first > consumedUntil && suspect(before.value)) add(label, before.value)
                if (after != null && suspect(after.value)) {
                    val end = labelMatch.range.last + 1 + after.range.last
                    val rest = input.substring(end + 1).trim()
                    val next = forward.find(rest)?.groupValues?.get(1)
                    val separateHp = separateHpPattern.containsMatchIn(rest)
                    val continued = (next != null && fragment.matches(next) && !unrelated(next) && !separateHp) ||
                        (rest.isEmpty() && fragment.matches(after.value) &&
                            ((label !in geometric && continuationLine(index + 1)) ||
                                (sourceBox != null && numericContinuation(sourceBox))))
                    if (continued) readings.getValue(label).add(null) else add(label, after.value)
                    consumedUntil = end
                }
            }
        }
    }
    labeled(text, geometryLabels)
    for (box in outer) labeled(box.text, sourceBox = box)
    for (labelBox in outer) {
        val label = spaces(labelBox.text).removeSuffix(":").trim().uppercase(Locale.ROOT)
        if (label !in readings) continue
        val height = (labelBox.bottom - labelBox.top).toLong()
        for (valueBox in outer) {
            if (valueBox == labelBox) continue
            val value = spaces(valueBox.text)
            if (!suspect(value) || ' ' in value || detachedUnit(valueBox)) continue
            val overlapY = minOf(labelBox.bottom, valueBox.bottom).toLong() - maxOf(labelBox.top, valueBox.top)
            val overlapX = minOf(labelBox.right, valueBox.right).toLong() - maxOf(labelBox.left, valueBox.left)
            val valueHeight = (valueBox.bottom - valueBox.top).toLong()
            val rightGap = valueBox.left.toLong() - labelBox.right
            val leftGap = labelBox.left.toLong() - valueBox.right
            val belowGap = valueBox.top.toLong() - labelBox.bottom
            val aboveGap = labelBox.top.toLong() - valueBox.bottom
            val beside = overlapY * 2 >= minOf(height, valueHeight) &&
                (rightGap in 0..height * 2 || (label == "HP" && leftGap in 0..height * 2))
            val stacked = overlapX * 2 >= minOf(labelBox.right - labelBox.left, valueBox.right - valueBox.left) &&
                (belowGap in 0..height || (label == "HP" && aboveGap in 0..height))
            if (!beside && !stacked) continue
            // A nearby number is not adjacent if another OCR item lies in the gap.
            val gapLeft = if (beside) minOf(labelBox.right, valueBox.right) else maxOf(labelBox.left, valueBox.left)
            val gapRight = if (beside) maxOf(labelBox.left, valueBox.left) else minOf(labelBox.right, valueBox.right)
            val gapTop = if (beside) maxOf(labelBox.top, valueBox.top) else minOf(labelBox.bottom, valueBox.bottom)
            val gapBottom = if (beside) minOf(labelBox.bottom, valueBox.bottom) else maxOf(labelBox.top, valueBox.top)
            val blocked = outer.any { other -> other != labelBox && other != valueBox &&
                if (beside) other.left >= gapLeft && other.right <= gapRight && other.top < gapBottom && other.bottom > gapTop
                else other.top >= gapTop && other.bottom <= gapBottom && other.left < gapRight && other.right > gapLeft
            }
            if (blocked) continue
            // Without a complete fraction line, never mistake one element for maximum HP.
            val fractionFragment = '/' !in value && outer.any { other ->
                other != valueBox && '/' in other.text && !unrelated(spaces(other.text)) &&
                    minOf(other.bottom, valueBox.bottom).toLong() - maxOf(other.top, valueBox.top) > 0 &&
                    (other.left.toLong() - valueBox.right in 0..valueHeight ||
                        valueBox.left.toLong() - other.right in 0..valueHeight)
            }
            val numericFragment = fragment.matches(value) && numericContinuation(valueBox)
            if (fractionFragment || numericFragment) readings.getValue(label).add(null) else add(label, value)
        }
    }
    fun reading(label: String): Int? = readings.getValue(label).let { values ->
        values.firstOrNull()?.takeIf { first -> values.all { it == first } }
    }
    return ScanResult(text, candidates, null, reading("CP"), reading("HP"))
}
