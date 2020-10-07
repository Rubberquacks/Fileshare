package dev.akampf.fileshare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.location.LocationManagerCompat
import kotlin.time.ExperimentalTime


private const val LOG_TAG: String = "LocationModeBrdcastRcvr"

// TODO (check again new documentation!) from documentation  it is not clear if it only notifies for on/off or for other mode changes, too
@ExperimentalTime
class LocationModeChangedBroadcastReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent): Unit {
		// This method is called when the BroadcastReceiver is receiving an Intent broadcast.
		when(intent.action) {
			LocationManager.MODE_CHANGED_ACTION -> {

				val locationManager: LocationManager = getSystemService(context, LocationManager::class.java) as LocationManager
				var locationModeEnabled: Boolean = LocationManagerCompat.isLocationEnabled(locationManager)

				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
					// change to the value provided by the intent extra, falling back to the previously determined value if no extra found with that
					// name
					// This gets the mode race condition free as we were notified of the mode changed action, so it cannot be different now compared
					// to the value that triggered the intent. If it is changed again, we should get notified again in this broadcast receiver and
					// thus will not miss a switch this way!
					locationModeEnabled = intent.getBooleanExtra(LocationManager.EXTRA_LOCATION_ENABLED, locationModeEnabled)
				}

				Log.i(LOG_TAG, "Location mode changed, activation state: $locationModeEnabled")
				(context as MainActivity).locationModeEnabled = locationModeEnabled
			}

			// different action or action is null, this should not happen as we did not register for other actions in the intent filter
			else -> {}
		}
	}
}
