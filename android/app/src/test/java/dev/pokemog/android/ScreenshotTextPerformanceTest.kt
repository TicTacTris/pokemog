package dev.pokemog.android

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScreenshotTextPerformanceTest {
    @Test fun cachedCatalogMatchesFreshAcrossAllNativeFixtures() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/appraisal-text.json"))
            .bufferedReader().use { JSONObject(it.readText()) }
        val catalog = fixture.getJSONArray("pokemon")
        val pokemon = List(catalog.length()) { i -> catalog.getJSONObject(i).let {
            Pokemon(it.getString("id"), it.getString("name"), 1, 1, 1)
        } }
        val index = PokemonNameIndex(pokemon)
        val cases = fixture.getJSONArray("cases")
        assertEquals(1062, cases.length())
        for (i in 0 until cases.length()) {
            val text = cases.getJSONObject(i).getString("text")
            val fresh = parseScreenshotText(text, pokemon)
            assertEquals(text, fresh, parseScreenshotText(text, pokemon, nameIndex = index))
            assertNull(fresh.timings)
        }
    }

    @Test fun sharedIndexHasNoCrossScanStateAndKeepsExactNamesAndFormOrder() {
        val pokemon = listOf(
            Pokemon("g1", "Giratina (Altered)", 1, 1, 1),
            Pokemon("g2", "Giratina (Origin)", 1, 1, 1),
            Pokemon("female", "Nidoran\u2640", 1, 1, 1),
            Pokemon("accent", "Flab\u00e9b\u00e9", 1, 1, 1),
            Pokemon("empty", "!!!", 1, 1, 1),
        )
        val index = PokemonNameIndex(pokemon)
        val texts = listOf("Giratina CP1820\n120/173 HP", "Nidoran\u2640 CP 123\nHP 55",
            "Flabebe CP 18 20\nHP 12O", "Giratinas CP 999\nHP 100", "")
        val regions = listOf(OcrRegion("120 / 173 HP", 10, 100, 180, 120),
            OcrRegion("120", 10, 100, 50, 120), OcrRegion("HP", 130, 100, 180, 120))
        val expected = texts.map { parseScreenshotText(it, pokemon, regions) }
        assertEquals(listOf("g1", "g2"), expected[0].candidates)
        assertEquals(listOf("female"), expected[1].candidates)
        assertEquals(listOf("accent"), expected[2].candidates)
        assertTrue(expected[3].candidates.isEmpty())
        val pool = Executors.newFixedThreadPool(4)
        try {
            pool.invokeAll(List(200) { i -> Callable {
                val j = i % texts.size
                assertEquals(expected[j], parseScreenshotText(texts[j], pokemon, regions, index))
            } }).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }
}
