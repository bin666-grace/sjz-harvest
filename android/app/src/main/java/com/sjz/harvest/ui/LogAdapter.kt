package com.sjz.harvest.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sjz.harvest.databinding.ItemLogBinding

/**
 * 运行日志列表。数据量小（最多几百行），直接 notifyDataSetChanged 足够。
 */
class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

    private var items: List<String> = emptyList()

    fun submit(newLines: List<String>) {
        if (newLines == items) return
        items = newLines.toList()
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.binding.tvLogLine.text = items[position]
    }

    class VH(val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root)
}
