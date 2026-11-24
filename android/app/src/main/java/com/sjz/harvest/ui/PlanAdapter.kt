package com.sjz.harvest.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sjz.harvest.R
import com.sjz.harvest.core.config.PlanEntry
import com.sjz.harvest.databinding.ItemPlanEntryBinding

/**
 * 单个部门的计划条目列表适配器。
 * 所有修改操作通过回调交给 Activity 处理，Adapter 本身不持有可变计划数据。
 */
class PlanAdapter(
    private val onToggle: (index: Int, enabled: Boolean) -> Unit,
    private val onMoveUp: (index: Int) -> Unit,
    private val onMoveDown: (index: Int) -> Unit,
    private val onDelete: (index: Int) -> Unit,
) : RecyclerView.Adapter<PlanAdapter.VH>() {

    private var items: List<PlanEntry> = emptyList()

    fun submit(newItems: List<PlanEntry>) {
        items = newItems.toList()
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPlanEntryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val binding = holder.binding
        val entry = items[position]
        val context = binding.root.context

        binding.tvItemName.text = entry.item
        binding.tvItemCount.text = if (entry.count == -1) {
            context.getString(R.string.plan_count_infinite)
        } else {
            context.getString(R.string.plan_count_remaining_fmt, entry.count)
        }

        // 先清空监听，避免复用时触发回调
        binding.switchEnabled.setOnCheckedChangeListener(null)
        binding.switchEnabled.isChecked = entry.enabled
        binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) onToggle(index, checked)
        }

        binding.btnUp.isEnabled = position > 0
        binding.btnDown.isEnabled = position in 0 until (items.size - 1)
        binding.btnUp.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current != RecyclerView.NO_POSITION) onMoveUp(current)
        }
        binding.btnDown.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current != RecyclerView.NO_POSITION) onMoveDown(current)
        }
        binding.btnDelete.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current != RecyclerView.NO_POSITION) onDelete(current)
        }
    }

    class VH(val binding: ItemPlanEntryBinding) : RecyclerView.ViewHolder(binding.root)
}
