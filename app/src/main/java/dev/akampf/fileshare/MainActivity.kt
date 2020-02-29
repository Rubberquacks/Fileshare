package dev.akampf.fileshare

// `import kotlinx.android.synthetic.main.activity_main.*` is used to access views directly by their id as the variable name
// without `findViewByID()`, uses `kotlin-android-extensions` which does lookup and caching for us
import android.Manifest
import android.app.Activity
import android.app.IntentService
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.net.wifi.p2p.*
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.*
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket


private const val OPEN_FILE_WITH_FILE_CHOOSER_REQUEST_CODE: Int = 42
private const val ACCESS_FINE_LOCATION_PERMISSION_REQUEST_CODE: Int = 52


private const val ACCESS_FINE_LOCATION_PERMISSION: String = Manifest.permission.ACCESS_FINE_LOCATION

private const val LOGGING_TAG: String = "WiFiDirectMainActivity"

class MainActivity : AppCompatActivity(), DeviceFragment.OnListFragmentInteractionListener {

	// TODO register callback when connection to system WiFi Direct maneger gets lost and handle appropriately
	private val mWiFiDirectManager: WifiP2pManager? by lazy(LazyThreadSafetyMode.NONE) {
		getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
	}

	var connectedWiFiDirectInfo: WifiP2pInfo? = null

	private val mWiFiDirectIntentFilter = IntentFilter().apply {
		// Indicates a change in the Wi-Fi P2P status.
		addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
		// Indicates a change in the list of available peers.
		addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
		// Indicates the state of Wi-Fi P2P connectivity has changed.
		addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
		// Indicates this device's details have changed.
		addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
	}

	private var mChannel: WifiP2pManager.Channel? = null
	private var mWiFiDirectBroadcastReceiver: BroadcastReceiver? = null

	// TODO can permission be revoked without onResume being called again, do we need to check permissions after checking in onResume that
	//  we have the permission?
	// TODO how to handle if later wifi is deactivated when we connect to a device or are already connected etc
	// can we distinguish disabled and unavailable? is it unavailable on supported devices sometimes even?
	var mWiFiDirectEnabled: Boolean = false
		set(wiFiDirectEnabled) {


			wiFi_direct_status_text_view.text = if (wiFiDirectEnabled) getText(R.string.wifi_direct_enabled) else getText(R.string.wifi_direct_disabled)
			if (wiFiDirectEnabled && mFineLocationPermissionGranted) {
				// TODO maybe request again if not granted? think about at what times request is appropriate or just make button after first request
				discoverWiFiDirectPeers()
			}

			field = wiFiDirectEnabled
		}

	// TODO we should also check if location services are enabled by the user and prompt for that if not the case
	// TODO luckily setter is not called on initialization with false, since this could be wrong (permission already granted), but still
	//  initialization with false does not really seem clean, maybe initialize with null if not accessed except in setter (no null safety)?
	private var mFineLocationPermissionGranted: Boolean = false
		set(fineLocationPermissionGranted_new_value) {

			if (fineLocationPermissionGranted_new_value) {
				if (mWiFiDirectEnabled) {
					discoverWiFiDirectPeers()
				}
				// TODO notify that wifi needs to be enabled after location permission was granted or just enable it yourself and notify about it?
			}
			else {
				// TODO when (first) requesting permission, display sth like "requesting permission" and not "denied", cause that seems harsh and
				//  users might see this behind the permission request dialog, "requesting" seems more appropriate to convey the state, even if
				//  denied (from the system) is technically correct
				wiFi_direct_status_text_view.text = getText(R.string.wifi_direct_location_permission_denied)
			}
			// set field to new value at the end so we can use the old value before for comparing if it changed or was the same before this setter
			// was called / the property was set
			field = fineLocationPermissionGranted_new_value
		}

	lateinit var deviceFragment: DeviceFragment

	val wiFiDirectPeers = mutableListOf<WifiP2pDevice>()

	fun notifyWiFiDirectPeerListDiscoveryFinished(discoveredPeers: WifiP2pDeviceList) {
		Log.i(LOGGING_TAG, "Discovered WiFi Direct peers:\n$discoveredPeers")

		// TODO sort or even filter by device type: https://www.wifi-libre.com/img/members/3/Wi-Fi_Simple_Configuration_Technical_Specification_v2_0_5.pdf


		// TODO why not work directly with WifiP2pDeviceList ? current code from:
		//  https://developer.android.com/training/connect-devices-wirelessly/wifi-direct#fetch
		val refreshedPeers = discoveredPeers.deviceList
		if (refreshedPeers != wiFiDirectPeers) {
			wiFiDirectPeers.clear()
			wiFiDirectPeers.addAll(refreshedPeers)

			// TODO move peer list management to fragment to make management of ui changes easier?
			//  or at least use https://developer.android.com/guide/components/fragments#CommunicatingWithActivity or
			//  https://developer.android.com/reference/kotlin/androidx/fragment/app/FragmentManager.html to interact with fragment?
			// Tell the RecyclerViewAdapter that is backed by this data that it changed so it can update the view
			deviceFragment.recyclerViewAdapter.notifyDataSetChanged()
		}

		if (wiFiDirectPeers.isEmpty()) {
			Log.d(LOGGING_TAG, "No WiFi Direct devices found in current scan")
		}
	}


	// the RecyclerView in the fragment calls this method when a view in it was clicked
	override fun onListFragmentInteraction(wiFiDirectDevice: WifiP2pDevice) {
		wiFiDirectDevice.let { clickedDeviceItem ->
			Log.i(LOGGING_TAG, "$clickedDeviceItem\n has been clicked")
			connectToWiFiDirectDevice(wiFiDirectDevice)
		}
	}

	private fun connectToWiFiDirectDevice(wiFiDirectDeviceToConnectTo: WifiP2pDevice) {

		val wiFiDirectConnectionConfig = WifiP2pConfig().apply {
			deviceAddress = wiFiDirectDeviceToConnectTo.deviceAddress
			// TODO use other wps setup method if PBC not supported or is PBC always supported by laptops, tablets, phones, etc?
			//  WpsInfo.PBC is default value
			// wps.setup = here_is_the_mode_to_use
		}

		// initiates WiFi Direct group negotiation with target or invite device to existing group where this device is already part of (because
		// it has joined or has created the group itself)
		// TODO at this point another device was found, so the manager was clearly initiated, should we just assume that, assert that or catch
		//  the case that it was null and display an error / handle it appropriately?
		mChannel.also {
			// TODO here https://developer.android.com/training/connect-devices-wirelessly/wifi-direct#connect it is done without ?. (safe call) /
			//  being safe that mChannel is null and ide also does not complain that mChannel is of nullable type, even though it
			//  complains about that when initializing, even though there the manager should be not null after initialization the channel with it
			//  and only doing sth with the channel if it is not null and using the manager there

			// TODO linter says this and other methods called need permission check, when / how often do we need to check these permissions?
			mWiFiDirectManager?.connect(mChannel, wiFiDirectConnectionConfig, object: WifiP2pManager.ActionListener {

				override fun onSuccess() {
					Log.d(LOGGING_TAG, "Initiation of connection to $wiFiDirectDeviceToConnectTo succeeded")
					// We get notified by broadcast when the connection is really there
				}

				override fun onFailure(reason: Int) {
					// TODO handle reason nicely, display it to user
					//  reason 	int: The reason for failure could be one of WifiP2pManager.P2P_UNSUPPORTED, WifiP2pManager.ERROR or WifiP2pManager.BUSY
					//  https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager.ActionListener.html#onFailure(int)
					Log.i(
						LOGGING_TAG,
						"Initiation of connection to $wiFiDirectDeviceToConnectTo failed with failure code $reason TODO parse error code"
					)
					Snackbar.make(
						root_coordinator_layout,
						getString(R.string.wifi_direct_connection_initiation_failed, wiFiDirectDeviceToConnectTo.deviceName, wiFiDirectDeviceToConnectTo.deviceAddress, reason),
						Snackbar.LENGTH_LONG
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

	/**
	 * Request Fine Location permission
	 */
	private fun requestFineLocationPermissionForWiFiDirect() {

		// Should we show an explanation?
		if (ActivityCompat.shouldShowRequestPermissionRationale(this,
				ACCESS_FINE_LOCATION_PERMISSION)) {
			Log.i(LOGGING_TAG, "Fine Location permission was denied in the past! TODO: show an explanation before retrying")
			// TODO: Show an explanation to the user *asynchronously* -- don't block
			//  this thread waiting for the user's response! After the user
			//  sees the explanation, try again to request the permission.

			// this will be removed and put in the callback of the explanation window/popup/whatever
			ActivityCompat.requestPermissions(this,
				arrayOf(ACCESS_FINE_LOCATION_PERMISSION),
				ACCESS_FINE_LOCATION_PERMISSION_REQUEST_CODE)

		} else {
			// TODO maybe show rationale for location for WiFi Direct every time
			Log.i(LOGGING_TAG, "Fine Location permission was not denied in the past, request permission directly " +
					"without showing rationale. TODO maybe show rationale for location for WiFi Direct every time")
			// No explanation needed, we can request the permission.
			ActivityCompat.requestPermissions(this,
				arrayOf(ACCESS_FINE_LOCATION_PERMISSION),
				ACCESS_FINE_LOCATION_PERMISSION_REQUEST_CODE)
			// The callback method gets the result of the request providing the same request code.
		}
	}

	private fun initializeWiFiDirect() {
		// Registers the application with the Wi-Fi Direct framework to do any further operations.

		// TODO: do we need to do all of this even when only onResume is executed and not onCreate? according to examples, this is not the case
		// this only needs `android.permission.ACCESS_WIFI_STATE` so we wan safely call it without location permission, cause
		// `android.permission.ACCESS_WIFI_STATE` was granted at install time
		// TODO should we deal with frameworks that revoke this? possible to check this permission like checking e.g. location permission?

		// TODO set listener to be notified of loss of framework communication?
		mChannel = mWiFiDirectManager?.initialize(this, mainLooper, null)
		mChannel?.let { channel ->
			// If mChannel was not null, we are already sure manager was not null, too, because of the mWiFiDirectManager?.initialize() call
			// above only being executed when mWiFiDirectManager was not null. So we cast it to not optional type with the
			// characters !! (assert non-null).
			// TODO report to Kotlin?
			mWiFiDirectBroadcastReceiver = WiFiDirectBroadcastReceiver(mWiFiDirectManager!!, channel, this)

		}
	}

	// result callback from requesting dangerous permission access from the system, e.g. Location for WiFi Direct
	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
		when (requestCode) {
			ACCESS_FINE_LOCATION_PERMISSION_REQUEST_CODE -> {
				// If request is cancelled, the result arrays are empty.
				if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
					// permission was granted, yay! Do the
					// permission related task you need to do.
					mFineLocationPermissionGranted = true


				} else {
					Log.w(LOGGING_TAG, "Fine Location permission denied, cannot use WiFi Direct! TODO: disable all functionality dependent on that")
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


	private fun discoverWiFiDirectPeers() {

    // This only initiates the discovery, this method immediately returns.
    // The discovery remains active in the background until a connection is initiated or a p2p group is formed.
    mWiFiDirectManager?.discoverPeers(mChannel, object: WifiP2pManager.ActionListener {

	    // success initiating the scan for peers
	    override fun onSuccess() {
	      Log.i(LOGGING_TAG, "Initiating peer discovery successful")
	      // In the future, if the discovery process succeeds and detects peers, the system broadcasts the
	      // WIFI_P2P_PEERS_CHANGED_ACTION intent, which we can listen for in a broadcast receiver to then obtain a list of peers.
	    }

	    // failed to initiate the scan for peers
	    override fun onFailure(reasonCode: Int) {
	      // TODO handle reason
	      //  reason 	int: The reason for failure could be one of WifiP2pManager.P2P_UNSUPPORTED, WifiP2pManager.ERROR or WifiP2pManager.BUSY
	      //  https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager.ActionListener.html#onFailure(int)

	      Log.w(LOGGING_TAG, "Initiating peer discovery failed")
	    }
    })
	}


	override fun onCreate(savedInstanceState: Bundle?) {
		Log.d(LOGGING_TAG, "onCreate started")
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		initializeWiFiDirect()
	}


	override fun onResume() {
		Log.v(LOGGING_TAG, "onResume started")
		super.onResume()


		Log.d(LOGGING_TAG, "registering wifi direct broadcast receiver... : $mWiFiDirectBroadcastReceiver")
		registerReceiver(mWiFiDirectBroadcastReceiver, mWiFiDirectIntentFilter)

		val haveFineLocationPermission = havePermissionCurrently(ACCESS_FINE_LOCATION_PERMISSION)

		if (haveFineLocationPermission) {
			Log.d(LOGGING_TAG, "We already have Fine Location permission")
			mFineLocationPermissionGranted = true
		} else {
			mFineLocationPermissionGranted = false
			// TODO permission is requested again after being denied because onResume is executed again, leave for now and change to manually
			//  requesting / explaining how to grant in settings when denied once or twice.
			//  Move all code dependant on this state to setter?
			requestFineLocationPermissionForWiFiDirect()
		}
	}

	override fun onPause() {
		Log.d(LOGGING_TAG, "onPause start")
		super.onPause()
		mWiFiDirectBroadcastReceiver?.also { broadcastReceiver ->
			Log.d(LOGGING_TAG, "unregistering wifi direct broadcast receiver... : $broadcastReceiver")
			unregisterReceiver(broadcastReceiver)
		}
	}


	// TODO consider using ACTION_GET_CONTENT because we only need a copy and not permanent access to the file if it changes and/or modify the file and write it back
	//  If sending big file, how long is access guaranteed? copy to own directory first if space available? better for retrying later? better solution?
	//  https://developer.android.com/guide/topics/providers/document-provider#client
	// Fires an intent to spin up the "file chooser" UI and select a file.
	private fun getOpenableFilePickedByUser() {

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

		startActivityForResult(intent, OPEN_FILE_WITH_FILE_CHOOSER_REQUEST_CODE)
	}


	fun onClickedOpenFileButton(view: View) {
		// result is delivered in callback
		this.getOpenableFilePickedByUser()

	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, resultIntentWithData: Intent?) {
		// TODO linter says we should call the superclass here, but official android example does not show it
		//  https://developer.android.com/training/data-storage/shared/documents-files#perform-operations

		// The ACTION_OPEN_DOCUMENT intent was sent with the request code
		// READ_REQUEST_CODE. If the request code seen here doesn't match, it's the
		// response to some other intent, and the code below shouldn't run at all.

		if (requestCode == OPEN_FILE_WITH_FILE_CHOOSER_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
			// The document selected by the user won't be returned in the intent.
			// Instead, a URI to that document will be contained in the return intent
			// provided to this method as a parameter.
			val uriOfSelectedFile: Uri = resultIntentWithData?.data?.also { uri ->
				Log.v(LOGGING_TAG, "Uri: $uri")
				this.dumpContentUriMetaData(uri)
			} ?: TODO("handle error")


			Log.d(LOGGING_TAG, "Currently connected WiFi Direct Device Info: $connectedWiFiDirectInfo")

			val groupOwnerIpAddress: String = connectedWiFiDirectInfo?.groupOwnerAddress?.hostAddress ?: TODO("handle error when not already connected / change app control and navigation flow")

			// TODO extract in variable to use here and in server socket creation
			val SERVER_PORT: Int = 8888

			// start the Android Service for sending the file and pass it what to send and where
			val intent: Intent = Intent(this, SendFileIntentService::class.java).apply {
				action = SendFileIntentService.ACTION_SEND_FILE
				data = uriOfSelectedFile
				putExtra(SendFileIntentService.EXTRAS_GROUP_OWNER_ADDRESS, groupOwnerIpAddress)
				putExtra(SendFileIntentService.EXTRAS_GROUP_OWNER_PORT, SERVER_PORT)
			}

			// TODO convert to foreground service and register right permission for it
			startService(intent)
		}
	}

	fun dumpContentUriMetaData(uriToImage: Uri) {
		// The query, since it only applies to a single document, will only return
		// one row. There's no need to filter, sort, or select fields, since we want
		// all fields for one document.
		// TODO use projection to only needed columns needed to not waste resources
		val cursor: Cursor? = contentResolver.query(uriToImage, null, null, null, null, null)

		cursor?.use {
			// moveToFirst() returns false if the cursor has 0 rows.  Very handy for
			// "if there's anything to look at, look at it" conditionals.
			if (it.moveToFirst()) {

				// Note it's called "Display Name". This is
				// provider-specific, and might not necessarily be the file name.
				// TODO handle potential exception and null return value
				val displayName=
					it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
				Log.v(LOGGING_TAG, "Display Name: $displayName")

				// returns -1 if column with this name cannot be found
				val columnIndexForFileSize: Int = it.getColumnIndex(OpenableColumns.SIZE).also { columnIndex -> if(columnIndex == -1) {TODO("handle error")} }

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
				Log.v(LOGGING_TAG, "File size: $fileSize bytes")
				// everything went well, no uncaught errors, return without logging an error
				return
			}
		}
		Log.w(LOGGING_TAG, "couldn't get metadata of file with uri $uriToImage")
	}
}


fun getDisplayNameFromUri(context: Context, uri: Uri): String {

	var displayName: String? = null


	// TODO use projection for performance
	// this returns null if the uri does not use the content:// scheme
	val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null, null)

	if (cursor?.moveToFirst() == true) {
		// Note it's called "Display Name". This is
		// provider-specific, and might not necessarily be the file name.
		val columnIndex: Int = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
		if (columnIndex == -1) {
			Log.w(LOGGING_TAG, "There is no DISPLAY_NAME for the queried uri in the associated content resolver. Queried uri: $uri")
		} else {
			try {
				val displayNameFromContentResolver: String? = cursor.getString(columnIndex)
				if (displayNameFromContentResolver == null) {
					Log.w(LOGGING_TAG, "")
					Log.w(LOGGING_TAG, "DISPLAY_NAME value null returned from content resolver associated with uri: $uri")
				} else {
					// TODO don't use this value when it is an empty string?
					displayName = displayNameFromContentResolver
				}

				// depending on implementation, `cursor.getString()` might throw an exception when column value is not a String or null
			} catch (e: Exception) {
				Log.w(LOGGING_TAG, "Error when getting DISPLAY_NAME, even though the column exists in content resolver associated with uri: $uri")
				Log.w(LOGGING_TAG, e.toString())
				Log.w(LOGGING_TAG, e.stackTrace.toString())
			}
		}
	}
	cursor?.close()

	// fallback name if we could not get a display name from the content resolver
	if (displayName == null) {
		// uri.toString() is guaranteed to not return null, so we always return a non-null string, but possibly empty TODO change this?
		displayName = uri.lastPathSegment ?: uri.encodedPath ?: uri.toString()
	}


	return displayName

}
// TODO add flush at the end?
fun copyBetweenByteStreamsAndFlush(inputStream: InputStream, outputStream: OutputStream): Boolean {
	// TODO good buffer size for speed? just use `BufferedOutputStream` and `BufferedInputStream` instead of own buffer?
	// this only determines how much is read and then written to the output stream before new data is read, so this does not limit
	// transferable file size
	val buffer = ByteArray(1024)
	var numberOfBytesRead: Int
	try {
		while (inputStream.read(buffer).also { numberOfBytesRead = it } != -1) {
			// read bytes from buffer and write to the output stream with 0 offset
			outputStream.write(buffer, 0, numberOfBytesRead)
		}
		// flush stream after writing data in case it was called with a buffered output stream and not a raw
		// output stream, where flushing does nothing
		outputStream.flush()
	} catch (e: IOException) {
		Log.e(LOGGING_TAG, "TODO handle error: $e")
		Log.e(LOGGING_TAG, e.stackTrace.toString())
		return false
	}
	return true
}


// receives a file sent via a network socket, currently only works for receiving files, for sending the other device must be the server
// TODO convert to foreground service that keeps running when app exits
class FileServerAsyncTask(
	private val context: Context,
	private var statusText: TextView
) : AsyncTask<Void, Void, String?>() {

	companion object {

		const val RECEIVED_FILES_DIRECTORY_NAME: String = "received_files"
	}

	override fun doInBackground(vararg params: Void): String? {

		val SERVER_PORT: Int = 8888


		// TODO handle io exceptions, wrap nearly all of code in this method in try except and handle important exceptions for status reporting
		//  separately like rethrowing own exception which is handled in own catch clause or saving failure status in variable?


		Log.i(LOGGING_TAG, "Starting server socket on port $SERVER_PORT")
		// Consider using less used but fixed port, ephemeral port, or fixed and likely not blocked port. Fixed might be better for remote
		// searching through network for available devices.
		val serverSocket = ServerSocket(SERVER_PORT)

		Log.i(LOGGING_TAG, "We are now accepting connections to ip ${serverSocket.inetAddress} on port ${serverSocket.localPort}.")
		// Wait for client connections. This call blocks until a connection from a client is accepted.
		val socketToConnectedClient = serverSocket.accept()
		Log.i(LOGGING_TAG, "Connection from client with ip ${socketToConnectedClient.inetAddress} from port ${socketToConnectedClient.port} accepted.")




		// Save the input stream from the client as a file in internal app directory as proof of concept



		val inputStreamFromConnectedDevice = socketToConnectedClient.getInputStream()

		val bufferedInputStream: BufferedInputStream = BufferedInputStream(inputStreamFromConnectedDevice)
		val dataInputStream: DataInputStream = DataInputStream(bufferedInputStream)

		// read file name with readUTF function for now, then create file in filesystem to write received data to
		// TODO this is user supplied data transferred over to this device, we can't trust its content, how to escape properly for using as
		//  file name for saving to disk etc? what if contains illegal characters for filename, like slash, or just non-printable ones?
		//  what if file name is the empty string?
		val fileName: String = dataInputStream.readUTF()



		// open/create subdirectory only readable by our own app in app private storage, note that this is not created in the `nobackup`
		// directory so if we use backup, we should not backup those big files in this subdirectory
		// TODO POTENTIAL BREAKAGE filesDir gives us a subdirectory in the data directory named "files", we use this name in the content
		//  provider (file provider) sharable file paths definition, so if the name is different on another android version, the app breaks
		val receivedFilesDirectory: File = File(context.filesDir, RECEIVED_FILES_DIRECTORY_NAME)

		val destinationFile = File(
			// TODO replace by letting user choose where to save or starting download while letting user choose or letting user choose
			//  afterwards if wants to view, edit etc file, but choosing save location might make sense earlier. view, save, edit etc shortcuts
			//  maybe also in transfer complete notification as action
			receivedFilesDirectory.absolutePath, fileName)

		// TODO generally handle parent directory creation better and handle errors when we can't create every parent directory, throw error
		//  and exit app when parent directory that should always exist is not there
		if (destinationFile.parentFile?.doesNotExist() == true) {
			// TODO why assert non-null needed, shouldn't this condition return false when parent file is null?
			destinationFile.parentFile!!.mkdir()
		}

		/* possibly needed if creating deeper nested folder hierarchies
		destinationFile.parent?.also {parent ->
			val dirs = File(parent)
			Log.d(LOGGING_TAG, "dirs from File(parent): $dirs")
			if (dirs.doesNotExist()) {
				Log.d(LOGGING_TAG, "creating parent directories for saving file")
				dirs.mkdirs()
			}
		}
		*/

		// TODO handle when file already exists, but multiple transfers with same file name should be possible!
		//  consider locking file access?
		//  don't transfer file when it is the same file, check hash
		//  handle other io and security errors
		destinationFile.createNewFile()

		val fileOutputStreamToDestinationFile: FileOutputStream
		try {
			fileOutputStreamToDestinationFile = FileOutputStream(destinationFile)
		} catch (e: Exception) {
			// !!!
			// THIS CODE SHOULD NEVER BE REACHED!
			//
			// This should never happen as the path to the file is internally constructed in the app only influenced by the received name
			// for the content. The constructed path should always be existent and writable as it is in an app private directory. So neither a
			// FileNotFoundException nor a SecurityException should be possible to be thrown.
			// !!!
			Log.e(LOGGING_TAG, "Error on opening file in app private directory, which should always be possible!")
			Log.e(LOGGING_TAG, e.toString())
			Log.e(LOGGING_TAG, e.stackTrace.toString())
			// Something is severely wrong with the state of the app (maybe a remote device tried to attack us), so it should throw
			// an error which is NOT caught by some other code and exit the app, meaning we should not try to recover from an undefined state
			// or from an attack but we should fail safely by quitting
			Log.e(LOGGING_TAG, "App is in an undefined state, exiting app...")
			// TODO how to guarantee exiting all of the app without something intercepting it? ensure exceptions from this method are not handled
			throw e
		}




		// TODO handle interrupted and partial transfers correctly, don't display / save
		// TODO this is user supplied data transferred over to this device, we can't trust its content, should we do some checks for its
		//  content in general (anti-malware etc) or for methods and programs that use it like processing in this app, FileProvider, etc?
		val contentSuccessfullyReceived: Boolean = copyBetweenByteStreamsAndFlush(inputStreamFromConnectedDevice, fileOutputStreamToDestinationFile)


		// close the outermost stream to call its flush method before it in turn closes any underlying streams and used resources
		try {

			// TODO why is socket to connected client not closed after closing input stream of it?
			dataInputStream.close()

			fileOutputStreamToDestinationFile.close()

			// closes the server socket, which is still bound to the ip address and port to receive connections with `accept()`
			// this releases its resources and makes it possible to bind to this port and ip address again by this or other apps
			// This is separate from the socket to the connected client.
			serverSocket.close()
		} catch (e: IOException) {
			// error when closing streams and socket
			// TODO should this be ignored and is it bad when e.g. the output stream to the saved file has an error on close? just check
			//  integrity of received content after writing to file (not only when in memory)? still resource leak?
			Log.e(LOGGING_TAG, e.toString())
			Log.e(LOGGING_TAG, e.stackTrace.toString())
			throw e
		}




		if (contentSuccessfullyReceived) {
			Log.i(LOGGING_TAG, "File name and content successfully received and written, error on resources closing " +
					"might still have occurred")
		}

		return destinationFile.absolutePath
	}

	private fun File.doesNotExist(): Boolean = !exists()

	/**
	 * Start activity that can handle the JPEG image
	 */
	override fun onPostExecute(absolutePathReceivedFile: String?) {
		absolutePathReceivedFile?.run {


			Log.d(LOGGING_TAG, "File was written to $absolutePathReceivedFile")
			statusText.text = "File received: $absolutePathReceivedFile"


			val uriToFile: Uri = Uri.parse("file://$absolutePathReceivedFile")

			// WiFiDirectBroadcastReceiver passes the main activity as the context
			// TODO this is ugly, extract method as general helper (for a context, because of content provider) or just globally
			val mainActivity = context as MainActivity
			// TODO change to display info with other method cause this only works with the content:// scheme and not file:// urls!
			mainActivity.dumpContentUriMetaData(uriToFile)


			// open saved file for viewing in a supported app

			val viewIntent: Intent = Intent(Intent.ACTION_VIEW)

			// use content provider for content:// uri scheme used in newer versions for modern working secure sharing of files with other apps,
			// but not all apps might support those instead of file:// uris, so still use them for older versions where they work for greater
			// compatibility
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				// needed for app displaying the file having the temporary access to read from this uri, either uri must be put in data of intent
				// or `Context.grantUriPermission` must be called for the target package
				// TODO explain why this is needed / what exactly is needed more clearly
				viewIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION

				// the authority argument of `getUriForFile` must be the same as the authority of the file provider defined in the AndroidManifest!
				// TODO extract authority in global variable or resource to reuse in manifest and code
				val fileProviderUri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", File(absolutePathReceivedFile))

				// normalizing the uri to match android best practices for schemes: makes the scheme component lowercase
        viewIntent.setDataAndNormalize(fileProviderUri)
      } else {
				// normalizing the uri to match android best practices for schemes: makes the scheme component lowercase
        viewIntent.setDataAndNormalize(uriToFile)
      }

			// TODO handle activity not found exception which is thrown when no app is found being able to display the file
			context.startActivity(viewIntent)

		}
	}
}

// use IntentService for now for serial handling of requests on a worker thread without handling
// thread creation ourselves, also Android Jobs are not
// guaranteed to be executed immediately maybe (if app not in foreground at least?) and seem to have a execution limit of 10 min
// We also want a notification present for the transfer so we want to use a foreground service anyway
class SendFileIntentService: IntentService(SendFileIntentService::class.simpleName) {

	companion object {

		private const val SOCKET_TIMEOUT_MILLISECONDS: Int = 500
		// TODO make modular for changes in package name, maybe with global constant or string resource for package name?
		//  where and how to register and then use like registering as field of class in this way:
		//  val ACTION_SEND_FILE: String = "${applicationContext.packageName}.wifi_direct.SEND_FILE"
		const val ACTION_SEND_FILE: String = "dev.akampf.fileshare.wifi_direct.SEND_FILE"
		// TODO make modular to be usable with any target for the server connection, not only the group owner
		const val EXTRAS_GROUP_OWNER_ADDRESS: String = "group_owner_ip_address"
		const val EXTRAS_GROUP_OWNER_PORT: String = "group_owner_server_port"

	}
	override fun onHandleIntent(workIntent: Intent?) {
		// Gets data from the incoming Intent

		val dataUri = workIntent?.data ?: TODO("handle error")
		val serverPort: Int = workIntent.getIntExtra(EXTRAS_GROUP_OWNER_PORT, 0).also {
			if(it == 0) { TODO("handle error") }
		}
		val groupOwnerIpAddress = workIntent.getStringExtra(EXTRAS_GROUP_OWNER_ADDRESS) ?: TODO("handle error")


		val socket = Socket()

		try {
			// bind with a ip address and port given to us by the system cause we initiate the connection and don't care for the outgoing port
			// and ip address
			// "If the address is null, then the system will pick up an ephemeral port and a valid local address to bind the socket."
			// If not passing null, meaning specifying port and ip, we might need to catch an IllegalArgumentException!
			socket.bind(null)
			// TODO handle connection timing out
			socket.connect((InetSocketAddress(groupOwnerIpAddress, serverPort)), SOCKET_TIMEOUT_MILLISECONDS)


			// Currently, we first send the file name and then the raw file data. These data (name and content) are then retrieved by the server
			// socket.
			val outputStreamConnectedDevice: OutputStream = socket.getOutputStream()


			val bufferedOutputStream: BufferedOutputStream = BufferedOutputStream(outputStreamConnectedDevice)
			val dataOutputStream: DataOutputStream = DataOutputStream(bufferedOutputStream)



			// TODO don't use this when it is an empty string?
			val displayName = getDisplayNameFromUri(this, dataUri)
			Log.d(LOGGING_TAG, "Display name being sent is: $displayName")


			// this writes the length of the string and then a string of the display name of the content in "modified utf8"
			dataOutputStream.writeUTF(displayName)
			// Without this flush, the transfer will not work. Probably because the data output stream gets flushed on close and by then
			// the actual content is already written to the underlying output stream before the buffered data of the file name got written to it.
			// This results in the file name not being at the beginning of the data in the stream.
			// TODO But checking how many bytes were written on the underlying output stream suggests it is already written to it without the flush,
			//  so no idea if this really was the problem and this is the appropriate fix... :/
			dataOutputStream.flush()




			// Create a byte stream from the file and copy it to the output stream of the socket.
			val inputStreamOfContentToTransfer: InputStream = contentResolver.openInputStream(dataUri) ?: TODO("handle error")
			val wasContentSuccessfullyTransferred: Boolean = copyBetweenByteStreamsAndFlush(inputStreamOfContentToTransfer, outputStreamConnectedDevice)

			if (wasContentSuccessfullyTransferred) {
				Log.i(LOGGING_TAG, "File name and content successfully transferred, error on resources closing might still occur")
			}

			// only close outermost stream here because it will flush its content and also close all underlying streams, which in turn flushes
			// them, as well as releasing all used resources
			dataOutputStream.close()

			inputStreamOfContentToTransfer.close()
		} catch (e: FileNotFoundException) {
			Log.e(LOGGING_TAG, e.toString())
			Log.e(LOGGING_TAG, e.stackTrace.toString())
			TODO("handle error appropriately")
		} catch (e: IOException) {
			Log.e(LOGGING_TAG, e.toString())
			Log.e(LOGGING_TAG, e.stackTrace.toString())
			TODO("handle error appropriately")
		} finally {
			// Clean up any open sockets when done
			// transferring or if an exception occurred.
			// TODO close outermost stream here in finally instead to also close when exception while transferring (going in catch clause)
			//  or not important cause data is lost either way and real resource is only the socket and other streams will just be
			//  cleaned up when out of scope?? why not here? seems more clean
			if (socket.isConnected) {
				// TODO handle exception, keep exceptions from catch close by encapsulating or sth (adding to supressed excepitons list)
				//  so they are still viewable?
				socket.close()
			}
		}


	}
}

