package dev.akampf.fileshare

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat


private const val READ_REQUEST_CODE: Int = 42

private const val LOGGING_TAG: String = "own_logs"

class MainActivity : AppCompatActivity() {

	private val mWiFiDirectManager: WifiP2pManager? by lazy(LazyThreadSafetyMode.NONE) {
		getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
	}

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

	// can we distinguish disabled and unavailable? is it unavailable on supported devices sometimes even?
	var mWiFiDirectEnabled: Boolean = false
		set(wifiDirectEnabled) {
			field = wifiDirectEnabled
			val wifiDirectStateTextView = findViewById<TextView>(R.id.wifiDirectStatus)
            val wifiDirectStatePretty = if (wifiDirectEnabled) "Enabled" else "Disabled"
			wifiDirectStateTextView.text = "WiFi Direct: $wifiDirectStatePretty"
            if (wifiDirectEnabled) discoverWiFiDirectPeers()
		}


	fun wiFiDirectPeerListDiscoveryFinished(discoveredPeerList: WifiP2pDeviceList) {
		Log.i(LOGGING_TAG, discoveredPeerList.toString())
	}


	private fun haveFineLocationPermissionCurrently(): Boolean {
		return havePermissionCurrently(Manifest.permission.ACCESS_FINE_LOCATION)
	}

	/**
	 * If MainActivity currently has android permission.
	 *
	 * @param androidManifestPermission String in the Android.manifest.* namespace.
	 * @return if permission is currently available
	 * @see haveFineLocationPermissionCurrently
	 */
	private fun havePermissionCurrently(androidManifestPermission: String): Boolean {
		return ContextCompat.checkSelfPermission(this, androidManifestPermission) != PackageManager.PERMISSION_GRANTED

	}

    private fun discoverWiFiDirectPeers() {

        // This only initiates the discovery, this method immediately returns.
        // The discovery remains active until a connection is initiated or a p2p group is formed
        mWiFiDirectManager?.discoverPeers(mChannel, object : WifiP2pManager.ActionListener {

            // success initiating the scan for peers
            override fun onSuccess() {
                Log.i(LOGGING_TAG, "Initiating peer discovery successful")
                // In the future, if the discovery process succeeds and detects peers, the system broadcasts the
                // WIFI_P2P_PEERS_CHANGED_ACTION intent, which we can listen for in a broadcast receiver to then obtain a list of peers.
            }

            // failed to initiate the scan for peers
            override fun onFailure(reasonCode: Int) {
                // TODO handle reason
                // reason 	int: The reason for failure could be one of WifiP2pManager.P2P_UNSUPPORTED, WifiP2pManager.ERROR or WifiP2pManager.BUSY
                // https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager.ActionListener.html#onFailure(int)

                Log.w(LOGGING_TAG, "Initiating peer discovery failed")
            }
        })
    }


	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		Log.v(LOGGING_TAG, "Hello World!")


		mChannel = mWiFiDirectManager?.initialize(this, mainLooper, null)
		mChannel?.also { channel ->
			// if mChannel was not null, we are already sure manager was not null, too, because of the manager?.initialize() call
			// above only being executed then. So we cast it to not optional type with !!
			// TODO report to Kotlin?
			mWiFiDirectBroadcastReceiver = WiFiDirectBroadcastReceiver(mWiFiDirectManager!!, channel, this)
		}

	}


	/* register the broadcast receiver with the intent values to be matched */
	override fun onResume() {
		super.onResume()
		mWiFiDirectBroadcastReceiver?.also { receiver ->
			registerReceiver(receiver, mWiFiDirectIntentFilter)
		}
	}

	/* unregister the broadcast receiver */
	override fun onPause() {
		super.onPause()
		mWiFiDirectBroadcastReceiver?.also { receiver ->
			unregisterReceiver(receiver)
		}
	}


	// TODO consider using ACTION_GET_CONTENT because we only need a copy and not permanent access to the file if it changes and/or modify the file and write it back
	// https://developer.android.com/guide/topics/providers/document-provider#client
	/**
	 * Fires an intent to spin up the "file chooser" UI and select a file.
	 */
	private fun getOpenableFilePickedByUser() {

		// ACTION_OPEN_DOCUMENT is the intent to choose a file via the system's file
		// browser.
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			// Filter to only show results that can be "opened", such as a
			// file (as opposed to a list of contacts or timezones)
			addCategory(Intent.CATEGORY_OPENABLE)

			// Filter to show only images, using the image MIME data type.
			// If one wanted to search for ogg vorbis files, the type would be "audio/ogg".
			// To search for all documents available via installed storage providers,
			// it would be "*/*".
			type = "*/*"
		}

		startActivityForResult(intent, READ_REQUEST_CODE)
	}


	/** Called when the user taps the Send button */
	fun sendMessage(view: View) {
		// Do something in response to button
		Log.v(LOGGING_TAG, "button pressed")
		this.getOpenableFilePickedByUser()
        val editText = findViewById<TextView>(R.id.textView)
        editText.text = "file chooser initiated"

	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
		// TODO linter says we should call the superclass here, but android example does not show it
		// https://developer.android.com/training/data-storage/shared/documents-files#perform-operations

		// The ACTION_OPEN_DOCUMENT intent was sent with the request code
		// READ_REQUEST_CODE. If the request code seen here doesn't match, it's the
		// response to some other intent, and the code below shouldn't run at all.

		if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
			// The document selected by the user won't be returned in the intent.
			// Instead, a URI to that document will be contained in the return intent
			// provided to this method as a parameter.
			// Pull that URI using resultData.getData().
			resultData?.data?.also { uri ->
				Log.v(LOGGING_TAG, "Uri: $uri")
				this.dumpImageMetaData(uri)
				//showImage(uri)
			}
		}
	}

	private fun dumpImageMetaData(uri: Uri) {

		// The query, since it only applies to a single document, will only return
		// one row. There's no need to filter, sort, or select fields, since we want
		// all fields for one document.
		val cursor: Cursor? = contentResolver.query(uri, null, null, null, null, null)

		cursor?.use {
			// moveToFirst() returns false if the cursor has 0 rows.  Very handy for
			// "if there's anything to look at, look at it" conditionals.
			if (it.moveToFirst()) {

				// Note it's called "Display Name".  This is
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
				val size: String = if (!it.isNull(sizeIndex)) {
					// Technically the column stores an int, but cursor.getString()
					// will do the conversion automatically.
					it.getString(sizeIndex)
				} else {
					"Unknown"
				}
				Log.v(LOGGING_TAG, "Size: $size")
			}
		}
	}



}
