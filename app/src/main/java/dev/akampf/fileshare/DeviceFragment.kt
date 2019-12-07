package dev.akampf.fileshare

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

/**
 * A fragment representing a list of WiFi Direct Devices.
 * Activities containing this fragment MUST implement the
 * [DeviceFragment.OnListFragmentInteractionListener] interface.
 */
class DeviceFragment : Fragment() {

	private var columnCount = 1

	private var listener: OnListFragmentInteractionListener? = null

	lateinit var recyclerViewAdapter: WiFiDirectPeerDevicesRecyclerViewAdapter

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		arguments?.let {
			columnCount = it.getInt(ARG_COLUMN_COUNT)
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		val view = inflater.inflate(R.layout.fragment_device_list, container, false)


		// listener was set in onAttach and is the view containing this fragment, in our case the main activity
		val mainActivity = listener as MainActivity
		// we use that to set a reference to this fragment for the main activity to later interact with the recyclerViewAdaptor to notify it
		// of changes to the device list
		mainActivity.deviceFragment = this

		recyclerViewAdapter = WiFiDirectPeerDevicesRecyclerViewAdapter(mainActivity.wiFiDirectPeers, listener)

		// Set the adapter
		if (view is RecyclerView) {
			with(view) {
				layoutManager = when {
					columnCount <= 1 -> LinearLayoutManager(context)
					else -> GridLayoutManager(context, columnCount)
				}
				adapter = recyclerViewAdapter
			}
		}
		return view
	}

	override fun onAttach(context: Context) {
		super.onAttach(context)
		// TODO remove runtime check?
		if (context is OnListFragmentInteractionListener) {
			listener = context
		} else {
			throw RuntimeException("$context must implement OnListFragmentInteractionListener")
		}
	}

	override fun onDetach() {
		super.onDetach()
		listener = null
	}

	/**
	 * This interface must be implemented by activities that contain this
	 * fragment to allow an interaction in this fragment to be communicated
	 * to the activity and potentially other fragments contained in that
	 * activity.
	 *
	 *
	 * See the Android Training lesson
	 * [Communicating with Other Fragments](http://developer.android.com/training/basics/fragments/communicating.html)
	 * for more information.
	 */
	interface OnListFragmentInteractionListener {
		fun onListFragmentInteraction(wiFiDirectDevice: WifiP2pDevice)
	}

	companion object {

		// TODO: Customize parameter argument names
		const val ARG_COLUMN_COUNT = "column-count"

		// TODO: Customize parameter initialization
		@JvmStatic
		fun newInstance(columnCount: Int) =
			DeviceFragment().apply {
				arguments = Bundle().apply {
					putInt(ARG_COLUMN_COUNT, columnCount)
				}
			}
	}
}
