package app.openread.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** What kind of connection the phone is on right now. */
class NetworkState(private val context: Context) {
    /**
     * True on a connection that does not count data (Wi-Fi, Ethernet). False on mobile data, on a hotspot the system treats as
     * metered, and when it cannot be told: the heavy background work is then only done after asking.
     */
    fun unmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching {
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        }.getOrDefault(false)
    }
}
