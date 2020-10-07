package dev.akampf.fileshare

import android.app.*
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.*
import java.io.*
import java.net.*
import kotlin.time.ExperimentalTime
import kotlin.time.seconds


private const val LOG_TAG: String = "WiFiDirectTransfer"

// replace with port likely to be free and not in the popular 8xxx range or even knowingly used
// multiple times like 8888:
// https://en.wikipedia.org/wiki/List_of_TCP_and_UDP_port_numbers
const val SERVER_PORT_FILE_TRANSFER: Int = 8888
const val SERVER_PORT_NOTIFY_OF_IP_ADDRESS: Int = 8889


// TODO convert to foreground (intent)service that shuts down connection after 5 minutes if nothing connects
//  is started when activity starts if not already running
//  does not shut down when transfer in progress

// receives a file sent via a network socket
@ExperimentalTime
class ReceiveFileOrGetIpAddressFromOtherDeviceAsyncTask(
	private val mainActivity: MainActivity,
	private var statusText: TextView,
	private val onlyGetNotifiedOfIpAddress: Boolean
) : AsyncTask<Void, Void, Pair<String?, InetAddress?>>() {

	companion object {

		const val RECEIVED_FILES_DIRECTORY_NAME: String = "received_files"

		const val LOG_TAG: String = "ReceiveOrGetIpAsyncTask"
	}

	override fun doInBackground(vararg params: Void): Pair<String?, InetAddress?> {

		// we start a server on a different port for accepting a connection from the wifi direct group client even if it does not send a file
		// to know its ip address to connect to it when we want to send something
		// But even when we get a normal connection to send something, we update the ip address of the other device afterwards
		val serverPort: Int = if (onlyGetNotifiedOfIpAddress) {
			SERVER_PORT_NOTIFY_OF_IP_ADDRESS
		} else {
			SERVER_PORT_FILE_TRANSFER
		}
		Log.i(LOG_TAG, "AsyncTask started with onlyGetNotifiedOfIpAddress set to: $onlyGetNotifiedOfIpAddress")


		// TODO handle io exceptions, wrap nearly all of code in this method in try except and handle important exceptions for status reporting
		//  separately like rethrowing own exception which is handled in own catch clause or saving failure status in variable?


		Log.i(LOG_TAG, "Starting server socket on port $serverPort")
		// Consider using less used but fixed port, ephemeral port, or fixed and likely not blocked port.
		val serverSocket = ServerSocket(serverPort)

		Log.i(LOG_TAG, "We are now accepting connections to ip ${serverSocket.inetAddress} on port ${serverSocket.localPort}...")
		// Wait for client connections. This call blocks until a connection from a client is accepted.
		val socketToConnectedClient = serverSocket.accept()
		Log.i(
			LOG_TAG, "Connection from client with ip ${socketToConnectedClient.inetAddress} from " +
					"port ${socketToConnectedClient.port} to ip ${socketToConnectedClient.localAddress} on port ${socketToConnectedClient.localPort} " +
					"accepted."
		)






		if (onlyGetNotifiedOfIpAddress) {
			// we already know the ip address of the other device for us to connect to in the future so we can close the connection
			// the ip address stays retrievable after closing the socket
			try {
				if (!socketToConnectedClient.isClosed) {
					socketToConnectedClient.close()
				}
				if (!serverSocket.isClosed) {
					serverSocket.close()
				}
			} catch (exception: IOException) {
				// if closing of socket fails, is that bad and should be handled somehow?
				Log.e(LOG_TAG, "IO error while closing sockets!", exception)
				TODO("Handle error on closing server socket appropriately")
			}

			return Pair<String?, InetAddress>(null, socketToConnectedClient.inetAddress)
		}


		// Save the input stream from the client as a file in internal app directory as proof of concept


		val inputStreamFromConnectedDevice = socketToConnectedClient.getInputStream()

		val bufferedInputStream: BufferedInputStream = BufferedInputStream(inputStreamFromConnectedDevice)
		val dataInputStream = DataInputStream(bufferedInputStream)

		// read file name with readUTF function for now, then create file in filesystem to write received data to

		// see: https://cwe.mitre.org/data/definitions/1219.html
		// TODO this is user supplied data transferred over to this device, we can't trust its content, how to escape properly for using as
		//  file name for saving to disk etc? what if contains illegal characters for filename, like slash, which would
		//  maybe even allow path traversal attacks through relative paths pointing to parent directories (escape slashes etc with some method)
		//  how to handle non-printable characters or homographs? (disallow them?)
		//  when file gets saved later, check for similar (homograph, only different case) files in directory and rename it with feedback
		//  for user? just append some random string?
		//  what if file name is the empty string? (generate name?)
		// see: https://cwe.mitre.org/data/definitions/22.html
		// outright reject whole request if filename contains known malicious input like slashes or maybe exceeds certain size etc
		// and report this to user if clear so they can distrust the sender
		// check allow list of file types or at least check deny list and report to user / warn (e.g. its an apk)
		// see: https://cwe.mitre.org/data/definitions/434.html
		// just always generate a filename to not deal with untrusted user input more than necessary
		// or at least escape every character except in allow list like alphanumeric characters
		// and after the transform check against an allowlist as final step (transforms can introduce vulnerabilities, e.g. if escaping sth)
		val fileName: String = dataInputStream.readUTF()


		// open/create subdirectory only readable by our own app in app private storage, note that this is not created in the `nobackup`
		// directory so if we use backup, we should not backup those big files in this subdirectory
		// TODO POTENTIAL BREAKAGE filesDir gives us a subdirectory in the data directory named "files", we use this name in the content
		//  provider (file provider) sharable file paths definition, so if the name is different on another android version, the app breaks
		val receivedFilesDirectory: File = File(mainActivity.filesDir, RECEIVED_FILES_DIRECTORY_NAME)

		val destinationFile: File = File(
			// replace by letting user choose where to save or starting download while letting user choose or letting user choose
			//  afterwards if wants to view, edit etc file, but choosing save location might make sense earlier. view, save, edit etc shortcuts
			//  maybe also in transfer complete notification as action
			receivedFilesDirectory.absolutePath, fileName
		)

		// generally handle parent directory creation better and handle errors when we can't create every parent directory, throw error
		//  and exit app when parent directory that should always exist is not there, like in our case with the filesDir directory of the
		//  app private storage?
		// if using nested directories, take care to create every parent directory
		// if destination file has a parent directory (seen in its path), but that does not exist, create it:
		if (destinationFile.parentFile?.doesNotExist() == true) {
			// why assert non-null needed, shouldn't this condition return false when parent file is null?
			// create the `received_files` directory if not already existent
			// TODO better do this above before involvement of untrusted user data in form ot the sent file name
			destinationFile.parentFile!!.mkdir()
		}

		// TODO handle when file already exists, but multiple transfers with same file name should be possible!
		//  consider locking file access?
		//  don't transfer file when it is the same exact file, check hash
		//  handle other io and security errors
		val destinationFileSuccessfullyCreated: Boolean = destinationFile.createNewFile()

		if (!destinationFileSuccessfullyCreated) {
			Log.e(
				LOG_TAG, "TODO this MUST be handled! destination file (path: ${destinationFile.path}) to save received content to " +
						"not created because file with same name already exists! absolute path: ${destinationFile.absolutePath}"
			)
		}

		val fileOutputStreamToDestinationFile: FileOutputStream
		try {
			fileOutputStreamToDestinationFile = FileOutputStream(destinationFile)
		} catch (e: Exception) {
			when (e) {
				is FileNotFoundException, is SecurityException -> {
					// !!!
					// THIS CODE SHOULD NEVER BE REACHED!
					//
					// This should never happen as the path to the file is internally constructed in the app only influenced by the received name
					// for the content. The constructed path should always be existent and writable as it is in an app private directory. So neither a
					// FileNotFoundException nor a SecurityException should be possible to be thrown.
					// !!!
					Log.wtf(LOG_TAG, "Error on opening file in app private directory, which should always be possible! " +
							"App is in an undefined state, trying to exit app...", e)
					// Something is severely wrong with the state of the app (maybe a remote device tried to attack us), so it should throw
					// an error which is NOT caught by some other code and exit the app, meaning we should not try to recover from an undefined state
					// or from an attack but we should fail safely by quitting
					TODO("how to guarantee exiting all of the app without something intercepting it? ensure exceptions from this method are not handled")
				}
				else -> throw e
			}

		}


		// TODO handle interrupted and partial transfers correctly, don't display / save
		// TODO this is user supplied data transferred over to this device, we can't trust its content, should we do some checks for its
		//  content in general (anti-malware etc) or for methods and programs that use it like processing in this app, FileProvider, etc?
		val contentSuccessfullyReceived: Boolean =
			copyBetweenByteStreamsAndFlush(inputStreamFromConnectedDevice, fileOutputStreamToDestinationFile)


		if (contentSuccessfullyReceived) {
			Log.i(
				LOG_TAG, "File name and content successfully received and written, error on resources closing " +
						"might still occurred"
			)
		}

		// close the outermost stream to call its flush method before it in turn closes any underlying streams and used resources
		try {


			// TODO why is socket to connected client not closed after closing input stream of it, is that a problem?
			//  is it really not? check if it is, maybe also try to close the socket
			dataInputStream.close()

			Log.d(LOG_TAG, "socket to connected client closed state after closing dataInputStream: ${socketToConnectedClient.isClosed}")

			fileOutputStreamToDestinationFile.close()


			// closes the server socket, which is still bound to the ip address and port to receive connections with `accept()`
			// this releases its resources and makes it possible to bind to this port and ip address again by this or other apps
			// This is separate from the socket to the connected client.
			serverSocket.close()
			Log.d(LOG_TAG, "Successfully closed In- and Output streams and the server socket")
		} catch (e: IOException) {
			// error when closing streams and socket
			// TODO should this be ignored and is it bad when e.g. the output stream to the saved file has an error on close? just check
			//  integrity of received content after writing to file (not only when in memory)? still resource leak?
			Log.e(LOG_TAG, "IO Error when closing In- or Output streams or the server socket!", e)
			TODO("Handle error on closing server socket appropriately")
		}


		// the ip address of the connected client will still be available after the socket is closed again and the client is disconnected
		// it will only be null when the client was never connected, for example when an error occurred and was caught before the connection
		return Pair<String?, InetAddress?>(destinationFile.absolutePath, socketToConnectedClient.inetAddress)
	}


	private fun File.doesNotExist(): Boolean = !exists()

	/**
	 * This method runs in the UI thread so we can safely modify our ui according to the result here.
	 * Start activity that can handle opening the file
	 */
	override fun onPostExecute(absolutePathReceivedFileAndClientIpAddress: Pair<String?, InetAddress?>): Unit {
		val absolutePathReceivedFile: String? = absolutePathReceivedFileAndClientIpAddress.first
		val clientIpAddress: InetAddress? = absolutePathReceivedFileAndClientIpAddress.second


		// is this too fragile and the ip address the other device uses to connect to us over wifi direct could change so we could not
		//  use it to connect back to them?
		// only update info about other device with new ip address if the other device actually connected to us and thus we have the (new)
		// ip address
		if (clientIpAddress != null) {
			Log.i(
				LOG_TAG, "Saving IP Address $clientIpAddress from WiFi Direct group client device after it connected to us" +
						", AsyncTask with onlyGetNotifiedOfIpAddress: $onlyGetNotifiedOfIpAddress"
			)
			mainActivity.connectedClientWiFiDirectIpAddress = clientIpAddress
		}

		absolutePathReceivedFile?.let { absolutePathReceivedFile ->


			Log.d(LOG_TAG, "File was written to $absolutePathReceivedFile")
			statusText.text = "File received: $absolutePathReceivedFile"


			val uriToFile: Uri = Uri.parse("file://$absolutePathReceivedFile")

			// TODO this is ugly, extract method as general helper (for a context, because of content provider) or just globally
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
				// explain in comments why this is needed / what exactly is needed more clearly
				viewIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION

				// the authority argument of `getUriForFile` must be the same as the authority of the file provider defined in the AndroidManifest!
				// should extract authority in global variable or resource to reuse in manifest and code
				val fileProviderUri =
					FileProvider.getUriForFile(mainActivity, BuildConfig.APPLICATION_ID + ".provider", File(absolutePathReceivedFile))

				// normalizing the uri to match android best practices for schemes: makes the scheme component lowercase
				viewIntent.setDataAndNormalize(fileProviderUri)
			} else {
				// normalizing the uri to match android case-sensitive matching for schemes: makes the scheme component lowercase
				viewIntent.setDataAndNormalize(uriToFile)
			}

			try {
				mainActivity.startActivity(viewIntent)
			} catch (e: ActivityNotFoundException) {
				Log.w(LOG_TAG, "No installed app supports viewing this content!", e)
				Snackbar.make(
					mainActivity.root_coordinator_layout,// could also be that the other device has not connected to us yet but the wifi direct connection is fine
					mainActivity.getString(R.string.could_not_find_activity_to_handle_viewing_content),
					Snackbar.LENGTH_LONG
				).show()
			}

		}
		Log.i(LOG_TAG, "AsyncTask exited that was started with onlyGetNotifiedOfIpAddress: $onlyGetNotifiedOfIpAddress")
	}
}


// use IntentService for now for serial handling of requests on a worker thread without handling
// thread creation ourselves, also Android Jobs are not
// guaranteed to be executed immediately maybe (if app not in foreground at least?) and seem to have a execution limit of running
// for 10 min, at least if not using: https://developer.android.com/topic/libraries/architecture/workmanager/advanced/long-running
// Convert to foreground raw (not intent) service because deprecated
@ExperimentalTime
class SendFileOrNotifyOfIpAddressIntentService : IntentService(SendFileOrNotifyOfIpAddressIntentService::class.simpleName) {

	companion object {

		const val ACTION_NOTIFY_OF_IP_ADDRESS: String = "${BuildConfig.APPLICATION_ID}.wifi_direct.NOTIFY_OF_IP_ADDRESS"

		const val ACTION_SEND_FILE: String = "${BuildConfig.APPLICATION_ID}.wifi_direct.SEND_FILE"

		const val EXTRAS_OTHER_DEVICE_IP_ADDRESS: String = "other_device_ip_address"


		private const val ONGOING_NOTIFICATION_ID: Int = 1

		private const val PENDING_INTENT_FOR_NOTIFICATION_REQUEST_CODE: Int = 1

		private const val LOG_TAG: String = "SendFileOrIpService"

		// handle retry better
		private const val SOCKET_TIMEOUT_MILLISECONDS: Int = 30_000


	}

	// this is called with an intent to start work, if service started again this method will only be called when the first one finishes
	// runs in background thread and not in UI thread
	// intent is null if service was restarted after its process has gone away
	override fun onHandleIntent(workIntent: Intent?): Unit {
		Log.i(LOG_TAG, "onHandleIntent started")


		// promote this service to a foreground service to display an ongoing notification and keep running when the app gets in the background

		val startMainActivityPendingIntent: PendingIntent =
			Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).let { startMainActivityExplicitIntent ->
				PendingIntent.getActivity(this, PENDING_INTENT_FOR_NOTIFICATION_REQUEST_CODE, startMainActivityExplicitIntent, 0)
			}

		// make sure the notification channel is created for Android 8+ before using it to post a notification
		createWiFiDirectConnectionNotificationChannelIfSupported(this)

		val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_WIFI_DIRECT_CONNECTION)
			.setContentTitle(getText(R.string.wifi_direct_data_transfer_notification_title))
			//.setContentText(getText(R.string.wifi_direct_data_transfer_notification_message))
			//.setSmallIcon(R.drawable.icon)
			.setContentIntent(startMainActivityPendingIntent)
			.setTicker(getText(R.string.wifi_direct_data_transfer_ticker_text))
			// The priority determines how intrusive the notification should be on Android 7.1 and lower.
			// For Android 8.0 and higher, the priority depends on the channel importance of the notification channel
			// used above in the constructor.
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.build()

		startForeground(ONGOING_NOTIFICATION_ID, notification)


		val durationToSleep = 5.seconds
		Log.d(LOG_TAG, "Sleeping for $durationToSleep...")
		Thread.sleep(durationToSleep.toLongMilliseconds())
		Log.d(LOG_TAG, "Woke up again after $durationToSleep!")


		// Gets data from the incoming Intent

		val intentAction: String =
			workIntent?.action ?: TODO("handle error, service to connect to server was called without an action in the intent")
		if (intentAction !in listOf<String>(ACTION_NOTIFY_OF_IP_ADDRESS, ACTION_SEND_FILE)) {
			TODO("handle error, service to connect to server was called with unknown action: $intentAction")
		}
		Log.i(LOG_TAG, "${this::class.simpleName} started with action: $intentAction")
		val otherDevicePort: Int = if (intentAction == ACTION_NOTIFY_OF_IP_ADDRESS) {
			SERVER_PORT_NOTIFY_OF_IP_ADDRESS
		} else { // action is sending a file, we checked earlier that only these 2 actions are possible
			SERVER_PORT_FILE_TRANSFER
		}

		val otherDeviceIpAddress: String = workIntent.getStringExtra(EXTRAS_OTHER_DEVICE_IP_ADDRESS)
			?: TODO("handle error, send file service called without providing non-null ip address to connect to")

		val dataToSendUri: Uri? = workIntent.data
		if (intentAction == ACTION_SEND_FILE && dataToSendUri == null) {
			TODO(
				"handle error, send file service called with intent with ACTION_SEND_FILE but " +
						"without intent data, which should contain the uri of the data to send"
			)
		}
		// if we got here either we only notify of our ip address and do not use the uri of the data to send or it is not null and can be used
		// normally


		val socket: Socket = Socket()

		try {
			// bind with an ip address and port given to us by the system cause we initiate the connection and don't care for the outgoing port
			// and ip address
			// "If the address is null, then the system will pick up an ephemeral port and a valid local address to bind the socket."
			// If not passing null, meaning specifying port and ip, we might need to catch an IllegalArgumentException!
			Log.d(
				LOG_TAG, "Binding socket to ephemeral local ip address and port because we do not care for the source address " +
						"and port..."
			)
			socket.bind(null)
			val localBoundIpAddress: InetAddress = socket.localAddress
			if (localBoundIpAddress.isAnyLocalAddress && (socket.isClosed || !socket.isBound)) {
				Log.i(
					LOG_TAG, "Bound socket to port ${socket.localPort} but socket closed (closed state: ${socket.isClosed}) or\n" +
							"not bound yet (bound state: ${socket.isBound}) or closed (closed state: ${socket.isClosed}) so ip address is not " +
							"known anymore (it is displayed as the wildcard ip address: ${socket.localAddress})"
				)
			} else {
				Log.i(LOG_TAG, "Successfully bound socket to local ip address ${socket.localAddress} on port ${socket.localPort}")
			}
			// TODO handle connection timing out
			// blocks until connection established or error (e.g. timeout)
			socket.connect((InetSocketAddress(otherDeviceIpAddress, otherDevicePort)), SOCKET_TIMEOUT_MILLISECONDS)
			Log.i(
				LOG_TAG, "Successfully connected to other device with remote ip address ${socket.inetAddress} on " +
						"remote port ${socket.port}"
			)


			// If action is ACTION_NOTIFY_OF_IP_ADDRESS it is enough to connect to the other device, they will see the ip address from which
			// the connection is coming and can save it to use it to connect to this device.
			// Now follows the whole sending file name and data over the socket and opening and closing all output and input streams,
			// in the case of only notifying of the ip address wo do not need to open any input or output stream but just close the socket after
			// we successfully connected to the other device.
			if (intentAction == ACTION_SEND_FILE) {
				// Currently, we first send the file name and then the raw file data. These data (name and content) are then retrieved by the server
				// socket.
				val outputStreamConnectedDevice: OutputStream = socket.getOutputStream()


				val bufferedOutputStream: BufferedOutputStream = BufferedOutputStream(outputStreamConnectedDevice)
				val dataOutputStream: DataOutputStream = DataOutputStream(bufferedOutputStream)

				// assert non-null because if we got here ACTION_SEND_FILE was the action and if dataUri would have been null at the same time, we
				// would have thrown an error and exited earlier and not even got here
				// TODO don't use this name when it is an empty string or somehow invalid (contains slashes etc)?
				val displayName = getDisplayNameFromUri(this, dataToSendUri!!)
				Log.d(LOG_TAG, "Display name being sent is: $displayName")


				// this writes the length of the string and then a string of the display name of the content in "modified utf8"
				dataOutputStream.writeUTF(displayName)
				// Without this flush, the transfer will not work. Probably because the data output stream gets flushed on close and by then
				// the actual content is already written to the underlying output stream before the buffered data of the file name got written to it.
				// This results in the file name not being at the beginning of the data in the stream.
				// TODO But checking how many bytes were written on the underlying output stream suggests it is already written to it without the flush,
				//  so no idea if this really was the problem and this is the appropriate fix... :/
				dataOutputStream.flush()


				// Create a byte stream from the file and copy it to the output stream of the socket.
				val inputStreamOfContentToTransfer: InputStream =
					contentResolver.openInputStream(dataToSendUri) ?: TODO("handle error, cannot get stream of data from chosen uri of content")
				val contentSuccessfullyTransferred: Boolean =
					copyBetweenByteStreamsAndFlush(inputStreamOfContentToTransfer, outputStreamConnectedDevice)

				if (contentSuccessfullyTransferred) {
					Log.i(LOG_TAG, "File name and content successfully transferred, error on resources closing might still occur")
				}

				// only close outermost stream here because it will flush its content and also close all underlying streams, which in turn flushes
				// them, as well as releasing all used resources
				dataOutputStream.close()

				inputStreamOfContentToTransfer.close()

			}
		} catch (e: FileNotFoundException) {
			Log.e(LOG_TAG, "File Not Found error when transferring content or ip address", e)
			TODO("handle file not found error when transferring data / connecting appropriately")
		} catch (e: IOException) {
			Log.e(LOG_TAG, "IO error when transferring content or ip address", e)
			TODO("handle io error while transferring data / connecting appropriately, intent action: $intentAction")
		} finally {
			// Clean up any open sockets when done
			// transferring or if an exception occurred.
			// TODO close outermost stream here in finally instead to also close when exception while transferring (going in catch clause)
			//  or not important to also close outputstream and not only underlying socket cause data is lost either way and real resource
			//  is only the socket and other streams will just be
			//  cleaned up when out of scope?? but why not here? seems more clean
			if (socket.isConnected) {
				// TODO handle exception, keep exceptions from catch close by encapsulating or sth (adding to suppressed exceptions list)
				//  so they are still viewable?
				try {
					socket.close()
				} catch (e: IOException) {
					Log.e(LOG_TAG, "IO Error while closing socket which was used to connect to the other device", e)
				}
			}
		}
		Log.d(LOG_TAG, "End of onHandleIntent of ${this::class.simpleName} started with intent action: $intentAction")
	}
}


fun copyBetweenByteStreamsAndFlush(inputStream: InputStream, outputStream: OutputStream): Boolean {
	// good buffer size for speed? just use `BufferedOutputStream` and `BufferedInputStream` instead of own buffer?
	// this only determines how much is read and then written to the output stream before new data is read, so this does not limit
	// transferable file size
	val buffer = ByteArray(1024)
	var totalNumberOfBytesRead: Int = 0
	var numberOfBytesReadAtOnce: Int
	try {
		// read until there is no more data because the end of the stream has been reached
		while (inputStream.read(buffer).also { numberOfBytesReadAtOnce = it } != -1) {
			totalNumberOfBytesRead += numberOfBytesReadAtOnce
			//Log.d(LOG_TAG, "number bytes read at once: $numberOfBytesReadAtOnce")

			// read bytes from buffer and write to the output stream with 0 offset
			outputStream.write(buffer, 0, numberOfBytesReadAtOnce)
		}
		// flush stream after writing data in case it was called with a buffered output stream and not a raw
		// output stream (where flushing would do nothing)
		Log.i(LOG_TAG, "Total number of bytes read and written: ${String.format("%,d", totalNumberOfBytesRead)}")
		outputStream.flush()
	} catch (e: IOException) {
		Log.e(LOG_TAG, "TODO handle io errors while sending bytes to the output stream", e)
		// TODO return number of bytes read or sth like -1 on error instead of simple true false or take expected number of bytes as argument
		//  to check against it for errors (in future compare against returned hash or at least send hash so other
		//  device can check or sth like that)
		//  just use some library for the whole transfer stuff after we are connected?
		return false
	}
	return true
}


fun getDisplayNameFromUri(context: Context, uri: Uri): String {

	var displayName: String? = null


	// use projection parameter of query for performance?
	// this returns null if the uri does not use the content:// scheme
	val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null, null)

	if (cursor?.moveToFirst() == true) {
		// Note it's called "Display Name". This is
		// provider-specific, and might not necessarily be the file name.
		val columnIndex: Int = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
		if (columnIndex == -1) {
			Log.w(LOG_TAG, "There is no DISPLAY_NAME for the queried uri in the associated content resolver. Queried uri: $uri")
		} else {
			try {
				val displayNameFromContentResolver: String? = cursor.getString(columnIndex)
				if (displayNameFromContentResolver == null) {
					Log.w(LOG_TAG, "")
					Log.w(LOG_TAG, "DISPLAY_NAME value null returned from content resolver associated with uri: $uri")
				} else {
					// TODO don't use this value when it is an empty string?
					displayName = displayNameFromContentResolver
				}

				// depending on implementation, `cursor.getString()` might throw an exception when column value is not a String or null
			} catch (e: Exception) {
				Log.w(
					LOG_TAG,
					"Error when getting DISPLAY_NAME, even though the column exists in content resolver associated with uri: $uri",
					e
				)
			}
		}
	}
	cursor?.close()

	// fallback name if we could not get a display name from the content resolver
	if (displayName == null) {
		Log.w(LOG_TAG, "Couldn't get name from content provider, falling back to extracting it from the uri itself! uri: $uri")
		// uri.toString() is guaranteed to not return null, so we always return a non-null string, but possibly empty
		// TODO change this (that we might return empty string)
		// TODO we might have slashes in the name with schemes being prepended, not good for saving on
		//  the other side when receiving this name
		displayName = uri.lastPathSegment ?: uri.encodedPath ?: uri.toString()
	}

	return displayName

}


fun createWiFiDirectConnectionNotificationChannelIfSupported(context: Context): Unit {
	// Create the NotificationChannel, but only on API 26+ because
	// the NotificationChannel class is new and not in the support library
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
		val name: String = context.getString(R.string.data_transfer_notification_channel_name)
		val descriptionText: String = context.getString(R.string.data_transfer_notification_channel_description)
		val importance: Int = NotificationManager.IMPORTANCE_DEFAULT
		val channel: NotificationChannel = NotificationChannel(NOTIFICATION_CHANNEL_ID_WIFI_DIRECT_CONNECTION, name, importance).apply {
			description = descriptionText
		}
		// Register the channel with the system
		// We do not need the compat version here because this is only needed on API 26+ where this function is always available,
		// but we want to use AndroidX code as much as possible to benefit from updates and fixes to it.
		NotificationManagerCompat.from(context).createNotificationChannel(channel)
	}
}

