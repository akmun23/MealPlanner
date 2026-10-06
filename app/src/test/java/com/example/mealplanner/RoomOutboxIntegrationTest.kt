package com.example.mealplanner

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomOutboxIntegrationTest {

    private val context =
        ApplicationProvider.getApplicationContext<Context>()
    private val databaseName =
        "mealplanner-migration-${UUID.randomUUID()}.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationPreservesSnapshotAndOutboxSurvivesReopen() = runBlocking {
        createVersionOneDatabase()

        var database = openVersionTwoDatabase()

        val migratedSnapshot =
            database.householdSnapshotDao()
                .find("household")
        assertNotNull(migratedSnapshot)
        assertEquals("before", migratedSnapshot?.payload)

        val mutation = QueuedMutation.SetPantry(
            ingredientId = "ingredient",
            amountBase = 4.0
        )
        val pending = PendingMutationEntity(
            id = "household|${mutation.key()}",
            revision = "revision-1",
            householdId = "household",
            mutationKey = mutation.key(),
            priority = mutation.priority(),
            domainMask = mutation.domainMask(),
            payload = QueuedMutationCodec.encode(mutation),
            updatedAtEpochMs = 200L
        )

        database.localStateDao()
            .persistSnapshotAndMutations(
                snapshot = HouseholdSnapshotEntity(
                    householdId = "household",
                    payload = "after",
                    updatedAtEpochMs = 200L
                ),
                mutations = listOf(pending)
            )

        assertEquals(
            1,
            database.pendingMutationDao()
                .count("household")
        )
        database.close()

        database = openVersionTwoDatabase()

        assertEquals(
            "after",
            database.householdSnapshotDao()
                .find("household")
                ?.payload
        )
        val reopenedPending =
            database.pendingMutationDao()
                .next("household")
        assertEquals("revision-1", reopenedPending?.revision)
        assertEquals(
            mutation,
            reopenedPending?.payload?.let(
                QueuedMutationCodec::decode
            )
        )

        database.close()
    }

    @Test
    fun replacingSameMutationKeyKeepsOnlyLatestRevision() = runBlocking {
        val database = openVersionTwoDatabase()

        val first = pendingPantry(
            revision = "first",
            amount = 1.0,
            timestamp = 1L
        )
        val second = pendingPantry(
            revision = "second",
            amount = 2.0,
            timestamp = 2L
        )

        database.localStateDao()
            .persistSnapshotAndMutations(
                snapshot = HouseholdSnapshotEntity(
                    householdId = "household",
                    payload = "one",
                    updatedAtEpochMs = 1L
                ),
                mutations = listOf(first)
            )
        database.localStateDao()
            .persistSnapshotAndMutations(
                snapshot = HouseholdSnapshotEntity(
                    householdId = "household",
                    payload = "two",
                    updatedAtEpochMs = 2L
                ),
                mutations = listOf(second)
            )

        assertEquals(
            1,
            database.pendingMutationDao()
                .count("household")
        )
        assertEquals(
            "second",
            database.pendingMutationDao()
                .next("household")
                ?.revision
        )

        database.close()
    }

    private fun createVersionOneDatabase() {
        val sqlite = context.openOrCreateDatabase(
            databaseName,
            Context.MODE_PRIVATE,
            null
        )
        sqlite.execSQL(
            """
            create table household_snapshots (
                household_id text not null primary key,
                payload text not null,
                updated_at_epoch_ms integer not null
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            insert into household_snapshots(
                household_id,
                payload,
                updated_at_epoch_ms
            ) values('household', 'before', 100)
            """.trimIndent()
        )
        sqlite.version = 1
        sqlite.close()
    }

    private fun openVersionTwoDatabase(): MealPlannerDatabase =
        Room.databaseBuilder(
            context,
            MealPlannerDatabase::class.java,
            databaseName
        )
            .addMigrations(
                MealPlannerDatabaseProvider.MIGRATION_1_2
            )
            .allowMainThreadQueries()
            .build()
            .also {
                it.openHelper.writableDatabase
            }

    private fun pendingPantry(
        revision: String,
        amount: Double,
        timestamp: Long
    ): PendingMutationEntity {
        val mutation = QueuedMutation.SetPantry(
            ingredientId = "ingredient",
            amountBase = amount
        )

        return PendingMutationEntity(
            id = "household|${mutation.key()}",
            revision = revision,
            householdId = "household",
            mutationKey = mutation.key(),
            priority = mutation.priority(),
            domainMask = mutation.domainMask(),
            payload = QueuedMutationCodec.encode(mutation),
            updatedAtEpochMs = timestamp
        )
    }
}
