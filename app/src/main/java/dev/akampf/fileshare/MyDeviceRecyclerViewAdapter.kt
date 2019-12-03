package dev.akampf.fileshare

import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView


import dev.akampf.fileshare.DeviceFragment.OnListFragmentInteractionListener
import dev.akampf.fileshare.dummy.DummyContent.DummyItem

import kotlinx.android.synthetic.main.fragment_device.view.*

/**
 * [RecyclerView.Adapter] that can display a [DummyItem] and makes a call to the
 * specified [OnListFragmentInteractionListener].
 * TODO: Replace the implementation with code for your data type.
 */
class MyDeviceRecyclerViewAdapter(
	private val mValues: List<DummyItem>,
	private val mListener: OnListFragmentInteractionListener?
) : RecyclerView.Adapter<MyDeviceRecyclerViewAdapter.ViewHolder>() {

	private val mOnClickListener: View.OnClickListener

	init {
		mOnClickListener = View.OnClickListener { v ->
			val item = v.tag as DummyItem
			// Notify the active callbacks interface (the activity, if the fragment is attached to
			// one) that an item has been selected.
			mListener?.onListFragmentInteraction(item)
		}
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val view = LayoutInflater.from(parent.context)
			.inflate(R.layout.fragment_device, parent, false)
		return ViewHolder(view)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		val item = mValues[position]
		holder.mIdView.text = item.id
		holder.mContentView.text = item.content

		with(holder.mView) {
			// Set tag for the view that can be clicked to an identifier for the content or teh content itself so we can later retrieve
			// e.g. the data associated (if only identifier is used) and notify the containing activity of the click with this info, since
			// we only know which view was clicked
			tag = item
			setOnClickListener(mOnClickListener)
		}
	}

	override fun getItemCount(): Int = mValues.size

	inner class ViewHolder(val mView: View) : RecyclerView.ViewHolder(mView) {
		val mIdView: TextView = mView.item_number
		val mContentView: TextView = mView.content

		override fun toString(): String {
			return super.toString() + " '" + mContentView.text + "'"
		}
	}
}
