package dev.akampf.fileshare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.Uri
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.*
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket


private const val LOGGING_TAG: String = "WiFiDirectBrdcastRceivr"

/**
 * A BroadcastReceiver that handles / notifies of important Wi-Fi Direct events.
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
				//  constantly toggles between on and off until wifi is disabled, then enabling it (and connecting to a network) does not
				//  lead to the toggling behavior again
				when (val wiFiDirectState: Int = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)) {
					WifiP2pManager.WIFI_P2P_STATE_ENABLED -> {
						Log.i(LOGGING_TAG ,"WiFi Direct is ENABLED")
						mMainActivity.mWiFiDirectEnabled = true
					}
					WifiP2pManager.WIFI_P2P_STATE_DISABLED -> {
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
				Log.d(LOGGING_TAG, "WiFi Direct Peers Changed Intent received")
				// The discovery finished, now we can request a list of current peers, note that it might be empty!
				// When this asynchronous method has the results, we notify we main activity and pass the peer list to it.
				mWiFiDirectManager.requestPeers(mWiFiDirectChannel) { peerList -> mMainActivity.notifyWiFiDirectPeerListDiscoveryFinished(peerList) }

			}
			WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
				val wiFiDirectInfo: WifiP2pInfo? = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
				mMainActivity.connectedWiFiDirectInfo = wiFiDirectInfo

				// TODO use of this is deprecated: https://developer.android.com/reference/android/net/NetworkInfo.html
				//  but it is described in the documentation that it is an info provided in this intent here:
				//  https://developer.android.com/reference/kotlin/android/net/wifi/p2p/WifiP2pManager.html#wifi_p2p_connection_changed_action
				val networkInfo: NetworkInfo? = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
				val wiFiDirectGroup: WifiP2pGroup? = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
				Log.i(LOGGING_TAG, "WiFi Direct Connection Status changed:\n" +
						"WiFiDirectInfo: $wiFiDirectInfo\n" +
						"NetworkInfo: $networkInfo\n" +
						"WiFi Direct Group: $wiFiDirectGroup")
				// Respond to new connection or disconnections

				mWiFiDirectManager?.let { wiFiDirectManager ->

					if (networkInfo?.isConnected == true) {


						// We are connected with the other device, request connection
						// info to find group owner IP

						val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { wiFiDirectInfo ->

							Snackbar.make(
								mMainActivity.root_coordinator_layout,
								"Connection ${wiFiDirectGroup?.networkName} with owner ${wiFiDirectGroup?.owner} ${if (wiFiDirectInfo.isGroupOwner) {" (this device)"} else {""} } established",
								Snackbar.LENGTH_LONG
							).show()

							// IP address from WifiP2pInfo struct.
							val groupOwnerIpAddress: String = wiFiDirectInfo.groupOwnerAddress.hostAddress.also {Log.d(LOGGING_TAG, "group owner address: $it")}

							// After the group negotiation, we can determine the group owner
							// (server).
							if (wiFiDirectInfo.groupFormed && wiFiDirectInfo.isGroupOwner) {
								Log.d(LOGGING_TAG,"current device is group owner")
								// Do whatever tasks are specific to the group owner.
								// One common case is creating a group owner thread and accepting
								// incoming connections.
								FileServerAsyncTask(mMainActivity, mMainActivity.wiFi_direct_status_text_view).execute()
							} else if (wiFiDirectInfo.groupFormed) {
								Log.d(LOGGING_TAG,"other device is group owner")
								// The other device acts as the peer (client). In this case,
								// you'll want to create a peer thread that connects
								// to the group owner.




							}
						}


						wiFiDirectManager.requestConnectionInfo(mWiFiDirectChannel, connectionInfoListener)
					}
				}



			}
			WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
				// Respond to this device's wifi state changing
			}
			// different action or action is null
			else -> {}
		}
	}
}
