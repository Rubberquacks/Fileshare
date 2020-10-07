package dev.akampf.fileshare

// `import kotlinx.android.synthetic.main.activity_main.*` is used to access views directly by their id as the variable name
// without `findViewByID()`, uses `kotlin-android-extensions` which does lookup and caching for us
import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.net.InetAddress
import kotlin.time.ExperimentalTime

// be app unique or what are the rules? use enum?
private const val OPEN_FILE_WITH_FILE_CHOOSER_REQUEST_CODE: Int = 42
private const val ACCESS_FINE_LOCATION_PERMISSION_REQUEST_CODE: Int = 52


private const val ACCESS_FINE_LOCATION_PERMISSION: String = Manifest.permission.ACCESS_FINE_LOCATION

private const val LOG_TAG: String = "WiFiDirectMainActivity"


@ExperimentalTime
class MainActivity : AppCompatActivity(), WiFiDirectDeviceFragment.OnListFragmentInteractionListener {


	private lateinit var receiveIpAddressService: WiFiDirectBackgroundService
	private var receiveIpAddressServiceIsBound: Boolean = false


	// todo: transfer bind from broadcastreceiver to activity onResume or onStart
	/** Defines callbacks for service binding, passed to bindService()  */
	val receiveIpAddressServiceConnection = object : ServiceConnection {

		override fun onServiceConnected(componentNameOfServic: ComponentName, service: IBinder) {
			// We've bound to the Service, cast the IBinder and get the Service instance
			Log.d(LOG_TAG, "onServiceConnected callback in MainActivity called for $componentNameOfServic")
			val binder = service as WiFiDirectBackgroundService.LocalBinder
			// receiveIpAddressService = binder.getService()
			receiveIpAddressServiceIsBound = true
		}

		// seems to only get called on unexpected lost connection to service like the service crashing
		// and not on normal unbind or stop, documentation is vague... :/
		// https://developer.android.com/reference/android/content/ServiceConnection.html#onServiceDisconnected(android.content.ComponentName)
		override fun onServiceDisconnected(componentNameOfService: ComponentName) {
			Log.e(LOG_TAG, "Service Connection to $componentNameOfService lost!")
			receiveIpAddressServiceIsBound = false
		}
	}


	// finish service binding here, onstart etc, see android website example
	//  https://developer.android.com/guide/components/bound-services#Binder
	// currently bound to in broadcast receiver


	// TODO register callback when connection to system WiFi Direct manager gets lost and handle appropriately
	private val wiFiDirectManager: WifiP2pManager? by lazy(LazyThreadSafetyMode.NONE) {
		ContextCompat.getSystemService(this, WifiP2pManager::class.java)
	}

	// TODO clear this and related variables on disconnect to make tracking state easier?
	var wiFiDirectGroupInfo: WifiP2pInfo? = null

	var connectedClientWiFiDirectIpAddress: InetAddress? = null

	// used to receive the ip address of the other device from the service and save it
	// TODO should be moved to repository class or some kind of data storage outside of activity
	private val ipAddressBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			intent ?: TODO("received local broadcast for ip address without intent, this shouldn't happen")
			intent.action ?: TODO("no action specified in local broadcast")
			val ipAddressFromOtherDevice: InetAddress =
				intent.getSerializableExtra(WiFiDirectBackgroundService.EXTRA_IP_ADDRESS_OF_CONNECTED_DEVICE) as? InetAddress
					?: TODO("no extra with ip address specified in local broadcast or couldn't deserialize inetaddress")
			Log.d(LOG_TAG, "setting ip address of other device $ipAddressFromOtherDevice in main activity")
			this@MainActivity.connectedClientWiFiDirectIpAddress = ipAddressFromOtherDevice

		}
	}

	private val newFileReceivedBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			Log.d(LOG_TAG, "onReceive of new file received broadcast receiver")
			intent ?: TODO("received local broadcast for newly received file without intent, this shouldn't happen")
			intent.action ?: TODO("received local broadcast for newly received file without intent action")
			val absolutePathToNewlyReceivedFile: String = intent.getSerializableExtra(WiFiDirectBackgroundService.EXTRA_ABSOLUTE_PATH_TO_NEWLY_RECEIVED_FILE_FROM_CONNECTED_DEVICE) as? String
				?: TODO("no extra with absolute path to newly received file as string in local broadcast or couldn't deserialize it")


			Log.d(LOG_TAG, "File was written to $absolutePathToNewlyReceivedFile")
			wiFi_direct_status_text_view.text = "File received: $absolutePathToNewlyReceivedFile"
			displayFileInAppropriateNewActivityIfPossible(absolutePathToNewlyReceivedFile)

		}
	}
	
	private fun displayFileInAppropriateNewActivityIfPossible(absolutePathToFile: String) {


		val uriToFile: Uri = Uri.parse("file://$absolutePathToFile")



		// TODO this is ugly, extract method as general helper (for a context, because of content provider) or just globally
		// TODO change to display info with other method cause this only works with the content:// scheme and not file:// urls!
		dumpContentUriMetaData(uriToFile)

		val viewIntent: Intent = Intent(Intent.ACTION_VIEW)

		// use content provider for content:// uri scheme used in newer versions for modern working secure sharing of files with other apps,
		// but not all apps might support those instead of file:// uris, so still use them for older versions where they work for greater
		// compatibility
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			// needed for app displaying the file having the temporary access to read from this uri, either uri must be put in data of intent
			// or `Context.grantUriPermission` must be called for the target package
			// explain in comments why this is needed / what exactly is needed more clearly
			viewIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION

			// the authority argument of `getUriForFile` must be the same as the authority of the file provider defined in the AndroidManifest!
			// should extract authority in global variable or resource to reuse in manifest and code
			val fileProviderUri =
				FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", File(absolutePathToFile))

			// normalizing the uri to match android best practices for schemes: makes the scheme component lowercase
			viewIntent.setDataAndNormalize(fileProviderUri)
		} else {
			// normalizing the uri to match android case-sensitive matching for schemes: makes the scheme component lowercase
			viewIntent.setDataAndNormalize(uriToFile)
		}

		try {
			startActivity(viewIntent)
		} catch (e: ActivityNotFoundException) {
			Log.w(ReceiveFileOrGetIpAddressFromOtherDeviceAsyncTask.LOG_TAG, "No installed app supports viewing this content!", e)
			Snackbar.make(
				root_coordinator_layout,// could also be that the other device has not connected to us yet but the wifi direct connection is fine
				getString(R.string.could_not_find_activity_to_handle_viewing_content),
				Snackbar.LENGTH_LONG
			).show()
		}

	}

	private val wiFiDirectIntentFilter: IntentFilter = IntentFilter().apply {
		// Indicates a change in the Wi-Fi P2P status.
		addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
		// Indicates a change in the list of available peers.
		addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
		// Indicates the state of Wi-Fi P2P connectivity has changed.
		addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
		// Indicates this device's details have changed.
		addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
	}

	private var wiFiDirectChannel: WifiP2pManager.Channel? = null
	private var wiFiDirectBroadcastReceiver: BroadcastReceiver? = null


	// TODO just enable wifi etc ourselves? location mode and wifi disabled snackbars don't display both
	//  https://developer.android.com/about/versions/10/privacy/changes#enable-disable-wifi
	// TODO add new ui widgets per missing permission/activation with option to change and only display "unavailable because:" or nothing in first line

	// This is set to true when mWiFiDirectEnable is first set to true or false by the wifi direct broadcast receiver. Then we know the value
	// in it is not the default false value but the value accurately describing the wifi direct state
	var wiFiDirectStatusInitialized: Boolean = false

	// TODO can permission be revoked without onResume being called again, do we need to check permissions after checking in onResume that
	//  we have the permission?
	// how to handle if later wifi is deactivated when we connect to a device or are already connected etc?
	// can we distinguish disabled and unavailable? is it unavailable on supported devices sometimes even?
	var wiFiDirectEnabled: Boolean = false
		set(wiFiDirectEnabled) {


			// Only continue to execute something if the value changed compared to the old value OR it is the first time a valid value is set by
			// the wifi direct broadcast receiver (could also be set from false to false, but the previous value
			// only was the default value false!)
			// => if the value is the same and it was already initialized => no *real* change and skip rest of code reacting to a change
			//
			// If the value did not change, but is set, it is probably just spam from the wifi direct broadcast receiver reporting it multiple
			// times
			if ((wiFiDirectEnabled == this.wiFiDirectEnabled) && wiFiDirectStatusInitialized) {
				return
			}

			if (wiFiDirectEnabled) {
				wiFi_direct_status_text_view.text = getString(R.string.wifi_direct_status_enabled)
			} else {
				wiFi_direct_status_text_view.text = getString(R.string.wifi_direct_status_disabled)
				Snackbar.make(
					root_coordinator_layout,
					getString(R.string.wifi_direct_disabled_message),
					Snackbar.LENGTH_INDEFINITE
				).show()
			}
			if (wiFiDirectEnabled && fineLocationPermissionGranted) {
				// TODO maybe request again if not granted? think about at what times request is appropriate or just make button after first request
				discoverWiFiDirectPeers()
			}

			field = wiFiDirectEnabled
		}


	// TODO we should also check if location services are enabled by the user and prompt for that if not the case
	// TODO luckily setter is not called on initialization with false, since this could be wrong (permission already granted), but still
	//  initialization with false does not really seem clean, maybe initialize with null if not accessed except in setter (no null safety)?
	private var fineLocationPermissionGranted: Boolean = false
		set(fineLocationPermissionGranted_new_value) {

			if (fineLocationPermissionGranted_new_value) {
				if (wiFiDirectEnabled) {

					discoverWiFiDirectPeers()
				}
				// TODO notify that wifi needs to be enabled after location permission was granted or just enable it automatically
				//  and notify about it?
			} else {
				// TODO when (first) requesting permission, display sth like "requesting permission" and not "denied", cause that seems harsh and
				//  users might see this behind the permission request dialog, "requesting" seems more appropriate to convey the state, even if
				//  denied (from the system) is also technically correct
				wiFi_direct_status_text_view.text = getString(R.string.wifi_direct_location_permission_denied)
			}
			// set field to new value at the end so we can use the old value before for comparing if it changed or was the same before this setter
			// was called / the property was set
			field = fineLocationPermissionGranted_new_value
		}

	private val locationModeChangedBroadcastReceiver: LocationModeChangedBroadcastReceiver = LocationModeChangedBroadcastReceiver()
	private val locationModeChangedIntentFilter: IntentFilter = IntentFilter(LocationManager.MODE_CHANGED_ACTION)

	var locationModeEnabled: Boolean = false
		set(newValueLocationModeEnabled) {
			Log.v(LOG_TAG, "Location mode set to state: $newValueLocationModeEnabled")
			if (!newValueLocationModeEnabled) {
				Snackbar.make(
					root_coordinator_layout,
					getString(R.string.wifi_direct_location_mode_disabled),
					Snackbar.LENGTH_INDEFINITE
				).show()
			}
			field = newValueLocationModeEnabled
		}


	lateinit var wiFiDirectDeviceFragment: WiFiDirectDeviceFragment

	val wiFiDirectPeers: MutableList<WifiP2pDevice> = mutableListOf<WifiP2pDevice>()

	fun notifyWiFiDirectPeerListDiscoveryFinished(discoveredPeers: WifiP2pDeviceList): Unit {
		Log.i(LOG_TAG, "Discovered WiFi Direct peers:\n" +
				discoveredPeers.deviceList.joinToString(separator = "\n") { device -> "Device name: ${device.deviceName}" })

		// sort or even filter by device type: https://www.wifi-libre.com/img/members/3/Wi-Fi_Simple_Configuration_Technical_Specification_v2_0_5.pdf
		// maybe use discovery to detect on which device the app is actually running

		// why not work directly with WifiP2pDeviceList ? current code from:
		//  https://developer.android.com/training/connect-devices-wirelessly/wifi-direct#fetch
		val refreshedPeers = discoveredPeers.deviceList
		if (refreshedPeers != wiFiDirectPeers) {
			wiFiDirectPeers.clear()
			wiFiDirectPeers.addAll(refreshedPeers)

			// TODO move peer list management to fragment to make management of ui changes easier?
			//  or at least use https://developer.android.com/guide/components/fragments#CommunicatingWithActivity or
			//  https://developer.android.com/reference/kotlin/androidx/fragment/app/FragmentManager.html to interact with fragment?
			// Tell the RecyclerViewAdapter that is backed by this data that it changed so it can update the view
			// this could be replaced ideally by using livedata lists for the devices list or by using:
			// https://developer.android.com/reference/androidx/recyclerview/widget/ListAdapter
			wiFiDirectDeviceFragment.recyclerViewAdapter.notifyDataSetChanged()
		}

		if (wiFiDirectPeers.isEmpty()) {
			Log.i(LOG_TAG, "No WiFi Direct devices found in current scan")
		}
	}


	// the RecyclerView in the fragment calls this method when a view in it was clicked
	override fun onListFragmentInteraction(wiFiDirectDevice: WifiP2pDevice): Unit {
		wiFiDirectDevice.let { clickedDeviceItem ->
			Log.i(LOG_TAG, "$clickedDeviceItem\n has been clicked")
			connectToWiFiDirectDevice(wiFiDirectDevice)
		}
	}

	private fun connectToWiFiDirectDevice(wiFiDirectDeviceToConnectTo: WifiP2pDevice): Unit {

		val wiFiDirectConnectionConfig = WifiP2pConfig().apply {
			deviceAddress = wiFiDirectDeviceToConnectTo.deviceAddress
			// use other wps setup method if PBC not supported or is PBC always supported by laptops, tablets, phones, etc?
			//  see if WiFi Direct spec says what must be supported
			//
			//  WpsInfo.PBC is default value
			// wps.setup = here_is_the_mode_to_use
		}

		// initiates WiFi Direct group negotiation with target or invite device to existing group where this device is already part of (because
		// it has joined or has created the group itself)
		// TODO at this point another device was found, so the manager was clearly initiated, should we just assume that, assert that or catch
		//  the case that it was null and display an error / handle it appropriately?
		wiFiDirectChannel.also {
			// TODO here https://developer.android.com/training/connect-devices-wirelessly/wifi-direct#connect it is done without ?. (safe call) /
			//  being safe that mChannel is null and ide also does not complain that mChannel is of nullable type, even though it
			//  complains about that when initializing, even though there the manager should be not null after initialization the channel with it
			//  and only doing sth with the channel if it is not null and using the manager there

			// TODO linter says this and other methods called need permission check, when / how often do we need to check these permissions?
			wiFiDirectManager?.connect(wiFiDirectChannel, wiFiDirectConnectionConfig, object : WifiP2pManager.ActionListener {

				override fun onSuccess(): Unit {
					Log.d(LOG_TAG, "Initiation of connection to $wiFiDirectDeviceToConnectTo succeeded")
					// We get notified by broadcast when the connection is really there
				}

				override fun onFailure(reason: Int): Unit {
					val failureReason: String = getFailureReasonForWiFiDirectActionListener(reason)
					Log.i(LOG_TAG, "Initiation of connection to $wiFiDirectDeviceToConnectTo failed: $failureReason")
					// Make text for failure reason suitably short for Snackbar or display otherwise in the future
					Snackbar.make(
						root_coordinator_layout,
						getString(
							R.string.wifi_direct_connection_initiation_failed,
							wiFiDirectDeviceToConnectTo.deviceName,
							wiFiDirectDeviceToConnectTo.deviceAddress,
							failureReason
						),
						Snackbar.LENGTH_INDEFINITE
					).show()
				}
			})
		}

	}

	/**
	 * If MainActivity *currently* has specified android permission, result can change at any time.
	 *
	 * @param androidManifestPermission String in the Android.manifest.* namespace.
	 * @return if permission is currently available
	 */
	private fun havePermissionCurrently(androidManifestPermission: String): Boolean {
		return ContextCompat.checkSelfPermission(this, androidManifestPermission) == PackageManager.PERMISSION_GRANTED

	}


	private fun requestFineLocationPermissionForWiFiDirect(): Unit {

		// Should we show an explanation? (this is true when the permission was denied in the past)
		if (ActivityCompat.shouldShowRequestPermissionRationale(
				this,
				ACCESS_FINE_LOCATION_PERMISSION
			)
		) {
			Log.i(LOG_TAG, "Fine Location permission was denied in the past! TODO: show an explanation before retrying")
			// TODO: Show an explanation to the user *asynchronously* -- don't block
			//  this thread waiting for the user's response! After the user
			//  sees the explanation, try again to request the permission.

			// TODO at least on android 10 device and android R preview emulator, this loops after one denied request, so show rationale
			//  and then request again but also
			//  allow to cancel so that no request is made after that and the permission request loop exits.
			//  https://developer.android.com/preview/privacy/permissions#dialog-visibility
			// this will be removed and put in the callback of the explanation window/popup/whatever
			ActivityCompat.requestPermissions(
				this,
				arrayOf(ACCESS_FINE_LOCATION_PERMISSION),
				ACCESS_FINE_LOCATION_PERMISSION_REQUEST_CODE
			)

		} else {
			// TODO show rationale for location for WiFi Direct every time, even the first time
			Log.i(
				LOG_TAG, "Fine Location permission was not denied in the past, request permission directly " +
						"without showing rationale. TODO maybe show rationale for location for WiFi Direct every time"
			)
			// No explanation needed, we can request the permission.
			ActivityCompat.requestPermissions(
				this,
				arrayOf(ACCESS_FINE_LOCATION_PERMISSION),
				ACCESS_FINE_LOCATION_PERMISSION_REQUEST_CODE
			)
			// The callback method `onRequestPermissionsResult` gets the result of the request providing the same request code as used here.
		}
	}

	private fun initializeWiFiDirect(): Unit {
		// Registers the application with the Wi-Fi Direct framework to do any further operations.

		// TODO: do we need to do all of this even when only onResume is executed and not onCreate? according to examples, this is not the case
		// this only needs `android.permission.ACCESS_WIFI_STATE` so we wan safely call it without location permission, cause
		// `android.permission.ACCESS_WIFI_STATE` was granted at install time

		// TODO set listener to be notified of loss of framework communication?
		wiFiDirectChannel = wiFiDirectManager?.initialize(this, mainLooper, null)
		wiFiDirectChannel?.let { channel ->
			// If wiFiDirectChannel was not null, we are already sure manager was not null, too, because of the mWiFiDirectManager?.initialize() call
			// above only being executed when wiFiDirectManager was not null. So we cast it to not optional type with the
			// characters !! (assert non-null).
			wiFiDirectBroadcastReceiver = WiFiDirectBroadcastReceiver(wiFiDirectManager!!, channel, this)

		}
	}

	// result callback from requesting dangerous permission access from the system, e.g. Location for WiFi Direct
	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray): Unit {
		when (requestCode) {
			ACCESS_FINE_LOCATION_PERMISSION_REQUEST_CODE -> {
				// If request is cancelled, the result arrays are empty.
				if (grantResults.getOrNull(0) == PackageManager.PERMISSION_GRANTED) {
					// permission was granted, yay! Do the
					// permission related task you need to do.
					fineLocationPermissionGranted = true


				} else {
					Log.w(LOG_TAG, "Fine Location permission denied, cannot use WiFi Direct! TODO: disable all functionality dependent on that")
					// TODO permission denied, boo! Disable the functionality that depends on this permission.
				}
				return
			}

			// Add other 'when' cases here to check if there are other
			// permissions this app might have requested.
			else -> {
				// Ignore all other requests.
			}
		}
	}


	private fun discoverWiFiDirectPeers(): Unit {

		// This only initiates the discovery, this method immediately returns.
		// The discovery remains active in the background until a connection is initiated or a p2p group is formed.
		wiFiDirectManager?.discoverPeers(wiFiDirectChannel, object : WifiP2pManager.ActionListener {

			// success initiating the scan for peers
			override fun onSuccess(): Unit {
				Log.i(LOG_TAG, "Initiating peer discovery successful")
				// In the future, if the discovery process succeeds and detects peers, the system broadcasts the
				// WIFI_P2P_PEERS_CHANGED_ACTION intent, which we can listen for in a broadcast receiver to then obtain a list of peers.
			}

			// failed to initiate the scan for peers
			// This currently happens with "framework busy" error after starting app and allowing location
			// permission for the first time
			override fun onFailure(reasonCode: Int): Unit {
				val failureReason: String = getFailureReasonForWiFiDirectActionListener(reasonCode)
				Log.w(LOG_TAG, "Initiating peer discovery failed: $failureReason")
				// Display this error in this way? make reason short enough for snackbar
				showSnackbarLengthIndefinite("Initiating peer discovery failed: $failureReason")
			}
		})
	}


	override fun onCreate(savedInstanceState: Bundle?): Unit {
		Log.d(LOG_TAG, "onCreate starting...")
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		initializeWiFiDirect()

		Log.d(LOG_TAG, "registering local broadcast receiver for receiving ip address and newly received file of other device from service...")
		// do in onCreate for now to receive it in the background too, to act upon it later, bad architecture to receive in activity, i know :(
		IntentFilter(WiFiDirectBackgroundService.ACTION_REPORT_IP_ADDRESS_OF_CONNECTED_DEVICE).also { receiveIpAddressIntentFilter ->
			LocalBroadcastManager.getInstance(this).registerReceiver(ipAddressBroadcastReceiver, receiveIpAddressIntentFilter)
			}
		IntentFilter(WiFiDirectBackgroundService.ACTION_REPORT_NEW_FILE_RECEIVED_FROM_CONNECTED_DEVICE).also { newFileReceivedIntentFilter ->
			LocalBroadcastManager.getInstance(this).registerReceiver(newFileReceivedBroadcastReceiver, newFileReceivedIntentFilter)

		}

	}

	override fun onStart() {
		super.onStart()

		// TODO consider transferring registering broadcast receiver to app or service context to notify user of disabling location
		//  or permissions needed
		//  while transferring data and give explicit error message (if not available in failing transfer) and offer to activate it again
		//  and maybe even resume the transfer then
		//  https://developer.android.com/guide/components/broadcasts#context-registered-receivers (search for application context)


		// The broadcast receiver does not fire for changes while not registered (here: while app was inactive, since we unregister in onStop)
		// so we have to query the value once when app (or service depending on it) gets active
		val locationManager: LocationManager = ContextCompat.getSystemService(this, LocationManager::class.java) as LocationManager
		val currentLocationModeState: Boolean = LocationManagerCompat.isLocationEnabled(locationManager)
		Log.i(LOG_TAG, "Location mode queried onResume, state: $currentLocationModeState")
		locationModeEnabled = currentLocationModeState

		Log.v(LOG_TAG, "registering location mode changed broadcast receiver... : $locationModeChangedBroadcastReceiver")
		registerReceiver(locationModeChangedBroadcastReceiver, locationModeChangedIntentFilter)


		Log.v(LOG_TAG, "registering wifi direct broadcast receiver... : $wiFiDirectBroadcastReceiver")
		registerReceiver(wiFiDirectBroadcastReceiver, wiFiDirectIntentFilter)


		val haveFineLocationPermission = havePermissionCurrently(ACCESS_FINE_LOCATION_PERMISSION)

		if (haveFineLocationPermission) {
			Log.i(LOG_TAG, "We already have Fine Location permission")
			fineLocationPermissionGranted = true
		} else {
			fineLocationPermissionGranted = false
			Log.i(LOG_TAG, "We do not have Fine Location permission, requesting...")
			// TODO permission is requested again after being denied because onResume is executed again (after the permission dialog where
			//  deny was pressed is not in foreground anymore), leave for now and change to manually
			//  requesting / explaining how to grant in settings when denied once or twice.
			//  Move all code dependant on this state to setter?
			requestFineLocationPermissionForWiFiDirect()
		}

		// start service before binding to it, so it stays active even after unbinding
		// when exiting the app
		// We do this in the service, too in bind and rebind, can we drop it here?
		Log.d(LOG_TAG, "Starting new receive ip address and file service, still testing...")
		val startServiceIntent: Intent = Intent(this, WiFiDirectBackgroundService::class.java)
		ContextCompat.startForegroundService(this, startServiceIntent)

		Intent(this, WiFiDirectBackgroundService::class.java).also { intent ->
			Log.i(LOG_TAG, "Trying to bindService to ${WiFiDirectBackgroundService::class.simpleName}...")
			bindService(intent, receiveIpAddressServiceConnection, Context.BIND_AUTO_CREATE).also { bindRequestSucceeded ->
				Log.i(
					LOG_TAG,
					"bind request to ${WiFiDirectBackgroundService::class.simpleName} success status: $bindRequestSucceeded"
				)
			}
		}
	}


	// called when activity in visible AND active in the foreground
	// (in multi window it is possible to be visible but not active in the foreground)
	//
	// On platform versions prior to Build.VERSION_CODES.Q (Android 10, API 29) this is also a good place to try to open exclusive-access
	// devices or to get access to singleton resources. Starting with Build.VERSION_CODES.Q there can be multiple resumed activities in
	// the system simultaneously, so onTopResumedActivityChanged(boolean) should be used for that purpose instead.
	override fun onResume(): Unit {
		Log.d(LOG_TAG, "onResume starting...")
		super.onResume()
	}

	// Called when activity not active in the foreground anymore, but could still be visible (possible in multi window)
	//
	// Starting with Honeycomb, an application is not in the killable state until its onStop() has returned.
	// This allows an application to safely wait until onStop() to save persistent state.
	// Keep in mind that under extreme memory pressure the system can kill the application process at any time!
	//
	// Implementations of this method must be very quick because the next activity will not be resumed until this method returns.
	// This is also a good place to stop things that consume a noticeable amount of CPU in order to make the switch to the next activity as fast as possible.
	override fun onPause(): Unit {
		Log.d(LOG_TAG, "onPause starting...")
		super.onPause()
	}

	override fun onStop() {
		Log.d(LOG_TAG, "onStop starting...")

		Log.v(LOG_TAG, "unregistering Location Mode changed Broadcast Receiver... : $locationModeChangedBroadcastReceiver")
		unregisterReceiver(locationModeChangedBroadcastReceiver)

		if (wiFiDirectBroadcastReceiver != null) {
			Log.v(LOG_TAG, "unregistering wifi direct broadcast receiver... : $wiFiDirectBroadcastReceiver")
			unregisterReceiver(wiFiDirectBroadcastReceiver)
		} else {
			Log.w(LOG_TAG, "Did not unregister WiFi Direct Broadcast Receiver in onPause because it was null!")
		}
		// set to false here so when resuming activity again, a change in wifi direct activation state is handled as if it changed (at the
		// moment only used for actively displaying message that it is disabled)
		wiFiDirectStatusInitialized = false


		// unbind from service to indicate app is not in the foreground anymore
		// service can decide when to exit itself based on that
		if (receiveIpAddressServiceIsBound) {
			unbindService(receiveIpAddressServiceConnection)
			receiveIpAddressServiceIsBound = false
		}
		super.onStop()
	}


	override fun onDestroy() {
		Log.d(LOG_TAG, "unregistering local broadcast receiver for receiving ip address or new file of other device from service...")
		LocalBroadcastManager.getInstance(this).unregisterReceiver(ipAddressBroadcastReceiver)
		LocalBroadcastManager.getInstance(this).unregisterReceiver(newFileReceivedBroadcastReceiver)
		super.onDestroy()
	}

	// consider using ACTION_GET_CONTENT because we only need a copy and not permanent access to the file if it changes and/or modify the file and write it back
	//  If sending big file, how long is access guaranteed? copy to own directory first if space available? better for retrying later? better solution?
	//  https://developer.android.com/guide/topics/providers/document-provider#client
	// Fires an intent to spin up the "file chooser" UI and select a file.
	private fun getOpenableFilePickedByUser(): Unit {

		// ACTION_OPEN_DOCUMENT is the intent to choose a file via the system's file
		// browser.
		val intent: Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			// Filter to only show results that can be "opened", such as a
			// file (as opposed to a list of contacts or timezones)
			// Without this we could not simply get a byte stream from the content to share to read its data, thus requiring special
			// handling to transfer its contents. But this should be possible in the future, too. Like sending contacts. #enhancement
			addCategory(Intent.CATEGORY_OPENABLE)

			// Filter to show only images, using the image MIME data type.
			// If one wanted to search for ogg vorbis files, the type would be "audio/ogg".
			// To search for all documents available via installed storage providers,
			// it would be "*/*".
			type = "*/*"
		}

		// result is delivered in `onActivityResult` callback
		try {
			startActivityForResult(intent, OPEN_FILE_WITH_FILE_CHOOSER_REQUEST_CODE)
		} catch (e: ActivityNotFoundException) {
			Log.e(
				LOG_TAG, "Could not open file chooser activity to choose file, activity not found that could handle our " +
						"request! This should be present in every system and thus this error should never occur!", e
			)
		}
	}


	fun onClickedOpenFileButton(view: View): Unit {
		// TODO we should really check if we are connected to a device currently and not only if info about a connected device was set sometime
		//  currently opening file chooser is possible and then snackbar says connection to device lost
		//  check ip address of other device available (if other device is not group owner) and reset those info on connection lost
		Log.d(LOG_TAG, "open file button clicked")
		if (wiFiDirectGroupInfo != null) {
			// result is delivered in `onActivityResult` callback
			getOpenableFilePickedByUser()
		} else {
			Snackbar.make(
				root_coordinator_layout,
				getString(R.string.wifi_direct_please_connect_first),
				Snackbar.LENGTH_LONG
			).show()
		}

	}

	fun onClickedDisconnectButton(view: View): Unit {
		disconnectWiFiDirectConnection()
	}

	private fun disconnectWiFiDirectConnection(): Unit {
		if (wiFiDirectManager == null || wiFiDirectChannel == null) {
			Log.w(LOG_TAG, "Could not request disconnect because WiFi Direct Manager or Channel are null")
			showSnackbarLengthIndefinite("Could not request disconnect because WiFi Direct Manager or Channel are null")
			return
		}
		wiFiDirectManager?.requestGroupInfo(wiFiDirectChannel) { wifiDirectGroup: WifiP2pGroup? ->
			Log.d(LOG_TAG, "Current WiFi Direct Group: $wifiDirectGroup")
			if (wifiDirectGroup == null) {
				showSnackbarLengthIndefinite("Could not request disconnect because current WiFi Direct Group is null")
				Log.w(LOG_TAG, "Could not request disconnect because current WiFi Direct Group is null")
				return@requestGroupInfo
			}
			if (wiFiDirectManager == null || wiFiDirectChannel == null) {
				Log.w(
					LOG_TAG,
					"Could not request disconnect because WiFi Direct Manager or Channel after having received WiFi Direct group info"
				)
				showSnackbarLengthIndefinite("Could not request disconnect because WiFi Direct Manager or Channel after having received WiFi Direct group info")
				return@requestGroupInfo
			} else {
				wiFiDirectManager?.removeGroup(wiFiDirectChannel, object : WifiP2pManager.ActionListener {
					override fun onSuccess() {
						Log.i(LOG_TAG, "Successfully disconnected from group: $wifiDirectGroup")
						showSnackbarLengthIndefinite("Successfully disconnected from: $wifiDirectGroup")
						// TODO stop the android service listening for requests and ip address, too
					}

					override fun onFailure(reason: Int) {
						val failureReason: String = getFailureReasonForWiFiDirectActionListener(reason)
						Log.w(LOG_TAG, "Error when trying to disconnect from $wifiDirectGroup, failed because: $failureReason")
						showSnackbarLengthIndefinite("Error when trying to disconnect from $wifiDirectGroup, failed because: $failureReason")
					}

				})
			}
		}
	}

	private fun getFailureReasonForWiFiDirectActionListener(reason: Int): String {
		return when (reason) {
			WifiP2pManager.ERROR -> {
				"Internal Error"
			}
			WifiP2pManager.P2P_UNSUPPORTED -> {
				"WiFi Direct unsupported on this device"
			}
			// TODO if this happens every method that received this should try again later
			WifiP2pManager.BUSY -> {
				"Framework is busy and unable to service the request"
			}
			// Undocumented in `onFailure` method of ActionListener, but can be returned nonetheless when
			// discoverServices(Channel, ActionListener) is called without adding service requests.
			WifiP2pManager.NO_SERVICE_REQUESTS -> {
				"discoverServices(Channel, ActionListener) failed because no service requests were added"
			}
			else -> {
				"Unknown failure reason"
			}
		}
	}

	private fun showSnackbarLengthIndefinite(text: String): Unit {
		Snackbar.make(
			root_coordinator_layout,
			text,
			Snackbar.LENGTH_INDEFINITE
		).show()
	}

	// Called as callback when another Activity was started with `startActivityForResult`
	override fun onActivityResult(requestCode: Int, resultCode: Int, resultIntentWithData: Intent?): Unit {
		// super method called so it can pass results through to correct fragment in this activity that started the request with
		// `startActivityForResult`, but does not handle nested fragments correctly!
		// So those should call `startActivityForResult` from the parent fragment which handles passing it through then
		super.onActivityResult(requestCode, resultCode, resultIntentWithData)

		Log.d(LOG_TAG, "onActivityResult called")

		// if it was answer to opening a file
		if (requestCode == OPEN_FILE_WITH_FILE_CHOOSER_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
			// The document selected by the user won't be returned in the intent.
			// Instead, a URI to that document will be contained in the return intent
			// provided to this method as a parameter.
			val uriOfSelectedFile: Uri = resultIntentWithData?.data?.also { uri ->
				Log.v(LOG_TAG, "Uri of file to send, chosen by user: $uri")
				dumpContentUriMetaData(uri)
			} ?: TODO("handle error, did not receive uri of content in intent data from the system file chooser")


			Log.v(LOG_TAG, "Currently connected WiFi Direct Device Info: $wiFiDirectGroupInfo")

			// use the group owner address if we are the client and the client address if we are the group owner
			// we only know the client address if the client connected to us and we saved the address

			val connectedDeviceIpAddress: InetAddress? = if (wiFiDirectGroupInfo?.isGroupOwner == true) {
				connectedClientWiFiDirectIpAddress
			} else { // other device is group owner
				wiFiDirectGroupInfo?.groupOwnerAddress

			}

			if (connectedDeviceIpAddress == null) {
				// make log and ui error more clear depending on if we are the group owner
				Log.w(
					LOG_TAG, "Connection to wifi direct peer lost while user chose a file to send! or we don't know the ip address " +
							"of the wifi direct group client yet!"
				)
				Snackbar.make(
					root_coordinator_layout,
					// could also be that the other device has not connected to us yet but the wifi direct connection is fine
					getString(R.string.wifi_direct_connection_lost_please_connect_again),
					Snackbar.LENGTH_LONG
				).show()
				return
			}

			// start the Android Service for sending the file and pass it what to send and where
			val sendFileServiceIntent: Intent = Intent(this, SendFileOrNotifyOfIpAddressIntentService::class.java).apply {
				action = SendFileOrNotifyOfIpAddressIntentService.ACTION_SEND_FILE
				data = uriOfSelectedFile
				putExtra(SendFileOrNotifyOfIpAddressIntentService.EXTRAS_OTHER_DEVICE_IP_ADDRESS, connectedDeviceIpAddress.hostAddress)
			}

			startService(sendFileServiceIntent)
		}
	}


	fun dumpContentUriMetaData(uriWithContentScheme: Uri): Unit {

		if (uriWithContentScheme.scheme != "content") {
			Log.w(LOG_TAG, "Cannot currently dump metadata of uris that do not have the content uri scheme!")
			return
		}

		// The query, since it only applies to a single document, will only return
		// one row. There's no need to filter, sort, or select fields, since we want
		// all fields for one document.
		// use projection to only needed columns needed to not waste resources?
		val cursor: Cursor? = contentResolver.query(uriWithContentScheme, null, null, null, null, null)

		cursor?.use {
			// moveToFirst() returns false if the cursor has 0 rows.  Very handy for
			// "if there's anything to look at, look at it" conditionals.
			if (it.moveToFirst()) {

				// Note it's called "Display Name". This is
				// provider-specific, and might not necessarily be the file name.
				// TODO handle potential exception and null return value
				val displayName =
					it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
				Log.v(LOG_TAG, "Display Name: $displayName")

				// returns -1 if column with this name cannot be found
				val columnIndexForFileSize: Int = it.getColumnIndex(OpenableColumns.SIZE).also { columnIndex ->
					if (columnIndex == -1) {
						TODO("handle error, cannot find SIZE column in content resolver of the uri")
					}
				}

				// If the size is unknown, the value stored is null.  But since an
				// int can't be null in Java, the behavior is implementation-specific,
				// which is just a fancy term for "unpredictable".  So as
				// a rule, check if it's null before assigning to an int.  This will
				// happen often:  The storage API allows for remote files, whose
				// size might not be locally known.
				val fileSize = if (!it.isNull(columnIndexForFileSize)) {
					// Technically the column stores an int, but cursor.getString()
					// will do the conversion automatically.
					// TODO handle potential exception (and null return value?) if for example file size is unknown
					it.getString(columnIndexForFileSize)
				} else {
					"Unknown"
				}
				Log.v(LOG_TAG, "File size: $fileSize bytes")
				// everything went well, no uncaught errors, return without logging an error
				return
			}
		}
		Log.w(LOG_TAG, "couldn't get metadata of file with uri $uriWithContentScheme")
	}
}