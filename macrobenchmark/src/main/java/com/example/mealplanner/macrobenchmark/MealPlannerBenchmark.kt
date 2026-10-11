package com.example.mealplanner.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run on a physical device with a release-like non-debuggable app build.
 * See macrobenchmark/README.md before installing over an existing app.
 */
@RunWith(AndroidJUnit4::class)
class MealPlannerBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 8,
        setupBlock = { pressHome() },
        measureBlock = { startActivityAndWait() }
    )

    @Test
    fun tabNavigationFrameTiming() = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = 8,
        setupBlock = { startActivityAndWait() },
        measureBlock = {
            val device = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation()
            )
            // Run after signing in; the benchmark APK replaces the installed
            // app and the login state may need to be established again.
            check(device.wait(Until.hasObject(By.text("Opskrifter")), 10_000)) {
                "Sign in and open your household before benchmarking tabs."
            }
            device.findObject(By.text("Opskrifter")).click()
            device.waitForIdle()
            device.findObject(By.text("Lager")).click()
            device.waitForIdle()
            device.findObject(By.text("Indkøb")).click()
            device.waitForIdle()
            device.findObject(By.text("Madplan")).click()
            device.waitForIdle()
        }
    )

    /**
     * Separate measurements for the two long lists, instead of only testing
     * tab transitions. Use a populated household with representative photos
     * so these tests reproduce the scrolling pauses reported on devices.
     */
    @Test
    fun weekScrollFrameTiming() = measureListScroll("Madplan")

    @Test
    fun shoppingScrollFrameTiming() = measureListScroll("Indkøb")

    @Test
    fun pantryScrollFrameTiming() = measureListScroll("Lager")

    @Test
    fun recipesScrollFrameTiming() = measureListScroll("Opskrifter")

    private fun measureListScroll(tabName: String) = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = 8,
        setupBlock = {
            startActivityAndWait()
            val device = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation()
            )
            check(device.wait(Until.hasObject(By.text(tabName)), 10_000)) {
                "Sign in and prepare a household to benchmark $tabName."
            }
            device.findObject(By.text(tabName)).click()
            device.waitForIdle()
        },
        measureBlock = {
            val device = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation()
            )
            val middleX = device.displayWidth / 2
            val topY = (device.displayHeight * 0.32f).toInt()
            val bottomY = (device.displayHeight * 0.73f).toInt()
            repeat(4) {
                device.swipe(middleX, bottomY, middleX, topY, 22)
                device.swipe(middleX, topY, middleX, bottomY, 22)
            }
            device.waitForIdle()
        }
    )

    companion object {
        private const val PACKAGE_NAME = "com.mealplan.mealplanner"
    }
}
