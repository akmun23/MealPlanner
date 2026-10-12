package com.example.mealplanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Emits whether the app's default network is known to reach the internet.
 * Registering a callback avoids polling Supabase or waking the UI in a loop.
 * The callback is always unregistered when the household scope is canceled.
 */
internal class NetworkConnectivityMonitor(context: Context) {
    private val manager = context.applicationContext.getSystemService(
        Context.CONNECTIVITY_SERVICE
    ) as ConnectivityManager

    val connected: Flow<Boolean> = callbackFlow {
        fun isOnline(capabilities: NetworkCapabilities?): Boolean =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                trySend(isOnline(capabilities))
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }

        manager.registerDefaultNetworkCallback(callback)
        trySend(isOnline(manager.getNetworkCapabilities(manager.activeNetwork)))
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
