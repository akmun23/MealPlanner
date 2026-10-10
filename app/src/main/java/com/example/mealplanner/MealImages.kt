package com.example.mealplanner

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours
import androidx.core.content.edit
import kotlin.time.Duration.Companion.milliseconds

/**
 * An image reference is a cheap, stable value carried in Room snapshots.
 * Loading recipes never needs to sign 80 image URLs over the network.
 * Only the thumbnails actually composed by a lazy list resolve their URLs.
 */
internal object MealImageReference {
    private const val PREFIX = "mealplanner-image://"

    fun forPath(path: String): String = PREFIX + Uri.encode(path)

    fun pathFrom(reference: String): String? =
        reference.takeIf { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)
            ?.let(Uri::decode)
}

/**
 * Signed links expire, but image bytes do not. Cache links privately per
 * installation for up to 23h, and use stable Coil disk keys for image bytes.
 * Concurrent signing is capped so a quick scroll cannot flood Supabase.
 */
internal class SignedMealImageUrls private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "mealplanner_signed_images",
        Context.MODE_PRIVATE
    )
    private val requestSlots = Semaphore(4)
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun resolve(reference: String): String {
        val path = MealImageReference.pathFrom(reference) ?: return reference
        return locks.getOrPut(path) { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            val previous = preferences.getString("url:$path", null)
            val expires = preferences.getLong("expires:$path", 0L)
            if (previous != null && expires > now) return@withLock previous

            try {
                val signed = requestSlots.withPermit {
                    SupabaseProvider.client.storage["meal-images"]
                        .createSignedUrl(path, 24.hours)
                }
                preferences.edit {
                        putString("url:$path", signed)
                            .putLong(
                                "expires:$path",
                                now + 23L * 60L * 60L * 1000L
                            )
                }
                signed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // An expired signed link can still index a disk-cached Coil
                // image when offline. The bytes are keyed by the image path.
                previous ?: throw error
            }
        }
    }

    fun invalidate(path: String) {
        preferences.edit {
                remove("url:$path")
                .remove("expires:$path")
        }
        locks.remove(path)
    }

    companion object {
        @Volatile
        private var instance: SignedMealImageUrls? = null

        fun get(context: Context): SignedMealImageUrls =
            instance ?: synchronized(this) {
                instance ?: SignedMealImageUrls(context).also { instance = it }
            }
    }
}

/**
 * Warm image thumbnails after startup, one at a time so foreground scrolling
 * and cloud data reads are not crowded out by photo downloads. Coil holds
 * the compressed bytes in the shared 256 MB disk cache for later tabs.
 */
internal object MealImagePrefetcher {
    suspend fun warm(context: Context, references: List<String>) {
        val loader = context.imageLoader
        val resolver = SignedMealImageUrls.get(context)
        withContext(Dispatchers.IO) {
            references.asSequence()
                .filter { it.isNotBlank() }
                .distinct()
                .take(150)
                .forEach { reference ->
                    try {
                        val url = resolver.resolve(reference)
                        val request = ImageRequest.Builder(context)
                            .data(url)
                            .diskCacheKey(reference)
                            .memoryCacheKey("thumbnail:$reference")
                            .size(192, 192)
                            .crossfade(false)
                            .build()
                        loader.execute(request)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Preview failure must not affect meal data or scrolling.
                    }
                    delay(100L.milliseconds)
                }
        }
    }
}

@Composable
internal fun MealImage(
    imageUri: String,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    thumbnail: Boolean = false,
    fallback: String? = null
) {
    val context = LocalContext.current
    val resolver = remember(context.applicationContext) {
        SignedMealImageUrls.get(context.applicationContext)
    }
    val resolved by produceState(
        initialValue = if (MealImageReference.pathFrom(imageUri) != null) {
            null
        } else {
            imageUri
        },
        key1 = imageUri
    ) {
        value = try {
            resolver.resolve(imageUri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    val request = remember(context, imageUri, resolved, thumbnail) {
        resolved?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .diskCacheKey(imageUri)
                .memoryCacheKey(if (thumbnail) "thumbnail:$imageUri" else "full:$imageUri")
                .apply {
                    if (thumbnail) size(192, 192)
                }
                // Crossfades on dozens of rows contribute to scroll jank.
                .crossfade(false)
                .build()
        }
    }

    if (request != null && thumbnail && !fallback.isNullOrBlank()) {
        // Broken/revoked remote product URLs must show the same-sized
        // placeholder instead of leaving a visually empty pantry tile.
        SubcomposeAsyncImage(
            model = request,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
            loading = {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(fallback, style = MaterialTheme.typography.titleLarge)
                }
            },
            error = {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(fallback, style = MaterialTheme.typography.titleLarge)
                }
            }
        )
    } else if (request != null) {
        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Box(
            modifier = modifier.background(
                MaterialTheme.colorScheme.primaryContainer
            ),
            contentAlignment = Alignment.Center
        ) {
            if (!fallback.isNullOrBlank()) {
                Text(
                    fallback,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }
    }
}
