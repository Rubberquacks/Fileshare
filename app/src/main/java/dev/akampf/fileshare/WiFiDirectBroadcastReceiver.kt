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
	private val mWiFiDirectManager: WifiP2pManager,
	private val mWiFiDirectChannel: WifiP2pManager.Channel,
	private val mMainActivity: MainActivity
) : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {

		when (intent.action) {
			WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
				// Check to see if Wi-Fi Direct is enabled and notify appropriate activity

				// TODO in emulator and some real devices on activity resume and wifi enabled (only when connected?) the wifi direct state
                // constantly toggles between on and off until wifi is disabled, then enabling it (and connecting to a network) does not
                // lead to the toggling behavior again
				when (val wiFiDirectState: Int = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)) {
					WifiP2pManager.WIFI_P2P_STATE_ENABLED -> {
						// Wifi P2P is enabled
						Log.i(LOGGING_TAG ,"WiFi Direct is ENABLED")
						mMainActivity.mWiFiDirectEnabled = true
					}
					WifiP2pManager.WIFI_P2P_STATE_DISABLED -> {
						// Wi-Fi P2P is not enabled
						Log.i(LOGGING_TAG,"WiFi Direct is DISABLED")
						mMainActivity.mWiFiDirectEnabled = false
					}
					else -> {
						// === THIS STATE SHOULD NEVER BE REACHED ===

						// the EXTRA_WIFI_STATE extra of the WiFi Direct State Changed intent was not found (state == -1) or the state is neither
						// enabled (state == 2) nor disabled (state == 1), which should be the only 2 options when the extra is present
						// probably the extra also should be present all the time, when receiving the Wifi Direct
						// state changed action
						Log.e(LOGGING_TAG, "=== THIS STATE SHOULD NEVER BE REACHED!!! === \n" +
								"WIFI_P2P_STATE_CHANGED_ACTION intent received but not an EXTRA_WIFI_STATE " +
								"extra with enabled or disabled state!\nExtra value (-1 if EXTRA_WIFI_STATE extra not found) = $wiFiDirectState")
					}
				}

			}
			WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
				// The discovery finished, now we can request a list of current peers, note that it might be empty!
				// When this asynchronous method has the results, we notify we main activity and pass the peer list to it.
				mWiFiDirectManager.requestPeers(mWiFiDirectChannel) { peerList -> mMainActivity.notifyWiFiDirectPeerListDiscoveryFinished(peerList) }

			}
			WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
				// Respond to new connection or disconnections
			}
			WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
				// Respond to this device's wifi state changing
			}
			// different action or action is null
			else -> {}
		}
	}
}
