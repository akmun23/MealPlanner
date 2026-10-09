package com.example.mealplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MealImageReferenceTest {
    @Test
    fun storagePathRoundTripsWithoutSigningAtDataLoadTime() {
        val path = "my-household/ingredients/rød kartoffel-1.jpg"
        val reference = MealImageReference.forPath(path)
        assertTrue(reference.startsWith("mealplanner-image://"))
        assertEquals(path, MealImageReference.pathFrom(reference))
    }

    @Test
    fun existingLocalAndSignedUrlsStayUntouched() {
        assertNull(MealImageReference.pathFrom("content://photos/42"))
        assertNull(MealImageReference.pathFrom("https://example.test/old-signed.jpg"))
        assertNull(MealImageReference.pathFrom(""))
    }

    @Test
    fun cachedSnapshotPreservesStableImageReferences() {
        val reference = MealImageReference.forPath("household/recipes/dinner.jpg")
        val snapshot = CloudSnapshot.EMPTY.copy(
            recipes = listOf(
                Recipe(
                    id = "dinner",
                    name = "Test",
                    ingredients = emptyList(),
                    imageUri = reference,
                    imagePath = "household/recipes/dinner.jpg"
                )
            )
        )
        val restored = RoomSnapshotCodec.decode(RoomSnapshotCodec.encode(snapshot))
        assertEquals(reference, restored!!.recipes.single().imageUri)
        assertEquals("household/recipes/dinner.jpg", restored.recipes.single().imagePath)
    }
}
