package com.sagarsystemslab.nownetwork.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun oldestExportedSchemaOpensWithCurrentDatabaseAndPreservesState() = runBlocking {
        val context = instrumentation.targetContext
        val databaseName = "now-migration-test.db"
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        context.deleteDatabase(databaseName)

        val schema = instrumentation.context.assets
            .open(SCHEMA_ASSET)
            .bufferedReader()
            .use { JSONObject(it.readText()) }
            .getJSONObject("database")

        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { legacy ->
            createSchema(legacy, schema)
            legacy.version = schema.getInt("version")
            legacy.execSQL(
                """
                INSERT INTO cached_states(
                    state_id,
                    revision,
                    payload_json,
                    observed_at_ms,
                    aging_at_ms,
                    fresh_until_ms,
                    cached_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    "migration-sentinel",
                    1L,
                    """{"spaces":4}""",
                    1_000L,
                    2_000L,
                    3_000L,
                    4_000L,
                ),
            )
        }

        val current = Room.databaseBuilder(
            context,
            NowDatabase::class.java,
            databaseName,
        )
            .addMigrations(*NowDatabaseMigrations.ALL)
            .build()

        try {
            val stored = requireNotNull(current.cachedStateDao().get("migration-sentinel"))
            assertEquals(1L, stored.revision)
            assertEquals("""{"spaces":4}""", stored.payloadJson)
        } finally {
            current.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun createSchema(database: SQLiteDatabase, schema: JSONObject) {
        val entities = schema.getJSONArray("entities")
        for (entityIndex in 0 until entities.length()) {
            val entity = entities.getJSONObject(entityIndex)
            val tableName = entity.getString("tableName")
            database.execSQL(
                entity.getString("createSql").replace("\${TABLE_NAME}", tableName),
            )

            val indices = entity.optJSONArray("indices") ?: continue
            for (index in 0 until indices.length()) {
                database.execSQL(
                    indices.getJSONObject(index)
                        .getString("createSql")
                        .replace("\${TABLE_NAME}", tableName),
                )
            }
        }

        val setupQueries = schema.getJSONArray("setupQueries")
        for (queryIndex in 0 until setupQueries.length()) {
            database.execSQL(setupQueries.getString(queryIndex))
        }
    }

    private companion object {
        const val SCHEMA_ASSET =
            "com.sagarsystemslab.nownetwork.data.local.NowDatabase/1.json"
    }
}
