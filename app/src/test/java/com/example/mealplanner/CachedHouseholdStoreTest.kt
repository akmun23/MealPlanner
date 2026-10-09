package com.example.mealplanner

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CachedHouseholdStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun persistedHouseholdIsOnlyVisibleToItsOwnAccount() {
        val store = CachedHouseholdStore(context)
        val userA = UUID.randomUUID().toString()
        val userB = UUID.randomUUID().toString()
        val household = HouseholdDto("house", "Hjem", "JOIN1234")

        store.put(userA, household)
        assertEquals(household, CachedHouseholdStore(context).get(userA))
        assertNull(store.get(userB))
        store.remove(userA)
        assertNull(store.get(userA))
    }

    @Test
    fun corruptedCachedMetadataCannotCrashStartup() {
        val store = CachedHouseholdStore(context)
        val user = UUID.randomUUID().toString()
        context.getSharedPreferences("mealplanner_last_household", Context.MODE_PRIVATE)
            .edit().putString("account-household-$user", "{broken-json").commit()
        assertNull(store.get(user))
    }
}
