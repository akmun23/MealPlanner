package com.example.mealplanner

internal enum class SyncIndicator {
    IDLE,
    SAVING,
    SAVED,
    ERROR
}

internal data class SyncMutationKey(
    val domain: CloudSyncDomain,
    val entityId: String
)

internal class SyncGenerationTracker {
    private val generations = mutableMapOf<CloudSyncDomain, Long>()
    private val pendingKeys = mutableMapOf<SyncMutationKey, Int>()

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

    fun beginPending(keys: Set<SyncMutationKey>) {
        keys.forEach { key ->
            pendingKeys[key] = (pendingKeys[key] ?: 0) + 1
        }
    }

    fun endPending(keys: Set<SyncMutationKey>) {
        keys.forEach { key ->
            val next = (pendingKeys[key] ?: 0) - 1
            if (next > 0) {
                pendingKeys[key] = next
            } else {
                pendingKeys.remove(key)
            }
        }
    }

    fun isPending(key: SyncMutationKey): Boolean =
        (pendingKeys[key] ?: 0) > 0
}
