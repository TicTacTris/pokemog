package dev.pokemog.android

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ScreenshotTextTest {
    private fun parse(text: String, vararg regions: OcrRegion) =
        parseScreenshotText(text, emptyList(), regions.toList())

    @Test fun adjacentFragmentsAreRejectedWithoutNumericRepair() {
        for (text in listOf("CP 18 20", "CP\n18\n20", "CP 18 O", "CP\n18\nO", "CP 18\n20", "CP\u00a018\u200920")) {
            assertNull(text, parse(text).cp)
        }
        val separateFields = parse("CP1820 173/173 HP")
        assertEquals(1820, separateFields.cp)
        assertEquals(173, separateFields.hp)
        val cp = OcrRegion("CP", 100, 20, 130, 40)
        val first = OcrRegion("18", 135, 20, 155, 40)
        for (fragment in listOf("20", "O")) {
            assertNull(fragment, parse("CP", cp, first, OcrRegion(fragment, 160, 20, 180, 40)).cp)
            assertNull(fragment, parse("CP", cp, first, OcrRegion(fragment, 135, 45, 155, 65)).cp)
        }
        assertEquals(18, parse("CP\n18\n20", cp, first, OcrRegion("20", 135, 800, 155, 820)).cp)
        val line = OcrRegion("CP 18", 100, 20, 155, 40)
        assertNull(parse("CP 18\n20", line, OcrRegion("20", 135, 45, 155, 65)).cp)
        assertEquals(18, parse("CP 18\n20", line, OcrRegion("20", 135, 800, 155, 820)).cp)
        assertEquals(1820, parse("CP1820 769.9 kg").cp)
        assertEquals(1820, parse("CP1820 2026-09-08").cp)
    }

    @Test fun detachedUnitsAreNotHpReadings() {
        val text = "CP1820\n173/173\nHP\n769.9\nkg"
        assertEquals(1820, parse(text).cp)
        assertEquals(173, parse(text).hp)
        assertEquals(55, parse("Shinx\nCP123\n12/55\nHP\n9.5\nkg").hp)
        val hp = OcrRegion("HP", 100, 100, 130, 120)
        val fraction = OcrRegion("173/173", 10, 100, 95, 120)
        val weight = OcrRegion("769.9", 100, 125, 160, 145)
        assertEquals(173, parse(text, hp, fraction, weight, OcrRegion("kg", 100, 150, 120, 170)).hp)
        assertEquals(173, parse(text, hp, fraction, weight, OcrRegion("kg", 165, 125, 185, 145)).hp)
        assertNull(parse("HP", hp, fraction, weight, OcrRegion("kg", 100, 800, 120, 820)).hp)
        assertNull(parse("HP", hp, fraction, weight.copy(text = "12O"), OcrRegion("kg", 165, 125, 185, 145)).hp)
        assertNull(parse("173/173\nHP\n174").hp)
        assertNull(parse("173/173\nHP\n120/12O\nkg").hp)
    }

    @Test fun geometryGatesOnlyTheMatchingLabelsCrossLineFallback() {
        val cp = OcrRegion("CP", 100, 20, 130, 40)
        val value = OcrRegion("1820", 135, 15, 215, 45)
        val year = OcrRegion("2026", 100, 800, 180, 820)
        val text = "CP\n2026\nGiratina\n1820"
        assertEquals(1820, parse(text, cp, value, year).cp)
        assertNull(parse(text, cp, year).cp)
        assertNull(parse("CP1821", cp, value).cp)
        assertEquals(1820, parse("CP\n1820", OcrRegion("HP", 100, 100, 130, 120)).cp)
        assertEquals(173, parse("HP\n173", cp, value).hp)
        assertEquals(1820, parse("CP\n1820", year).cp)
        assertEquals(1820, parse("CP\n1820", cp.copy(right = 100)).cp)
    }

    @Test fun generatedNativeTextFixtureParity() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/appraisal-text.json"))
            .bufferedReader().use { JSONObject(it.readText()) }
        val catalog = fixture.getJSONArray("pokemon")
        val pokemon = List(catalog.length()) { index ->
            val entry = catalog.getJSONObject(index)
            Pokemon(entry.getString("id"), entry.getString("name"), 1, 1, 1)
        }
        val cases = fixture.getJSONArray("cases")
        assertEquals(1062, cases.length())
        for (index in 0 until cases.length()) {
            val entry = cases.getJSONObject(index)
            val text = entry.getString("text")
            val candidates = entry.getJSONArray("candidates")
            val expected = ScanResult(text, List(candidates.length()) { candidates.getString(it) }, null,
                if (entry.isNull("cp")) null else entry.getInt("cp"),
                if (entry.isNull("hp")) null else entry.getInt("hp"))
            assertEquals(text, expected, parseScreenshotText(text, pokemon))
        }
    }

    @Test fun unicodeWhitespaceAndLineBreaks() {
        for (space in listOf(" ", "\t", "\n", "\r\n", "\u0085", "\u00a0", "\u2009", "\u202f", "\u2028", "\u2029")) {
            val text = "CP${space}1820\nHP${space}120${space}/${space}173"
            val result = parse(text)
            assertEquals(text, 1820, result.cp)
            assertEquals(text, 173, result.hp)
            assertEquals(text, result.text)
            assertEquals(173, parse("120${space}/${space}173${space}HP").hp)
        }
        assertEquals(1820, parse("CP1820").cp)
        assertEquals(173, parse("173/173HP").hp)
        assertEquals(173, parse("HP173/173").hp)
        assertEquals(1234, parse("cp: 1,234").cp)
    }

    @Test fun giratinaRegressionPreservesOriginalTextAndCandidates() {
        val text = "CP\n1820\nGiratina\n173/173\nHP\nAttack\nDefense\nHP"
        val pokemon = listOf(Pokemon("giratina_altered", "Giratina (Altered)", 1, 1, 1),
            Pokemon("giratina_origin", "Giratina (Origin)", 1, 1, 1))
        assertEquals(ScanResult(text, pokemon.map { it.id }, null, 1820, 173),
            parseScreenshotText(text, pokemon))
    }

    @Test fun actualLayoutsDoNotReadWeightHeightOrCatchDateAsHp() {
        val giratina = "Giratina\nCP1820\n173 / 173 HP\n769.9kg\nGHOST / DRAGON\n4.48m\n" +
            "Attack\nDefense\nHP\nThis Giratina was caught on\n08/09/2026"
        val result = parse(giratina)
        assertEquals(1820, result.cp)
        assertEquals(173, result.hp)
        assertEquals(giratina, result.text)
        assertEquals(55, parse("Shinx\nCP 123\n12/55 HP\n9.5kg\nELECTRIC\n0.48m\nAttack\nDefense\nHP\n08/09/2026").hp)
        assertEquals(173, parse("173/173\nHP\n769.9kg").hp)
        assertEquals(173, parse("HP\n173/173\n769.9kg").hp)
        assertEquals(173, parse("173/173 HP\n769\nAttack\nDefense\nHP\nCaught on 08/09/2026").hp)
        assertEquals(173, parse("173/173 HP 769.9 kg").hp)
        assertEquals(173, parse("HP 173\n9.5 kg HP").hp)
        assertNull(parse("HP\n08/09/2026").hp)
    }

    @Test fun spatialUnitsAndDatesDoNotPoisonHp() {
        val label = OcrRegion("HP", 100, 100, 130, 120)
        val fraction = OcrRegion("120/173", 10, 100, 95, 120)
        for (other in listOf("769.9kg", "4.48m", "08/09/2026", "2026-09-08")) {
            assertEquals(other, 173, parse("HP", label, fraction, OcrRegion(other, 100, 125, 190, 145)).hp)
        }
        assertNull(parse("HP", label, fraction, OcrRegion("12O", 100, 125, 190, 145)).hp)
        assertNull(parse("HP", label, fraction, OcrRegion("174", 100, 125, 190, 145)).hp)
        assertEquals(173, parse("HP", label, fraction,
            OcrRegion("769 kg", 100, 125, 190, 145), OcrRegion("769", 100, 125, 150, 145),
            OcrRegion("kg", 160, 125, 190, 145)).hp)
    }

    @Test fun malformedTokensNeverYieldNumericFragments() {
        for (bad in listOf("12O", "O1820", "1,23", "12.5", "1820x", "1820_", "1820/", "1820/2",
            "2147483648", "999999999999999999999999", "\uff11\uff18\uff12\uff10")) {
            assertNull(bad, parse("CP $bad").cp)
        }
        for (bad in listOf("12O", "100/12O", "1O0/173", "120/173x", "120/173/200", "120/", "/173",
            "120 / / 173", "174/173", "9", "2147483648", "100/2147483648")) {
            assertNull(bad, parse("HP $bad").hp)
            assertNull(bad, parse("$bad HP").hp)
        }
        assertNull(parse("1820\n173/173").cp)
        assertNull(parse("1820\n173/173").hp)
        assertNull(parse("_CP 1820\nSCP 1820").cp)
    }

    @Test fun conflictsAndMalformedReadingsStayNull() {
        assertNull(parse("CP 1820\nCP 1821").cp)
        assertNull(parse("HP 120/173\nHP 120/174").hp)
        assertNull(parse("CP 1820\nCP 12O").cp)
        assertNull(parse("HP 173\n100/12O HP").hp)
        assertEquals(1820, parse("CP 1820\nCP 1,820").cp)
        assertEquals(173, parse("HP 120/173\n0/173 HP").hp)
        assertEquals(173, parse("HP 0/173").hp)
        assertNull(parse("100 HP 120").hp)
        assertNull(parse("CP 1820\nHP").hp)
        assertNull(parse("CP\n1820\nHP").hp)
        assertNull(parse("173\nHP\n174").hp)
        assertNull(parse("173/173\nHP\n12O").hp)
        assertNull(parse("HP\n100/12O").hp)
        assertNull(parse("HP 173\nHP 120/12/200").hp)
    }

    @Test fun reversedBlocksUseOnlyAlignedBoundedLabelPairs() {
        val cp = OcrRegion("CP", 100, 20, 130, 40)
        val value = OcrRegion("1820", 135, 15, 215, 45)
        val hp = OcrRegion("HP", 210, 100, 240, 120)
        val fraction = OcrRegion("120/173", 110, 100, 205, 120)
        val regions = arrayOf(value, fraction, hp, cp)
        val text = "1820\n120/173\nGiratina\nHP\nAttack\nCP"
        assertEquals(1820, parse(text, *regions).cp)
        assertEquals(173, parse(text, *regions).hp)
        assertEquals(1820, parse(text, *regions.reversedArray()).cp)
        assertNull(parse("CP", cp, value.copy(left = 300, right = 380)).cp)
        assertNull(parse("CP", cp, value.copy(top = 100, bottom = 130)).cp)
        assertNull(parse("CP", cp, value.copy(left = 0, right = 80)).cp)
        assertNull(parse("CP", cp, value.copy(right = 135)).cp)
        assertNull(parse("", value).cp)
    }

    @Test fun stackedLargeValueAndHpLabelAboveOrBelow() {
        val cp = OcrRegion("CP", 100, 20, 130, 40)
        val value = OcrRegion("1820", 85, 45, 165, 85)
        assertEquals(1820, parse("1820\nGiratina\nCP", cp, value).cp)
        assertNull(parse("CP", cp, value.copy(top = 61, bottom = 101)).cp)
        assertNull(parse("CP", cp, value.copy(left = 140, right = 220)).cp)
        val hp = OcrRegion("HP", 100, 100, 130, 120)
        assertEquals(173, parse("HP", hp, OcrRegion("120/173", 70, 125, 160, 145)).hp)
        assertEquals(173, parse("HP", hp, OcrRegion("120/173", 70, 75, 160, 95)).hp)
    }

    @Test fun spatialReadingsDoNotChooseBetweenConflictsOrRepairGarbage() {
        val label = OcrRegion("CP", 100, 20, 130, 40)
        val value = OcrRegion("1820", 135, 20, 200, 40)
        assertNull(parse("CP 1821", label, value).cp)
        assertNull(parse("CP 12O", label, value).cp)
        assertNull(parse("CP", label, value, value.copy(text = "1821", left = 140)).cp)
        assertNull(parse("CP", label, value.copy(text = "18O0")).cp)
        assertNull(parse("CP", label, value.copy(text = "1820/2000")).cp)
        assertNull(parse("CP", label, value.copy(text = "1820 Candy")).cp)
        assertNull(parse("CP", label, value.copy(left = 160), OcrRegion("Candy", 135, 20, 155, 40)).cp)
    }

    @Test fun lineAndElementDuplicatesDoNotReadRemainingHpAsMaximum() {
        val line = OcrRegion("120 / 173 HP", 10, 100, 180, 120)
        val current = OcrRegion("120", 10, 100, 50, 120)
        val max = OcrRegion("173", 70, 100, 110, 120)
        val label = OcrRegion("HP", 130, 100, 180, 120)
        assertEquals(173, parse("120 / 173 HP", line, current, max, label).hp)
        // If ML Kit supplies only incomplete elements, fail closed rather than return 120.
        assertNull(parse("HP", OcrRegion("HP", 0, 100, 25, 120),
            OcrRegion("120", 30, 100, 60, 120), OcrRegion("/173", 65, 100, 110, 120)).hp)
    }
}
