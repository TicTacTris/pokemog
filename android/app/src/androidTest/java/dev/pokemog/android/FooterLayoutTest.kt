package dev.pokemog.android

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class FooterLayoutTest {
    private fun onMain(block: (Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { block(instrumentation.targetContext) }
    }

    private fun context(base: Context, scale: Float): Context {
        val config = Configuration(base.resources.configuration).apply { fontScale = scale }
        return ContextThemeWrapper(base.createConfigurationContext(config), android.R.style.Theme_Material_Light_NoActionBar)
    }

    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun spec(value: Int, mode: Int) = View.MeasureSpec.makeMeasureSpec(value, mode)
    private fun measure(view: View, width: Int, height: Int, widthMode: Int = View.MeasureSpec.EXACTLY,
        heightMode: Int = View.MeasureSpec.AT_MOST) {
        view.measure(spec(width, widthMode), spec(height, heightMode))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun footer(context: Context, labels: List<String> = listOf("Rescan", "Collapse")) = OverlayActionFooter(context, context.resources.displayMetrics.density).apply {
        for (label in labels) {
            addView(Button(context).apply {
                text = label
                typeface = retroTypeface(context)
                textSize = 16f
                setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            })
        }
    }

    private fun assertInside(parent: ViewGroup) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child.visibility == View.GONE) continue
            assertTrue("left: ${child.left}", child.left >= 0)
            assertTrue("top: ${child.top}", child.top >= 0)
            assertTrue("right: ${child.right} > ${parent.width}", child.right <= parent.width)
            assertTrue("bottom: ${child.bottom} > ${parent.height}", child.bottom <= parent.height)
            assertEquals(child.measuredWidth, child.width)
            assertEquals(child.measuredHeight, child.height)
        }
    }

    private fun assertActions(footer: OverlayActionFooter, minimum: Int, naturalText: Boolean = true) {
        assertInside(footer)
        assertFalse(footer.isBaselineAligned)
        val first = footer.getChildAt(0) as Button
        val second = footer.getChildAt(1) as Button
        assertTrue(first.right <= second.left || first.bottom <= second.top)
        for (button in listOf(first, second)) {
            assertTrue("touch target ${button.height} < $minimum", button.height >= minimum)
            if (naturalText) {
                assertTrue(button.includeFontPadding)
                assertEquals(TextView.AUTO_SIZE_TEXT_TYPE_NONE, button.autoSizeTextType)
                assertTrue("glyph layout clipped vertically", button.layout.height <=
                    button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                assertEquals(button.text.length, button.layout.getLineEnd(button.layout.lineCount - 1))
            }
        }
    }

    @Test fun portraitLandscapeAndLargeFontsReserveNaturalFooterBeforeBody() = onMain { base ->
        for (scale in listOf(1f, 1.5f, 2f)) {
            val ctx = context(base, scale)
            for ((widthDp, heightDp) in listOf(360 to 400, 640 to 200)) {
                val actions = footer(ctx)
                val body = ScrollView(ctx).apply {
                    addView(TextView(ctx).apply {
                        text = "Long result body wraps at the current pixel font size. ".repeat(150)
                        typeface = retroTypeface(ctx)
                        textSize = 16f
                    })
                }
                val card = OverlayCardLayout(ctx, ctx.dp(heightDp)).apply {
                    setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
                    addView(body)
                    addView(actions)
                }
                measure(card, ctx.dp(widthDp), ctx.dp(heightDp))
                assertEquals(ctx.dp(heightDp), card.height)
                assertInside(card)
                assertActions(actions, ctx.dp(48))
                assertTrue(body.bottom <= actions.top)
                assertEquals(card.height - card.paddingBottom, actions.bottom)
                assertTrue(body.getChildAt(0).height > body.height)
            }
        }
    }

    @Test fun naturalLabelWidthChoosesStackAndCanReturnToRow() = onMain { base ->
        val ctx = context(base, 2f)
        val actions = footer(ctx)
        measure(actions, 0, 0, View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val naturalWidth = actions.width
        assertEquals(LinearLayout.HORIZONTAL, actions.orientation)
        measure(actions, naturalWidth - 1, ctx.dp(600))
        assertEquals(LinearLayout.VERTICAL, actions.orientation)
        assertActions(actions, ctx.dp(48))
        measure(actions, naturalWidth, ctx.dp(600))
        assertEquals(LinearLayout.HORIZONTAL, actions.orientation)
        assertActions(actions, ctx.dp(48))
        measure(actions, ctx.dp(110), ctx.dp(600))
        assertEquals(LinearLayout.VERTICAL, actions.orientation)
        assertActions(actions, ctx.dp(48))
        assertTrue((actions.getChildAt(1) as Button).lineCount > 1)
    }

    @Test fun actualServiceWidthsAndLandscapeHeightsKeepActionGlyphsVisible() = onMain { base ->
        for (scale in listOf(1f, 1.5f, 2f)) {
            val ctx = context(base, scale)
            for ((width, labels) in listOf(380 to listOf("Rescan", "Collapse"), 280 to listOf("Stop", "Back"))) {
                val actions = footer(ctx, labels)
                for (i in 0 until actions.childCount) (actions.getChildAt(i) as Button).apply {
                    textSize = 14f
                    isAllCaps = false
                    setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
                }
                val body = ScrollView(ctx).apply { addView(TextView(ctx).apply { text = "Results ".repeat(200) }) }
                val card = OverlayCardLayout(ctx, ctx.dp(176)).apply {
                    setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
                    addView(body); addView(actions)
                }
                measure(card, ctx.dp(width), ctx.dp(176))
                assertInside(card)
                assertActions(actions, ctx.dp(48))
                assertTrue(body.bottom <= actions.top)
                assertEquals(card.height - card.paddingBottom, actions.bottom)
            }
        }
    }

    @Test fun shortContentIsNaturalButExactHeightPinsFooterToBottom() = onMain { base ->
        val ctx = context(base, 1f)
        val body = ScrollView(ctx).apply { addView(TextView(ctx).apply { text = "Short"; typeface = retroTypeface(ctx) }) }
        val actions = footer(ctx)
        val card = OverlayCardLayout(ctx, ctx.dp(400)).apply {
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
            addView(body)
            addView(actions)
        }
        measure(card, ctx.dp(360), ctx.dp(600))
        val naturalHeight = card.height
        assertTrue(naturalHeight < ctx.dp(400))
        assertEquals(body.bottom, actions.top)
        measure(card, ctx.dp(360), ctx.dp(400), heightMode = View.MeasureSpec.EXACTLY)
        assertEquals(ctx.dp(400), card.height)
        assertEquals(card.height - card.paddingBottom, actions.bottom)
        assertInside(card)
        assertActions(actions, ctx.dp(48))
        measure(card, ctx.dp(360), 0, heightMode = View.MeasureSpec.UNSPECIFIED)
        assertEquals(naturalHeight, card.height)
    }

    @Test fun tinyHeightCollapsesBodyAndNeverOverlaysActions() = onMain { base ->
        val ctx = context(base, 2f)
        val actions = footer(ctx)
        val body = ScrollView(ctx).apply { addView(TextView(ctx).apply { text = "Body ".repeat(200) }) }
        val card = OverlayCardLayout(ctx, ctx.dp(400)).apply {
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
            addView(body)
            addView(actions)
        }
        for (heightDp in listOf(0, 1, 8, 16, 24, 40, 80)) {
            measure(card, ctx.dp(180), ctx.dp(heightDp))
            assertTrue(card.height <= ctx.dp(heightDp))
            assertEquals(0, body.height)
            assertInside(card)
            assertActions(actions, 0, naturalText = false)
        }
    }

    @Test fun paddingMarginsAndAtMostWidthStayInsideMeasuredBounds() = onMain { base ->
        val ctx = context(base, 1.5f)
        val actions = footer(ctx).apply {
            setPadding(ctx.dp(7), ctx.dp(9), ctx.dp(11), ctx.dp(13))
        }
        for (index in 0 until actions.childCount) {
            actions.getChildAt(index).layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(ctx.dp(3), ctx.dp(4), ctx.dp(5), ctx.dp(6))
            }
        }
        val card = OverlayCardLayout(ctx, ctx.dp(400)).apply {
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(14), ctx.dp(10))
            addView(ScrollView(ctx).apply { addView(TextView(ctx).apply { text = "Body" }) },
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(2, 3, 4, 5) })
            addView(actions, LinearLayout.LayoutParams(-1, -2).apply { setMargins(6, 7, 8, 9) })
        }
        measure(card, ctx.dp(300), ctx.dp(500), View.MeasureSpec.AT_MOST)
        assertTrue(card.width <= ctx.dp(300))
        assertTrue(card.height <= ctx.dp(400))
        assertInside(card)
        assertActions(actions, ctx.dp(48))
        assertEquals(card.paddingLeft + 6, actions.left)
        assertEquals(card.height - card.paddingBottom - 9, actions.bottom)
        assertTrue(actions.getChildAt(0).left >= actions.paddingLeft + ctx.dp(3))
        assertTrue(actions.getChildAt(1).bottom <= actions.height - actions.paddingBottom - ctx.dp(6))
    }

    @Test fun constrainedStackKeepsMinimumTargetsWhenTheyFit() = onMain { base ->
        val ctx = context(base, 2f)
        val actions = footer(ctx)
        val minimumHeight = 2 * ctx.dp(48) + 3 * ctx.dp(8)
        measure(actions, ctx.dp(130), minimumHeight)
        assertEquals(LinearLayout.VERTICAL, actions.orientation)
        assertActions(actions, ctx.dp(48), naturalText = false)
        assertEquals(minimumHeight, actions.height)
    }
}
