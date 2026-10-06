package com.example.mealplanner

internal enum class SyncIndicator {
    IDLE,
    SAVING,
    SAVED,
    PENDING,
    ERROR,
    OFFLINE
}

internal class SyncGenerationTracker {
    private val generations = mutableMapOf<CloudSyncDomain, Long>()

    fun markLocalChange(
        domains: Set<CloudSyncDomain>
    ): Map<CloudSyncDomain, Long> =
        domains.associateWith { domain ->
            val next = (generations[domain] ?: 0L) + 1L
            generations[domain] = next
            next
        }

    fun generation(domain: CloudSyncDomain): Long =
        generations[domain] ?: 0L

    fun isCurrent(
        snapshot: Map<CloudSyncDomain, Long>
    ): Boolean =
        snapshot.all { (domain, generation) ->
            generation(domain) == generation
        }

}
}
