package com.sjz.harvest.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.sjz.harvest.core.config.CatalogEntry
import com.sjz.harvest.databinding.ItemCatalogEntryBinding

/**
 * 添加计划时的物品候选列表（ListView 适配器）。
 * 展示物品名 + 分类，方便在较长的字典里定位。
 */
class CatalogAdapter(private val entries: List<CatalogEntry>) : BaseAdapter() {

    override fun getCount(): Int = entries.size

    override fun getItem(position: Int): CatalogEntry = entries[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = if (convertView == null) {
            val created = ItemCatalogEntryBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            created.root.tag = created
            created
        } else {
            @Suppress("UNCHECKED_CAST")
            convertView.tag as ItemCatalogEntryBinding
        }

        val entry = entries[position]
        binding.tvCatalogName.text = entry.name
        binding.tvCatalogCategory.text = entry.category
        return binding.root
    }
}
