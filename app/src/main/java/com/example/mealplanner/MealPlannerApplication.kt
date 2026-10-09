package com.example.mealplanner

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Keep decoded thumbnails in memory and compressed image responses on disk.
 * The limit intentionally favors smooth list scrolling over a tiny app cache;
 * Android can reclaim cache data without affecting household/Room records.
 */
class MealPlannerApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.30)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("meal-image-cache"))
                    .maxSizeBytes(256L * 1024L * 1024L)
                    .build()
            }
            .crossfade(false)
            .build()
}
