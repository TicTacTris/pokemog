package dev.pokemog.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry

class ShadowToggleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wholeButtonTogglesExactlyOnce() {
        var checked by mutableStateOf(false)
        var changes = 0
        compose.setContent { PokeMogTheme(false) { ShadowToggle(checked, true) { checked = it; changes++ } } }
        compose.onNodeWithTag("shadow-toggle").assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Shadow")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
            .performTouchInput { click() }.assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
        compose.runOnIdle { assertEquals(1, changes) }
        compose.onNodeWithTag("shadow-toggle").performTouchInput { click() }.assertIsOff()
        compose.runOnIdle { assertEquals(2, changes) }
    }

    @Test fun unresolvedIdentityDisablesWholeButton() {
        compose.setContent { PokeMogTheme(true) { ShadowToggle(false, false) { error("Disabled input was invoked") } } }
        compose.onNodeWithTag("shadow-toggle").assertIsNotEnabled().performTouchInput { click() }.assertIsOff()
    }

    @Test fun giratinaHeaderKeepsIvsWithoutTechnicalReadings() {
        val repo = PokemonRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val scan = ScanResult("Giratina\nCP1820\n173/173 HP", listOf("giratina_altered", "giratina_origin"), IVs(8, 11, 6), 1820, 173)
        val result = ScanAssessments.calculate(repo, scan, true)
        compose.setContent { PokeMogTheme(false) { ResultHeader(result, true) {} } }
        compose.onNodeWithText("8 / 11 / 6").assertIsDisplayed()
        compose.onNodeWithText("Current CP", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Effective level", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("shadow-toggle").assertIsEnabled().assertIsOn()
    }

    @Test fun catalogMissingShadowStillAllowsTheCondition() {
        val repo = PokemonRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val scan = ScanResult("Scatterbug", listOf("scatterbug"), IVs(15, 13, 13), 206, 66)
        var result by mutableStateOf(ScanAssessments.calculate(repo, scan, false))
        compose.setContent { PokeMogTheme(true) { ResultHeader(result, true) { result = ScanAssessments.calculate(repo, scan, it) } } }
        compose.onNodeWithTag("shadow-toggle").assertIsEnabled().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(true, result.unverifiedShadowIds.contains("scatterbug")) }
        compose.onNodeWithText("Shadow availability unverified").assertIsDisplayed()
        compose.onNodeWithTag("shadow-toggle").performClick().assertIsOff()
    }

    @Test fun sharedFormHeaderNeverClaimsRepresentativeIdentityAcrossShadowToggle() {
        val repo = PokemonRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val scan = ScanResult("Toxtricity", listOf("toxtricity_low_key", "toxtricity_amped"), IVs(10, 11, 15), 1436, 117)
        var result by mutableStateOf(ScanAssessments.calculate(repo, scan, false))
        compose.setContent { PokeMogTheme(false) { ResultHeader(result, true) { result = ScanAssessments.calculate(repo, scan, it) } } }
        compose.onNodeWithText("Toxtricity").assertIsDisplayed()
        compose.onNodeWithText("Form unresolved; shared IV calculations").assertIsDisplayed()
        compose.onNodeWithText("10 / 11 / 15").assertIsDisplayed()
        compose.onNodeWithText("Effective level", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("shadow-toggle").assertIsEnabled().performClick().assertIsOn()
        compose.onNodeWithText("Toxtricity").assertIsDisplayed()
        compose.onNodeWithText("Form unresolved; shared IV calculations").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, result.pokemon) }
    }

    @Test fun compactLeagueCardOmitsTechnicalStats() {
        val repo = PokemonRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val scan = ScanResult("Scatterbug", listOf("scatterbug"), IVs(15, 13, 13), 206, 66)
        val league = ScanAssessments.calculate(repo, scan, false).leagues.first()
        compose.setContent { PokeMogTheme(false) { LeagueCard(league.title, league.cpCap, league.assessment.evolutions.first()) } }
        compose.onNodeWithText("PvP IV percentile").assertIsDisplayed()
        for (label in listOf("Effective level", "Current stats", "ATK", "DEF", "Stat product", "Rank", "CP", "Lv")) {
            compose.onNodeWithText(label, substring = true).assertDoesNotExist()
        }
    }
}
