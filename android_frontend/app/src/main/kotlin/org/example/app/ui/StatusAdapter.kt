package org.example.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import org.example.app.R

/**
 * PUBLIC_INTERFACE
 * StatusAdapter is a simple ListView adapter to display status/progress lines with color coding.
 *
 * Use addInfo/addSuccess/addError to append lines with appropriate colors.
 * Call clear() to reset the list.
 */
class StatusAdapter(private val context: Context) : BaseAdapter() {

    /** Represents an item in the status list with a type for color styling. */
    data class StatusLine(val text: String, val type: Type) {
        enum class Type { INFO, SUCCESS, ERROR }
    }

    private val items = mutableListOf<StatusLine>()
    private val inflater: LayoutInflater = LayoutInflater.from(context)

    // PUBLIC_INTERFACE
    /** Add an informational line (blue). */
    fun addInfo(text: String) {
        items.add(StatusLine(text, StatusLine.Type.INFO))
        notifyDataSetChanged()
    }

    // PUBLIC_INTERFACE
    /** Add a success line (amber). */
    fun addSuccess(text: String) {
        items.add(StatusLine(text, StatusLine.Type.SUCCESS))
        notifyDataSetChanged()
    }

    // PUBLIC_INTERFACE
    /** Add an error line (red). */
    fun addError(text: String) {
        items.add(StatusLine(text, StatusLine.Type.ERROR))
        notifyDataSetChanged()
    }

    // PUBLIC_INTERFACE
    /** Clear all status lines. */
    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    // PUBLIC_INTERFACE
    /** Append a generic info line; kept for convenience with a single method name. */
    fun add(text: String) = addInfo(text)

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: inflater.inflate(R.layout.item_status_line, parent, false)
        val tv = view.findViewById<TextView>(R.id.tvStatusText)
        val item = items[position]
        tv.text = item.text
        // Apply color via text appearances defined in themes.xml
        when (item.type) {
            StatusLine.Type.INFO -> {
                tv.setTextAppearance(context, R.style.TextAppearance_Ocean_Status_Info)
            }
            StatusLine.Type.SUCCESS -> {
                tv.setTextAppearance(context, R.style.TextAppearance_Ocean_Status_Success)
            }
            StatusLine.Type.ERROR -> {
                tv.setTextAppearance(context, R.style.TextAppearance_Ocean_Status_Error)
            }
        }
        return view
    }
}
