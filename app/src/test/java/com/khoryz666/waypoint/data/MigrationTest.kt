package com.khoryz666.waypoint.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every exported schema version opens and migrates to the current one with a schema identical
 * to the entities (spec 10.3). Add a case per new version; the loop covers the chain.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val current = 1

    @Test
    fun `every version from 1 migrates to the current schema`() {
        for (from in 1..current) {
            val name = "migration-$from.db"
            helper.createDatabase(name, from).use { db ->
                db.execSQL("INSERT INTO tracks (domain, title, status, initialState, goal, goalCriteria, planNotes, rollingSummary, logsSinceSummary, githubRepos, createdAt, updatedAt) VALUES ('READING', 'A book', 'ACTIVE', '', 'Finish it', '[]', '', '', 0, '[]', 1, 1)")
            }
            helper.runMigrationsAndValidate(name, current, true, *AppDatabase.MIGRATIONS).use { db ->
                db.query("SELECT title FROM tracks").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("A book", cursor.getString(0))
                }
            }
        }
    }
}
