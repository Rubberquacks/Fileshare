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
	override fun onListFragmentInteraction(clickedWiFiDirectDevice: WifiP2pDevice) {
		clickedWiFiDirectDevice.let { clickedDeviceItem ->
			Log.i(LOGGING_TAG, "$clickedDeviceItem\n has been clicked")
			connectToWiFiDirectDevice(clickedWiFiDirectDevice)
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

			// Add other 'when' lines to check for other
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
			Log.d(LOGGING_TAG, "unregistering wifi direct broadcast receiver: $broadcastReceiver")
			unregisterReceiver(broadcastReceiver)
		}
	}


	// TODO consider using ACTION_GET_CONTENT because we only need a copy and not permanent access to the file if it changes and/or modify the file and write it back
	//  https://developer.android.com/guide/topics/providers/document-provider#client
	// Fires an intent to spin up the "file chooser" UI and select a file.
	private fun getOpenableFilePickedByUser() {

		// ACTION_OPEN_DOCUMENT is the intent to choose a file via the system's file
		// browser.
		val intent: Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			// Filter to only show results that can be "opened", such as a
			// file (as opposed to a list of contacts or timezones)
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
				this.dumpImageMetaData(uri)
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

	fun dumpImageMetaData(uriToImage: Uri) {
		// The query, since it only applies to a single document, will only return
		// one row. There's no need to filter, sort, or select fields, since we want
		// all fields for one document.
		val cursor: Cursor? = contentResolver.query(uriToImage, null, null, null, null, null)

		cursor?.use {
			// moveToFirst() returns false if the cursor has 0 rows.  Very handy for
			// "if there's anything to look at, look at it" conditionals.
			if (it.moveToFirst()) {

				// Note it's called "Display Name". This is
				// provider-specific, and might not necessarily be the file name.
				val displayName: String =
					it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
				Log.v(LOGGING_TAG, "Display Name: $displayName")

				val sizeIndex: Int = it.getColumnIndex(OpenableColumns.SIZE)
				// If the size is unknown, the value stored is null.  But since an
				// int can't be null in Java, the behavior is implementation-specific,
				// which is just a fancy term for "unpredictable".  So as
				// a rule, check if it's null before assigning to an int.  This will
				// happen often:  The storage API allows for remote files, whose
				// size might not be locally known.
				val fileSize: String = if (!it.isNull(sizeIndex)) {
					// Technically the column stores an int, but cursor.getString()
					// will do the conversion automatically.
					it.getString(sizeIndex)
				} else {
					"Unknown"
				}
				Log.v(LOGGING_TAG, "File size: $fileSize")
			}
		}
	}


}


fun copyFile(inputStream: InputStream, out: OutputStream): Boolean {
	// TODO good buffer size for speed?
	// this only determines how much is read and then written to the output stream before new data is read, so this does not limit
	// transferable file size
	val buffer = ByteArray(1024)
	var numberOfBytesRead: Int
	try {
		while (inputStream.read(buffer).also { numberOfBytesRead = it } != -1) {
			out.write(buffer, 0, numberOfBytesRead)
		}
		out.close()
		inputStream.close()
	} catch (e: IOException) {
		Log.e(LOGGING_TAG, e.toString())
		return false
	}
	return true
}


// receives a file sent via a network socket
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
		val serverSocket = ServerSocket(SERVER_PORT)

		// Wait for client connections. This call blocks until a connection is accepted from a client.
		val socketToConnectedClient = serverSocket.accept()

		// If this code is reached, a client has connected and transferred data
		// Save the input stream from the client as a JPEG file as proof of concept

		Log.d(LOGGING_TAG, "Client connected on port $SERVER_PORT")
		// open/create subdirectory only readable by our own app in app private storage, beware that this is not created in the nobackup
		// directory so if we use backup, we should not backup those big files in this subdirectory
		// TODO POTENTIAL BREAKAGE filesDir gives us a subdirectory in the data directory named "files", we use this name in the content
		//  provider sharable file paths definition, so if the name is different on an android version, the app breaks
		val receivedFilesDirectory: File = File(context.filesDir, RECEIVED_FILES_DIRECTORY_NAME)

		val destinationFile = File(
			// TODO replace by letting user choose where to save or starting download while letting user choose or letting user choose
			//  afterwards if wants to view, edit etc file, but choosing save location might make sense earlier. view, save, edit etc shortcuts
			//  maybe also in transfer complete notification as action
			receivedFilesDirectory.absolutePath +
				"/wifi_direct_shared-${System.currentTimeMillis()}.jpg")

		// TODO generally handle parent directory creation better and handle errors when we can't create every parent directory
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
		destinationFile.createNewFile()
		val inputStream = socketToConnectedClient.getInputStream()
		// TODO handle interrupted and partial transfers correctly, don't display / save
		copyFile(inputStream, FileOutputStream(destinationFile))
		serverSocket.close()

		return destinationFile.absolutePath
	}

	private fun File.doesNotExist(): Boolean = !exists()

	/**
	 * Start activity that can handle the JPEG image
	 */
	override fun onPostExecute(resultPathReceivedFile: String?) {
		resultPathReceivedFile?.run {


			Log.d(LOGGING_TAG, "Received file and successfully written to $resultPathReceivedFile")
			statusText.text = "File received: $resultPathReceivedFile"


			val uriToFile: Uri = Uri.parse("file://$resultPathReceivedFile")

			// WiFiDirectBroadcastReceiver passes the main activity as the context
			// TODO this is ugly, extract method as general helper (for a context, because of content provider) or something
			val mainActivity = context as MainActivity
			mainActivity.dumpImageMetaData(uriToFile)


			// TODO change type according to received file, image only used for testing!!!
			val FILE_MIME_TYPE = "image/*"

			val viewIntent: Intent = Intent(Intent.ACTION_VIEW)

			// use content provider for content:// uri scheme used in newer versions for modern working secure sharing of files with other apps,
			// but not all apps might support those instead of file:// uris, so still use them for older versions where they work
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				// needed for app displaying the file having the temporary access to read from this uri, either uri must be put in data of intent
				// or `Context.grantUriPermission` must be called for the target package
				viewIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
				// the authority argument of `getUriForFile` must be the same as the authority of the file provider defined in the AndroidManifest!
				val contentProviderUri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", File(resultPathReceivedFile))
				// TODO normalize data and type by using normalize method equivalent?
        viewIntent.setDataAndType(contentProviderUri, FILE_MIME_TYPE)
      } else {
				// TODO normalize data and type by using normalize method equivalent?
        viewIntent.setDataAndType(uriToFile, FILE_MIME_TYPE)
      }

			// TODO handle activity not found exception for when no app is found being able to display the file
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
			socket.bind(null)
			// TODO handle connection timing out
			socket.connect((InetSocketAddress(groupOwnerIpAddress, serverPort)), SOCKET_TIMEOUT_MILLISECONDS)


			// Create a byte stream from the file and pipe it to the output stream
			// of the socket. This data is retrieved by the server device.
			val outputStream: OutputStream = socket.getOutputStream()
			val inputStream: InputStream = contentResolver.openInputStream(dataUri) ?: TODO("handle error")
			copyFile(inputStream, outputStream)
			// TODO really close here and not in finally? use java try with resources / python with equivalent?
			outputStream.close()
			inputStream.close()
		} catch (e: FileNotFoundException) {
			TODO("handle error appropriately")
		} catch (e: IOException) {
			TODO("handle error appropriately")
		} finally {
			// Clean up any open sockets when done
			// transferring or if an exception occurred.
			if (socket.isConnected) {
				socket.close()
			}
		}

	}
}

