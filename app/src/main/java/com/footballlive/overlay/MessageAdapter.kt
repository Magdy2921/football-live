package com.footballlive.overlay

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = ArrayList<ChatMessage>()
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    var fontSp = 13f
        set(v) {
            if (field != v) { field = v; notifyDataSetChanged() }
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.avatar)
        val author: TextView = v.findViewById(R.id.author)
        val time: TextView = v.findViewById(R.id.time)
        val content: TextView = v.findViewById(R.id.content)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val m = items[position]
        h.author.text = m.author
        h.author.textSize = fontSp
        h.content.text = m.content
        h.content.textSize = fontSp
        h.time.text = fmt.format(Date(m.ts))
        h.time.textSize = (fontSp - 3f).coerceAtLeast(8f)
        if (m.avatar.isBlank()) {
            h.avatar.setImageResource(R.drawable.avatar_placeholder)
        } else {
            h.avatar.load(m.avatar) {
                placeholder(R.drawable.avatar_placeholder)
                error(R.drawable.avatar_placeholder)
                transformations(CircleCropTransformation())
            }
        }
    }

    fun add(m: ChatMessage) {
        if (items.any { it.id == m.id }) return
        items.add(m)
        notifyItemInserted(items.size - 1)
    }

    fun remove(id: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) { items.removeAt(i); notifyItemRemoved(i) }
    }

    fun clear() {
        val n = items.size
        items.clear()
        notifyItemRangeRemoved(0, n)
    }

    fun setAll(list: List<ChatMessage>) {
        items.clear(); items.addAll(list)
        notifyDataSetChanged()
    }

    fun trimTo(max: Int) {
        while (items.size > max) { items.removeAt(0); notifyItemRemoved(0) }
    }

    fun size() = items.size
}
