package com.example.mealplanner

internal enum class SyncStatus {
    IDLE,
    SAVING,
    SAVED,
    ERROR
}

internal sealed interface CloudRealtimeChange {
    val domain: CloudSyncDomain

    data class Domain(
        override val domain: CloudSyncDomain
    ) : CloudRealtimeChange

    data class Pantry(
        val ingredientId: String,
        val amountBase: Double?
    ) : CloudRealtimeChange {
        override val domain = CloudSyncDomain.PANTRY
    }

    data class ShoppingChecked(
        val ingredientId: String,
        val checked: Boolean?
    ) : CloudRealtimeChange {
        override val domain = CloudSyncDomain.SHOPPING
    }

    data class ShoppingIncluded(
        val ingredientId: String,
        val included: Boolean?
    ) : CloudRealtimeChange {
        override val domain = CloudSyncDomain.SHOPPING
    }
}
