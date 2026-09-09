package dev.pokemog.android

import android.graphics.BitmapFactory
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test

class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun appLaunchesWithBundledDataWithoutPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("0.10.0", info.versionName)
        assertEquals(12L, info.longVersionCode)
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText("Import screenshot").assertIsEnabled() }.isSuccess }
        compose.onNodeWithText("Import screenshot").assertIsDisplayed()
        compose.onNodeWithText("Start overlay").assertIsDisplayed()
        compose.onNodeWithText("PVP IVs on the GO").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onAllNodesWithText("Azumarill").assertCountEquals(0)
        compose.onAllNodesWithText("A little more potential.").assertCountEquals(0)
    }

    @Test fun darkModePersistsAcrossActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PokeMogAppearance.preferences(context)
        val hadPreference = prefs.contains(PokeMogAppearance.DARK_MODE)
        val previous = PokeMogAppearance.isDark(context)
        try {
            compose.onNodeWithContentDescription("Menu").performClick()
            compose.onNodeWithContentDescription("Dark mode").performClick()
            compose.waitForIdle()
            assertEquals(!previous, PokeMogAppearance.isDark(context))
            compose.activityRule.scenario.recreate()
            if (compose.onAllNodesWithContentDescription("Dark mode").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithContentDescription("Menu").performClick()
            }
            compose.onNodeWithContentDescription("Dark mode").assertIsDisplayed()
            assertEquals(!previous, PokeMogAppearance.isDark(context))
        } finally {
            if (hadPreference) PokeMogAppearance.setDark(context, previous)
            else prefs.edit().remove(PokeMogAppearance.DARK_MODE).commit()
        }
    }

    @Test fun toggleLabelsFollowSessionSignalsWithoutOpeningPermissionPrompts() {
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText("Import screenshot").assertIsEnabled() }.isSuccess }
        var id = -1L
        compose.runOnIdle { id = checkNotNull(OverlaySession.beginStart()) }
        try {
            compose.onNodeWithTag("overlay-toggle").assertTextContains("Starting...").assertIsNotEnabled()
            compose.onNodeWithTag("overlay-description").assertTextContains("Complete the Android permission prompt.")
            compose.runOnIdle { OverlaySession.dispatched(id); OverlaySession.running(id) }
            compose.onNodeWithTag("overlay-toggle").assertTextContains("Stop overlay").assertIsEnabled()
            compose.onNodeWithTag("overlay-description").assertTextContains("Scanner active. Tap to stop.")
            compose.runOnIdle { OverlaySession.stopped(id, "Capture ended") }
            compose.onNodeWithTag("overlay-toggle").assertTextContains("Start overlay").assertIsEnabled()
        } finally { compose.runOnIdle { OverlaySession.stopped(id) } }
    }

    @Test fun instructionsAndThemeAreAvailableInSideMenu() {
        compose.onNodeWithText("PVP IVs on the GO").assertIsDisplayed()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Dark mode").assertIsDisplayed()
        compose.onAllNodesWithText("Scan details").assertCountEquals(0)
        compose.onNodeWithText("About & licenses").performClick()
        compose.onNodeWithText("Pokemon data:", substring = true).assertExists()
        compose.onNodeWithText("Privacy & diagnostics").performClick()
        compose.onNodeWithContentDescription("Close menu").performClick()
        compose.onNodeWithText("PVP IVs on the GO").assertIsDisplayed()
    }

    @Test fun autoScanIsAnExplicitSavedMenuOption() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = AutoScanSettings.enabled(context)
        try {
            compose.runOnIdle { AutoScanSettings.setEnabled(context, false) }
            compose.onNodeWithContentDescription("Menu").performClick()
            compose.onNodeWithContentDescription("Auto-scan appraisals").assertIsOff().performClick().assertIsOn()
            compose.runOnIdle { assertTrue(AutoScanSettings.enabled(context)) }
            compose.onNodeWithContentDescription("Auto-scan appraisals").performClick().assertIsOff()
        } finally { compose.runOnIdle { AutoScanSettings.setEnabled(context, previous) } }
    }

    @Test fun pendingPermissionOwnerRestoresAfterViewModelLoss() {
        var id = -1L
        lateinit var previous: PokeMogViewModel
        compose.runOnIdle {
            id = checkNotNull(OverlaySession.beginStart())
            previous = ViewModelProvider(compose.activity)[PokeMogViewModel::class.java]
            previous.pendingOverlayRequest = id
            // Simulate the lost ViewModel store seen with Android's Don't keep activities setting.
            compose.activity.viewModelStore.clear()
        }
        try {
            compose.activityRule.scenario.recreate()
            compose.runOnIdle {
                val restored = ViewModelProvider(compose.activity)[PokeMogViewModel::class.java]
                assertNotSame(previous, restored)
                assertEquals(id, restored.pendingOverlayRequest)
                assertTrue(OverlaySession.isStarting(id))
                restored.pendingOverlayRequest = null
            }
        } finally { compose.runOnIdle { OverlaySession.stopped(id) } }
    }

    @Test fun nativeOcrReadsRealS23AppraisalCrop() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repo = PokemonRepository(instrumentation.targetContext)
        val image = instrumentation.context.assets.open("s23-ultra-appraisal.png").use { BitmapFactory.decodeStream(it) }
        val scanner = ScreenshotScanner(instrumentation.targetContext)
        try {
            val result = scanner.scan(image, repo)
            assertEquals(IVs(1, 15, 14), result.ivs)
            assertTrue(result.text.contains("Attack", ignoreCase = true))
        } finally { scanner.close(); image.recycle() }
    }

    @Test fun evolutionPathsKeepBranchesAndShadowIdentity() {
        val repo = PokemonRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val eevee = repo.pokemon.first { it.id == "eevee" }
        assertEquals(9, repo.descendants(eevee).size)
        val shadow = repo.pokemon.first { it.id == "bulbasaur_shadow" }
        assertEquals(listOf("bulbasaur_shadow", "ivysaur_shadow", "venusaur_shadow"), repo.descendants(shadow).map { it.id })
        val regional = repo.pokemon.first { it.id == "slowpoke_galarian" }
        assertEquals(setOf("slowpoke_galarian", "slowbro_galarian", "slowking_galarian"), repo.descendants(regional).map { it.id }.toSet())
    }
}
