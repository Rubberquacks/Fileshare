package dev.akampf.fileshare

// see comment in imports in MainActivity for explanation of the `kotlinx.android.synthetic...` import
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.*
import java.net.InetAddress
import kotlin.time.ExperimentalTime


private const val LOG_TAG: String = "WiFiDirectBrdcastRcvr"


// TODO should this class really receive the wifi direct manager and channel or just notify some other code of the change, this would
//  make the complicated instantiation of this class obsolete and also decouple the notifying of changes of the wifi direct manager logic,
//  where the connection to the manager can be lost etc (so this class would not be affected by that wifi direct manager lost connection
//  callback)
/**
 * A BroadcastReceiver that handles / notifies of important Wi-Fi Direct events.
 */
@ExperimentalTime
class WiFiDirectBroadcastReceiver(
	private val wiFiDirectManager: WifiP2pManager,
	private val wiFiDirectChannel: WifiP2pManager.Channel,
	private val mainActivity: MainActivity
) : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent): Unit {

		when (intent.action) {
			WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
				// Check to see if Wi-Fi Direct is enabled and notify appropriate activity

				// TODO in emulator and some real devices on activity resume and wifi enabled (only when connected?) the wifi direct state
				//  constantly toggles between on and off until wifi is disabled, then enabling it (and connecting to a network) does not
				//  lead to the toggling behavior again
				when (val wiFiDirectState: Int = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)) {
					WifiP2pManager.WIFI_P2P_STATE_ENABLED -> {
						Log.i(LOG_TAG, "WiFi Direct is ENABLED")
						mainActivity.wiFiDirectEnabled = true
						mainActivity.wiFiDirectStatusInitialized = true
					}
					WifiP2pManager.WIFI_P2P_STATE_DISABLED -> {
						Log.i(LOG_TAG, "WiFi Direct is DISABLED")
						mainActivity.wiFiDirectEnabled = false
						mainActivity.wiFiDirectStatusInitialized = true
					}
					else -> {
						// === THIS STATE SHOULD NEVER BE REACHED ===

						// the EXTRA_WIFI_STATE extra of the WiFi Direct State Changed intent was not found (state == -1) or the state is neither
						// enabled (state == 2) nor disabled (state == 1), which should be the only 2 options when the extra is present
						// The extra also should be present all the time, when receiving the Wifi Direct state changed action
						Log.wtf(
							LOG_TAG, "=== THIS STATE SHOULD NEVER BE REACHED!!! === \n" +
									"WIFI_P2P_STATE_CHANGED_ACTION intent received but not an EXTRA_WIFI_STATE " +
									"extra with enabled or disabled state!\nExtra value (-1 if EXTRA_WIFI_STATE extra not found) = $wiFiDirectState"
						)
					}
				}

			}
			WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
				Log.d(LOG_TAG, "WiFi Direct Peers Changed Intent received")
				// The discovery finished, now we can request a list of current peers, note that it might be empty!
				// When this asynchronous method has the results, we notify we main activity and pass the peer list to it.
				wiFiDirectManager.requestPeers(wiFiDirectChannel) { peerList -> mainActivity.notifyWiFiDirectPeerListDiscoveryFinished(peerList) }

			}
			WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
				val wiFiDirectGroupInfo: WifiP2pInfo? = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
				mainActivity.wiFiDirectGroupInfo = wiFiDirectGroupInfo

				// use of this is deprecated: https://developer.android.com/reference/android/net/NetworkInfo.html
				//  but it is described in the documentation that it is an info provided in this intent here:
				//  https://developer.android.com/reference/kotlin/android/net/wifi/p2p/WifiP2pManager.html#wifi_p2p_connection_changed_action
				val networkInfo: NetworkInfo? = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
				val wiFiDirectGroup: WifiP2pGroup? = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
				Log.i(
					LOG_TAG, "WiFi Direct Connection Status changed:\n" +
							"WiFiDirectInfo: $wiFiDirectGroupInfo\n" +
							"NetworkInfo: $networkInfo\n" +
							"WiFi Direct Group: $wiFiDirectGroup"
				)
				// Respond to new connection or disconnections

				wiFiDirectManager?.let { wiFiDirectManager ->

					if (networkInfo?.isConnected == true) {

						Log.d(
							LOG_TAG, "NetworkInfo says we are connected to a WiFi Direct group and it is possible to establish " +
									"connections and pass data"
						)

						// We are connected with the other device, request connection info to find group owner IP

						val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { wiFiDirectInfo ->

							val thisOrOtherDeviceText = if (wiFiDirectInfo.isGroupOwner) {
								context.getText(R.string.wifi_direct_connection_established_this_device)
							} else {
								context.getText(R.string.wifi_direct_connection_established_other_device)
							}

							Snackbar.make(
								mainActivity.root_coordinator_layout,
								context.getString(
									R.string.wifi_direct_connection_established,
									wiFiDirectGroup?.networkName,
									wiFiDirectGroup?.owner?.deviceName,
									thisOrOtherDeviceText
								),
								Snackbar.LENGTH_INDEFINITE
							).show()


							val groupOwnerIpAddress: InetAddress? = wiFiDirectInfo.groupOwnerAddress.also {
								Log.d(LOG_TAG, "group owner address: ${it.hostAddress}")
							}

							Log.d(LOG_TAG, "WiFi Direct client list: ${wiFiDirectGroup?.clientList}")


							// After the group negotiation, we can determine the group owner, who has to start a server cause they do not know the ip
							// addresses of the connected clients in the group, only the others know the ip address of the group owner
							if (wiFiDirectInfo.groupFormed) {


								// todo: replace with calling function in service to start listening for files as well as
								//  currently not working correctly, receiving less bytes that it writes
								// start server on both group owner and client, because that is the way the accept incoming connections with data, but
								// the client cannot be contacted until the group owner knows its ip address, which we will tell them by connecting on
								// another por
								ReceiveFileOrGetIpAddressFromOtherDeviceAsyncTask(
									mainActivity,
									mainActivity.wiFi_direct_status_text_view,
									onlyGetNotifiedOfIpAddress = false
								).execute()



								if (wiFiDirectInfo.isGroupOwner) {
									Log.d(LOG_TAG, "current device is group owner")
									// The other device acts as the peer (client).
									// Do tasks that are specific to the group owner.



								} else {
									Log.d(LOG_TAG, "other device is group owner")
									// We connect to the group owner just so that they know our ip address (from the connection itself) and can connect
									// back to us when they have something to send
									val startServiceIntent: Intent = Intent(context, SendFileOrNotifyOfIpAddressIntentService::class.java).apply {
										action = SendFileOrNotifyOfIpAddressIntentService.ACTION_NOTIFY_OF_IP_ADDRESS
										// if the other device is the group owner the group owner ip address is known and not null
										putExtra(SendFileOrNotifyOfIpAddressIntentService.EXTRAS_OTHER_DEVICE_IP_ADDRESS, groupOwnerIpAddress?.hostAddress)
									}

									// Unlike the ordinary `startService(Intent)`, this method can be used at
									// any time, regardless of whether the app hosting the service is in a foreground state.
									// (Only relevant for Android 8+ (API 26+)
									ContextCompat.startForegroundService(context, startServiceIntent)
								}
							}
						}


						wiFiDirectManager.requestConnectionInfo(wiFiDirectChannel, connectionInfoListener)
					}
				}


			}
			WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
				// Respond to this device's wifi state changing
				// https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager#WIFI_P2P_THIS_DEVICE_CHANGED_ACTION
			}
			// different action or action is null, this should not happen as we did not register for other actions in the intent filter
			else -> {
			}
		}
	}
}
