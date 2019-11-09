package dev.akampf.fileshare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log


private const val LOGGING_TAG: String = "own_logs"

/**
 * A BroadcastReceiver that handles / notifies of important Wi-Fi p2p events.
 */
class WiFiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: MainActivity
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(LOGGING_TAG,"onreceive broadcast receiver")
        // TODO look up why example set type to not optional string without null check but ide says intent.action is of type String?
        // check if intent.action is not null because it is of type "String?"
        if (intent.action != null) {
            return
        }
        // now we are sure it is not null so we cast String? to not optional type String
        val action: String = intent.action as String
        when (action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                // Check to see if Wi-Fi is enabled and notify appropriate activity
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                when (state) {
                    WifiP2pManager.WIFI_P2P_STATE_ENABLED -> {
                        // Wifi P2P is enabled
                        Log.i(LOGGING_TAG ,"wifi direct is ENABLED")
                    }
                    else -> {
                        // Wi-Fi P2P is not enabled
                        Log.i(LOGGING_TAG,"wifi direct is DISABLED")
                    }
                }

            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                // Call WifiP2pManager.requestPeers() to get a list of current peers
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                // Respond to new connection or disconnections
            }
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                // Respond to this device's wifi state changing
            }
        }
    }
}
