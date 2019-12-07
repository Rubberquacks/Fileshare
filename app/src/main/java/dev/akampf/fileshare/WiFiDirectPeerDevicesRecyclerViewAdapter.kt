package dev.akampf.fileshare

import android.net.wifi.p2p.WifiP2pDevice
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView


import dev.akampf.fileshare.DeviceFragment.OnListFragmentInteractionListener

import kotlinx.android.synthetic.main.fragment_device.view.*
import java.util.UUID

/**
 * [RecyclerView.Adapter] that can display a [WifiP2pDevice] and makes a call to the
 * specified [OnListFragmentInteractionListener] when the representing view is clicked.
 */
class WiFiDirectPeerDevicesRecyclerViewAdapter(
	private val mValues: List<WifiP2pDevice>,
	private val mListener: OnListFragmentInteractionListener?
) : RecyclerView.Adapter<WiFiDirectPeerDevicesRecyclerViewAdapter.DeviceViewHolder>() {

	private val mOnClickListener: View.OnClickListener

	init {
		// tells the layout manager that it can identify items that only have moved safely by their itemId, leads to view reuse and smooth
		// animations even when only notifying of data change without being specific about what moved where (still not efficient to not do that)
		setHasStableIds(true)
		mOnClickListener = View.OnClickListener { deviceView ->
			val wiFiDirectDevice = deviceView.tag as WifiP2pDevice
			// Notify the active callbacks interface (the activity, if the fragment is attached to
			// one) that an item has been selected.
			mListener?.onListFragmentInteraction(wiFiDirectDevice)
		}
	}


	// Create new views (invoked by the layout manager)
	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
		val singleDeviceView = LayoutInflater.from(parent.context)
			.inflate(R.layout.fragment_device, parent, false)
		return DeviceViewHolder(singleDeviceView)
	}


	// Replace the contents of a view (representing a single device) through the ViewHolder (invoked by the layout manager)
	override fun onBindViewHolder(deviceViewHolder: DeviceViewHolder, position: Int) {
		// - get element from your data set at this position
		// - replace the contents of the view with that element
		val wiFiDirectDevice = mValues[position]
		deviceViewHolder.mIdView.text = wiFiDirectDevice.deviceAddress
		deviceViewHolder.mContentView.text = wiFiDirectDevice.deviceName

		with(deviceViewHolder.mView) {
			// Set tag for the view that can be clicked to an identifier for the content or the content itself so we can later retrieve
			// e.g. the data associated (if only identifier is used) and notify the containing activity of the click with this info, since
			// we only know which view was clicked
			tag = wiFiDirectDevice
			setOnClickListener(mOnClickListener)
		}
	}


	// Return the size of the data set (invoked by the layout manager)
	override fun getItemCount(): Int = mValues.size

	// TODO annotation required to use UUID.nameUUIDFromBytes from the standard Java Library, method may be changed or disappear at any
	//  time so better alternative must be found
	@ExperimentalStdlibApi
	override fun getItemId(position: Int): Long {
		// generate a sufficiently unique Long id from the mac address of the device which should be unique
		// the possible Long values are 2^64 possibilities and less than the possible mac addresses, but with md5 hashing used internally
		// collisions of mac addresses visible to the user simultaneously in one location should not occur
		// taken from: https://stackoverflow.com/questions/9309723/how-can-i-generate-a-long-hash-of-a-string/46095268#46095268
		return UUID.nameUUIDFromBytes(mValues[position].deviceAddress.encodeToByteArray()).mostSignificantBits
	}

/**
	* Provide a reference to the views for each data item
	* Complex data items may need more than one view per item, and
  * you provide access to all the views for a data item in a view holder.
	*/
	inner class DeviceViewHolder(val mView: View) : RecyclerView.ViewHolder(mView) {
		val mIdView: TextView = mView.item_number
		val mContentView: TextView = mView.content

		override fun toString(): String {
			return super.toString() + " '" + mContentView.text + "'"
		}
	}
}
