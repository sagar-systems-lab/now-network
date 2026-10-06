package com.sagarsystemslab.nownetwork

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in capture pass; scrolling semantics cannot trigger the fixture's real action callbacks. */
@RunWith(AndroidJUnit4::class)
class VisualScrollCaptureInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun captureScrollableContent() {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("visualCapture") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = File(context.getExternalFilesDir(null), "visual-scroll").apply { mkdirs() }
        val screens = args.getString("visualScreens", "now,earn-empty,profile,funding,claim,verified,receipt,settings")!!.split(',')
        for (dark in listOf(false, true)) for (screen in screens) {
            val intent = Intent(context, VisualSnapshotActivity::class.java)
                .putExtra("screen", screen).putExtra("dark", dark).putExtra("motion", false)
            ActivityScenario.launch<VisualSnapshotActivity>(intent).use {
                compose.onNodeWithTag("snapshot-$screen").assertIsDisplayed()
                val vertical = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
                val nodes = compose.onAllNodes(vertical).fetchSemanticsNodes()
                if (nodes.isEmpty()) return@use
                val target = nodes.maxBy { it.boundsInRoot.height }
                val scrollable = compose.onNode(SemanticsMatcher("scroll container") { it.id == target.id })
                var previous = -1f
                for (page in 1..8) {
                    val range = scrollable.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                    val before = range.value()
                    if (before >= range.maxValue() || before == previous) break
                    previous = before
                    scrollable.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                        scroll(0f, target.boundsInRoot.height * .75f)
                    }
                    compose.waitForIdle()
                    instrumentation.waitForIdleSync()
                    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    File(output, "$screen-${if (dark) "dark" else "light"}-scroll$page.png").outputStream().use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    }
                    bitmap.recycle()
                }
            }
        }
    }
}
