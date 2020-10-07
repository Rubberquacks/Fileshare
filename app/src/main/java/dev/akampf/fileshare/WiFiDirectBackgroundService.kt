package dev.akampf.fileshare

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.minutes
import kotlin.time.seconds


// TODO wakelock for when screen off needed?

@ExperimentalTime
class WiFiDirectBackgroundService : Service() {

	companion object {

		// overwritten in listening functions for more clarity
		const val LOG_TAG: String = "BackgroundService"

		const val ONGOING_NOTIFICATION_ID: Int = 2

		const val PENDING_INTENT_FOR_NOTIFICATION_REQUEST_CODE: Int = 2

		// Intent action used for broadcasting the result of the service: the ip address of the other device
		const val ACTION_REPORT_IP_ADDRESS_OF_CONNECTED_DEVICE =
			"${BuildConfig.APPLICATION_ID}.wifi_direct.ACTION_REPORT_IP_ADDRESS_OF_CONNECTED_DEVICE"

		// key for the extra containing the IP address in the broadcasted Intent
		const val EXTRA_IP_ADDRESS_OF_CONNECTED_DEVICE = "${BuildConfig.APPLICATION_ID}.wifi_direct.EXTRA_IP_ADDRESS_OF_CONNECTED_DEVICE"


		const val ACTION_REPORT_NEW_FILE_RECEIVED_FROM_CONNECTED_DEVICE =
			"${BuildConfig.APPLICATION_ID}.wifi_direct.ACTION_REPORT_NEW_FILE_RECEIVED_FROM_CONNECTED_DEVICE"

		const val EXTRA_ABSOLUTE_PATH_TO_NEWLY_RECEIVED_FILE_FROM_CONNECTED_DEVICE =
			"${BuildConfig.APPLICATION_ID}.wifi_direct.EXTRA_ABSOLUTE_PATH_TO_NEWLY_RECEIVED_FILE_FROM_CONNECTED_DEVICE"

		const val RECEIVED_FILES_DIRECTORY_NAME: String = "received_files"

		var MAXIMUM_DURATION_FOR_SERVICE_LISTENING_IN_BACKGROUND = 5.minutes
	}

	private var listenForIpAddressCoroutineJob: Job? = null

	private var listenForFileTransferCoroutineJob: Job? = null

	// Supervisorjob lets a child coroutine fail without cancelling the others, so we could just restart the child
	private val backgroundServiceSupervisorJob = SupervisorJob()

	private val backgroundServiceCoroutineScope = CoroutineScope(Dispatchers.Main + backgroundServiceSupervisorJob)


	private val durationUntilServiceStopsMutex = Mutex()
	private var durationUntilServiceStops = MAXIMUM_DURATION_FOR_SERVICE_LISTENING_IN_BACKGROUND

	// Binder given to clients
	private val binder = LocalBinder()

	/**
	 * Class used for the client Binder.  Because we know this service always
	 * runs in the same process as its clients, we don't need to deal with IPC.
	 */
	inner class LocalBinder : Binder() {
		// Return this instance of the service so clients can call public methods
		fun getService(): WiFiDirectBackgroundService = this@WiFiDirectBackgroundService
	}


	override fun onBind(intent: Intent): IBinder {
		Log.d(LOG_TAG, "onBind of ${this::class.simpleName} with intent: $intent")
		onBindOrRebind()
		return binder
	}

	override fun onRebind(intent: Intent?) {
		super.onRebind(intent)
		Log.d(LOG_TAG, "onRebind")
		onBindOrRebind()
	}

	private fun onBindOrRebind() {
		// also ensure service is started so it doesn't stop on unbind but can handle its own timeout for when to exit
		// multiple [startForegroundService] calls only result in multiple [onStartCommand] calls, but are ok
		ContextCompat.startForegroundService(applicationContext, Intent(applicationContext, this::class.java))
		// must be called shortly after startForegroundService
		startForegroundServiceWithNotification()
		Log.d(LOG_TAG, "after startForegroundService, setting duration to infinite")
		// this should not take long, since while having the lock the check coroutine only reads and writes the
		// value and sometimes stops the service
		// Probably harder to debug errors if we would launch this in a normal coroutine
		runBlocking {
			durationUntilServiceStopsMutex.withLock("reset timeout to infinity ir onBindOrRebind") {
				durationUntilServiceStops = Duration.INFINITE
				Log.d(LOG_TAG, "duration until service stops is: $durationUntilServiceStops")
			}
		}
	}


	override fun onUnbind(intent: Intent?): Boolean {
		Log.d(LOG_TAG, "onUnbind of ${this::class.simpleName} with intent: $intent")
		// this should not take long, since while having the lock the check coroutine only reads and writes the
		// value and sometimes stops the service
		// Probably harder to debug errors if we would launch this in a normal coroutine
		runBlocking {
			durationUntilServiceStopsMutex.withLock("set timeout to max value in onUnbind") {
				durationUntilServiceStops = MAXIMUM_DURATION_FOR_SERVICE_LISTENING_IN_BACKGROUND
			}
		}

		// returning true means onRebind is called when other clients bind to the service later
		// after all have unbound
		// Important because otherwise binding to the service again calls no method in the service
		// and thus the service doesn't know a client connected, which we use for setting the stop
		// timeout to infinity
		return true
	}

	lateinit var jobCheck: Job

	override fun onCreate() {
		Log.d(LOG_TAG, "onCreate started...")

		// only for debugging:
		GlobalScope.launch(Dispatchers.Main) {
			while (isActive) {
				Log.d(LOG_TAG, "$coroutineContext running code")
				delay(30_000)
			}
		}

		checkServiceTimeoutInBackground()

		Log.d(LOG_TAG, "startListening...")
		startListening()

		super.onCreate()
	}

	private fun checkServiceTimeoutInBackground(): Unit {
		// doesn't seem necessary anymore..... (we *are* able to use main dispatcher here)
		// not really resource intensive but we want it to complete in the background while blocking main with runblocking
		// in onDestroy
		jobCheck = backgroundServiceCoroutineScope.launch(Dispatchers.Main) {
			val checkInterval = 2.seconds
			Log.d(LOG_TAG, "check interval: $checkInterval")
			while (true) {
				durationUntilServiceStopsMutex.withLock("timeout check coroutine started in onCreate") {
					Log.d(LOG_TAG, "duration until service stops: $durationUntilServiceStops")
					when {
						durationUntilServiceStops <= Duration.ZERO -> {
							Log.d(LOG_TAG, "Stopping service (stopSelf)...")
							// todo: is this blocking and should be done outside of lock?
							stopSelf()
							Log.d(LOG_TAG, "after stopSelf, returning out of this coroutine")
							return@launch
						}
						durationUntilServiceStops == Duration.INFINITE -> {
							// let everything run, just check again later
							//Log.v(LOG_TAG, "Duration until service stops is infinite")
						}
						else -> durationUntilServiceStops -= checkInterval
					}
				}
				//Log.v(LOG_TAG, "delaying for 2 seconds...")
				delay(2.seconds)
			}
		}
	}

	override fun onDestroy(): Unit {
		Log.i(LOG_TAG, "onDestroy starting...")
		Log.i(
			LOG_TAG, "If listen for ip address and file transfer coroutine was running, we cancel those now and wait " +
					"(blocking current thread) for their accept() timeouts and then cleanup of resources to complete..."
		)

		// todo: how long is it okay for onDestroy to block main thread? should it only cancel the coroutine?
		//  (the coroutines we are waiting for with cancel
		//  and join and thus the thread calling runBlocking
		//  could wait as long as the timeout of accept() in the functions in the coroutines)
		//  Other method to interrupt the accept() calls?
		runBlocking {
			backgroundServiceCoroutineScope.cancel()
			backgroundServiceSupervisorJob.join()
		}
		Log.i(LOG_TAG, "Completed waiting for coroutine to finish.")
		super.onDestroy()
	}


	private fun startListening() {
		Log.d(LOG_TAG, "startListening function start...")
		if (listenForIpAddressCoroutineJob == null) {
			listenForIpAddressCoroutineJob = backgroundServiceCoroutineScope.launch(Dispatchers.Default) {
				while (isActive) {
					Log.d(LOG_TAG, "start startListeningForIpAddressOfOtherDevice in coroutine")
					startListeningForIpAddressOfOtherDevice()
				}
				Log.d(LOG_TAG, "listenForIpCoroutine: current job not active anymore")
			}
		}
		if (listenForFileTransferCoroutineJob == null) {
			listenForFileTransferCoroutineJob = backgroundServiceCoroutineScope.launch(Dispatchers.Default) {
				while (isActive) {
					Log.d(LOG_TAG, "start startListeningForFileTransfer in coroutine")
					startListeningForFileTransfer()
				}
				Log.d(LOG_TAG, "listenFileCoroutine: current job not active anymore")
			}
		}
		Log.d(LOG_TAG, "after launching start ip listen coroutine job")

	}

	private suspend fun startListeningForFileTransfer() {
		withContext(Dispatchers.IO) {
			//startListeningForIpAddressOrFileTransferOfOtherDevice(true)
		}
		delay(20_000)
		Log.d(LOG_TAG, "listenForFile after withcontext")
	}

	private suspend fun startListeningForIpAddressOfOtherDevice() {
		withContext(Dispatchers.IO) {
			startListeningForIpAddressOrFileTransferOfOtherDevice(false)
		}
		Log.d(LOG_TAG, "listenForIp after withcontext")
	}


	private fun startForegroundServiceWithNotification() {
		// promote this service to a foreground service to always display an ongoing notification
		// and when the app gets in the background, it is needed to keep it running

		val startMainActivityPendingIntent: PendingIntent =
			Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).let { startMainActivityExplicitIntent ->
				PendingIntent.getActivity(this, PENDING_INTENT_FOR_NOTIFICATION_REQUEST_CODE, startMainActivityExplicitIntent, 0)
			}

		// make sure the notification channel is created for Android 8+ before using it to post a notification
		createWiFiDirectConnectionNotificationChannelIfSupported(this)


		val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_WIFI_DIRECT_CONNECTION)
			.setContentTitle("Waiting to finish establishing connection...")
			.setContentText(getText(R.string.wifi_direct_connection_establishing_notification_message))

			// replace with app icon
			.setSmallIcon(R.drawable.ic_launcher_foreground)

			.setContentIntent(startMainActivityPendingIntent)
			.setTicker("Receiving IP Address or file of other device...")
			// The priority determines how intrusive the notification should be on Android 7.1 and lower.
			// For Android 8.0 and higher, the priority depends on the channel importance of the notification channel
			// used above in the constructor.
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.build()

		startForeground(ONGOING_NOTIFICATION_ID, notification)
	}

	// Attention! Only call this with an IO Dispatcher cause it executes blocking IO calls
	// in normal execution as well as when closing its resources
	private suspend fun startListeningForIpAddressOrFileTransferOfOtherDevice(waitForFileTransferAfterReceivingIpAddress: Boolean): Unit {
		val LOG_TAG = if (waitForFileTransferAfterReceivingIpAddress) "WaitForFileFunction" else "ListenForIpFunction"

		Log.d(LOG_TAG, "startListeningForIpAddressOrFileTransferOfOtherDevice function started")


		val serverPort = if (waitForFileTransferAfterReceivingIpAddress) {
			SERVER_PORT_FILE_TRANSFER
		} else {
			SERVER_PORT_NOTIFY_OF_IP_ADDRESS
		}

		var socketToConnectedClient: Socket? = null
		var serverSocket: ServerSocket? = null
		var dataInputStream: DataInputStream? = null
		var fileOutputStreamToDestinationFile: FileOutputStream? = null



		try {
			Log.i(LOG_TAG, "Starting server socket on port $serverPort")
			// Consider using less used but fixed port, ephemeral port, or fixed and likely not blocked port.
			serverSocket = ServerSocket(serverPort)

			serverSocket.soTimeout = 5.seconds.inMilliseconds.toInt()
			// default timeout is 0, meaning no timeout
			Log.i(
				LOG_TAG,
				"timeout of server socket when receiving ip in milliseconds is: ${serverSocket.soTimeout} (0 means no timeout)"
			)

			Log.i(LOG_TAG, "We are now accepting connections to ip ${serverSocket.inetAddress} on port ${serverSocket.localPort}...")
			// Wait for client connections. This call blocks until a connection from a client is accepted.


			// look into if non-blocking accept is possible with socket channels and that makes it easier to
			// cancel an accept currently listening?
			while (true) {
				try {
					//Log.d(LOG_TAG, "accept() call")
					socketToConnectedClient = serverSocket.accept()
					// continue normal execution if accept was successful
					break
				} catch (e: SocketTimeoutException) {
					Log.v(LOG_TAG, "Socket timeout exception: $e")
					// Calling the suspending function yield() gives a chance for this coroutine to be
					// cancelled every time the current timeout of listening for connections is reached.
					// If the coroutine is not cancelled, that means we should keep trying to listen forever.
					// The small gaps should not be a problem because the other side tries to connect for at least a second
					// or a few seconds, so we do not have to continually listen.
					// If the coroutine has been cancelled yield() throws a [CancellationException], so we can do
					// cleanup for the socket in the finally clause.
					yield()
				}
			}
			if (socketToConnectedClient == null) {
				throw IOException("Socket to connected client was null after successful accept() call!")
			}
			// maybe we should save the state that someone connected somewhere so we do not cancel the coroutine
			// and the service if there was just a connection at the end of the timeout and this function is still
			// executing the rest
			Log.i(
				LOG_TAG, "Connection from client with ip ${socketToConnectedClient.inetAddress} from " +
						"port ${socketToConnectedClient.port} to ip ${socketToConnectedClient.localAddress} on port ${socketToConnectedClient.localPort} " +
						"accepted."
			)

			val localIntentIpAddressReceived = Intent(ACTION_REPORT_IP_ADDRESS_OF_CONNECTED_DEVICE).apply {
				// use serialization to pass the inetaddress for now to retain all info like eventually already resolved hostname
				// is that a risk (or even too slow) and passing raw ip string or byte array and reconstructing inetaddress object is better?
				putExtra(EXTRA_IP_ADDRESS_OF_CONNECTED_DEVICE, socketToConnectedClient.inetAddress)
			}
			Log.d(LOG_TAG, "sending local broadcast with ip address of other device...")
			LocalBroadcastManager.getInstance(this).sendBroadcast(localIntentIpAddressReceived)


			val durationToSleep = 2.seconds
			Log.d(LOG_TAG, "sleep $durationToSleep ...")
			Thread.sleep(durationToSleep.toLongMilliseconds())
			Log.d(LOG_TAG, "Woke up again!")

			if (!waitForFileTransferAfterReceivingIpAddress) {
				return
			}


			// Save the input stream from the client as a file in internal app directory as proof of concept

			val inputStreamFromConnectedDevice = socketToConnectedClient.getInputStream()

			val bufferedInputStream: BufferedInputStream = BufferedInputStream(inputStreamFromConnectedDevice)
			dataInputStream = DataInputStream(bufferedInputStream)

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

			Log.i(LOG_TAG, "File name received: $fileName")


			// open/create subdirectory only readable by our own app in app private storage, note that this is not created in the `nobackup`
			// directory so if we use backup, we should not backup those big files in this subdirectory
			// TODO POTENTIAL BREAKAGE filesDir gives us a subdirectory in the data directory named "files", we use this name in the content
			//  provider (file provider) sharable file paths definition, so if the name is different on another android version, the app breaks
			val receivedFilesDirectory: File = File(filesDir, RECEIVED_FILES_DIRECTORY_NAME)

			val destinationFile: File = File(
				// replace by letting user choose where to save or starting download while letting user choose or letting user choose
				//  afterwards if wants to view, edit etc file, but choosing save location might make sense earlier. view, save, edit etc shortcuts
				//  maybe also in transfer complete notification as action
				receivedFilesDirectory.absolutePath, fileName
			)

			fun File.doesNotExist(): Boolean = !exists()


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
						Log.wtf(
							LOG_TAG, "Error on opening file in app private directory, which should always be possible! " +
									"App is in an undefined state, trying to exit app...", e
						)
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

			val localIntentNewFileReceived: Intent = Intent(ACTION_REPORT_NEW_FILE_RECEIVED_FROM_CONNECTED_DEVICE).apply {
				putExtra(EXTRA_ABSOLUTE_PATH_TO_NEWLY_RECEIVED_FILE_FROM_CONNECTED_DEVICE, destinationFile.absolutePath)
			}
			Log.d(LOG_TAG, "sending local broadcast with path to newly received file of other device...")
			LocalBroadcastManager.getInstance(this).sendBroadcast(localIntentNewFileReceived)


		} catch (cancellationException: CancellationException) {
			Log.d(
				LOG_TAG, "Job/Coroutine that was listening for ip address or file was cancelled:",
				cancellationException
			)
			// the coroutine was cancelled, so we should stop listening for connections
			// still do any cleanup if necessary through the finally clause
		} catch (exception: IOException) {
			Log.e(LOG_TAG, "IO Error while (waiting for) receiving ip address or file of other device!", exception)
			TODO("Handle errors appropriately and fine grained")
		} finally {
			Log.d(LOG_TAG, "Doing cleanup of sockets in finally...")
			// we now know the ip address of the other device (for us to connect to it in the future) so we can close the connection
			try {


				// close the outermost stream to call its flush method before it in turn closes any underlying streams and used resources

				// TODO why is socket to connected client not closed after closing input stream of it, is that a problem?
				//  is it really not? check if it is, maybe also try to close the socket
				dataInputStream?.close()

				Log.d(
					LOG_TAG,
					"socket to connected client closed state after closing dataInputStream: ${socketToConnectedClient?.isClosed}"
				)

				// is it bad when this has an error? check integrity of file?
				fileOutputStreamToDestinationFile?.close()


				if (socketToConnectedClient?.isClosed == false) {
					socketToConnectedClient.close()
				}
				if (serverSocket?.isClosed == false) {
					serverSocket.close()
				}
			} catch (exception: IOException) {
				// if closing of socket fails, is that bad and should be handled somehow?
				// preserve original exception if there was one?
				Log.e(LOG_TAG, "IO error when closing sockets or input/output streams!", exception)
				TODO("Handle error on closing server socket and socket to client appropriately")
			}
		}
		Log.d(LOG_TAG, "end of function startListeningForIpAddressOfOtherDevice")

	}
}