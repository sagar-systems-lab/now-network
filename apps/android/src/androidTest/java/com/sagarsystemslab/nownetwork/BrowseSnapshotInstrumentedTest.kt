package com.sagarsystemslab.nownetwork

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sagarsystemslab.nownetwork.config.BrowseAreaConfig
import com.sagarsystemslab.nownetwork.experience.BrowseContextStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowseSnapshotInstrumentedTest {
    @Test
    fun savedOrderSurvivesReloadAndStaysBoundToActorAreaAndQuery() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val area = BrowseAreaConfig("Test area", 12.3, 55.0, 3000)
        val kind = "test:${java.util.UUID.randomUUID()}:payout:parking"
        val store = BrowseContextStore(context, area)
        val ids = linkedSetOf("high-estimate", "low-estimate", "other-token")
        store.saveSnapshot(kind, ids, "actor-a", area)
        val restored = BrowseContextStore(context, area)
        assertEquals(ids.toList(), restored.snapshot(kind, "actor-a", area).toList())
        assertTrue(restored.snapshot(kind, "actor-b", area).isEmpty())
        assertTrue(restored.snapshot("$kind-other", "actor-a", area).isEmpty())
        assertTrue(restored.snapshot(kind, "actor-a", area.copy(longitude = 56.0)).isEmpty())
        store.saveSnapshot(kind, emptySet(), "actor-a", area)
    }
}
