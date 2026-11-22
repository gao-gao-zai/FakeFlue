package com.flue.launcher.surface

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flue.launcher.ui.style.FlueSlider
import com.flue.launcher.ui.style.FlueSwitch
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.ui.theme.WatchLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Render the real controls and exercise their semantics and touch handlers. */
@RunWith(AndroidJUnit4::class)
class AppleControlsAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sliderExposesItsRangeClampsRequestsAndRejectsInvalidValues() {
        var value by mutableFloatStateOf(25f)
        var changes = 0
        var finishes = 0
        compose.setContent {
            Controls {
                FlueSlider(value, { value = it; changes++ }, Modifier.fillMaxWidth().testTag("slider"),
                    valueRange = 0f..100f, onValueChangeFinished = { finishes++ })
            }
        }
        val slider = compose.onNodeWithTag("slider")
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(25f, 0f..100f, 0)))
        assertTrue(slider.requestProgress(150f))
        compose.runOnIdle {
            assertEquals(100f, value)
            assertEquals(1, changes)
            assertEquals(1, finishes)
        }
        assertFalse(slider.requestProgress(100f))
        assertTrue(slider.requestProgress(-50f))
        assertFalse(slider.requestProgress(Float.NaN))
        assertFalse(slider.requestProgress(Float.POSITIVE_INFINITY))
        assertFalse(slider.requestProgress(Float.NEGATIVE_INFINITY))
        compose.runOnIdle {
            assertEquals(0f, value)
            assertEquals(2, changes)
            assertEquals(2, finishes)
        }
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(0f, 0f..100f, 0)))
    }

    @Test
    fun accessibilityAndPointerChangesUseTheSameSteppedStops() {
        var value by mutableFloatStateOf(20f)
        var finishes = 0
        compose.setContent {
            Controls {
                FlueSlider(value, { value = it }, Modifier.fillMaxWidth().testTag("slider"),
                    valueRange = 0f..100f, steps = 4, onValueChangeFinished = { finishes++ })
            }
        }
        val slider = compose.onNodeWithTag("slider")
        assertTrue(slider.requestProgress(31f))
        compose.runOnIdle {
            assertEquals(40f, value)
            assertEquals(1, finishes)
        }
        slider.performTouchInput {
            swipe(Offset(width * 0.25f, center.y), Offset(width * 0.75f, center.y), 400)
        }
        compose.waitForIdle()
        var current = -1f
        compose.runOnIdle {
            current = value
            assertTrue(value > 40f)
            assertTrue(value in listOf(0f, 20f, 40f, 60f, 80f, 100f))
            assertEquals(2, finishes)
        }
        assertFalse(slider.requestProgress(current - 0.1f))
        compose.runOnIdle { assertEquals(2, finishes) }
    }

    @Test
    fun disabledSliderRejectsActionsAndDraggingThenCanBeReenabled() {
        var value by mutableFloatStateOf(40f)
        var enabled by mutableStateOf(false)
        var changes = 0
        var finishes = 0
        compose.setContent {
            Controls {
                FlueSlider(value, { value = it; changes++ }, Modifier.fillMaxWidth().testTag("slider"),
                    valueRange = 0f..100f, enabled = enabled, onValueChangeFinished = { finishes++ })
            }
        }
        val slider = compose.onNodeWithTag("slider")
        slider.assertIsNotEnabled()
        assertFalse(slider.requestProgress(80f))
        slider.performTouchInput { swipe(Offset(width * 0.2f, center.y), Offset(width * 0.8f, center.y), 400) }
        compose.runOnIdle {
            assertEquals(40f, value)
            assertEquals(0, changes)
            assertEquals(0, finishes)
            enabled = true
        }
        slider.assertIsEnabled()
        assertTrue(slider.requestProgress(80f))
        compose.runOnIdle {
            assertEquals(80f, value)
            assertEquals(1, changes)
            assertEquals(1, finishes)
        }
    }

    @Test
    fun switchExposesStateTogglesOnceAndHonorsDisabled() {
        var checked by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var changes = 0
        var parentClicks = 0
        compose.setContent {
            Controls {
                Box(Modifier.clickable(enabled = enabled) { parentClicks++; checked = !checked }.padding(20.dp)) {
                    FlueSwitch(checked, { checked = it; changes++ }, Modifier.testTag("switch"), enabled)
                }
            }
        }
        val toggle = compose.onNodeWithTag("switch", useUnmergedTree = true)
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOff()
        toggle.performClick()
        toggle.assertIsOn()
        toggle.performTouchInput { click(center) }
        toggle.assertIsOff()
        compose.runOnIdle {
            assertEquals(2, changes)
            assertEquals(0, parentClicks)
            enabled = false
        }
        toggle.assertIsNotEnabled()
        assertFalse(SemanticsActions.OnClick in toggle.fetchSemanticsNode().config)
        toggle.performTouchInput { click(center) }
        compose.runOnIdle {
            assertFalse(checked)
            assertEquals(2, changes)
            assertEquals(0, parentClicks)
        }
    }

    private fun SemanticsNodeInteraction.requestProgress(value: Float): Boolean {
        var result = false
        performSemanticsAction(SemanticsActions.SetProgress) { result = it(value) }
        compose.waitForIdle()
        return result
    }

    @Composable
    private fun Controls(content: @Composable () -> Unit) {
        WatchLauncherTheme(uiStyle = UiStyle.APPLE_WATCH) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
        }
    }
}
