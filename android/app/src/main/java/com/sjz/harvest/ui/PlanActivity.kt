package com.sjz.harvest.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.sjz.harvest.R
import com.sjz.harvest.core.config.CatalogIndex
import com.sjz.harvest.core.config.Plan
import com.sjz.harvest.core.config.PlanEntry
import com.sjz.harvest.core.config.ServiceLocator
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.databinding.ActivityPlanBinding
import com.sjz.harvest.databinding.DialogAddCatalogBinding
import com.sjz.harvest.service.AutomationController
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 生产计划编辑：
 * - 四个部门 Tab；
 * - 条目支持启用/禁用、上移/下移、删除；
 * - 从 catalog 搜索并添加物品，count=-1 表示无限；
 * - 显式保存到 ConfigRepository；运行中保存会提示下次启动生效。
 */
class PlanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlanBinding
    private lateinit var adapter: PlanAdapter
    private lateinit var backCallback: OnBackPressedCallback

    private val planMap = linkedMapOf<Dept, MutableList<PlanEntry>>()
    private var baseDepartments: Map<String, List<PlanEntry>> = emptyMap()
    private var planVersion = 1
    private var catalogIndex: CatalogIndex? = null
    private var currentDept: Dept = Dept.TECH
    private var loading = false
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        adapter = PlanAdapter(
            onToggle = ::onToggle,
            onMoveUp = { move(it, -1) },
            onMoveDown = { move(it, 1) },
            onDelete = ::onDelete,
        )
        binding.rvPlan.layoutManager = LinearLayoutManager(this)
        binding.rvPlan.adapter = adapter

        setupTabs()

        binding.btnAdd.setOnClickListener { openAddDialog() }
        binding.btnSave.setOnClickListener { savePlan(showMessage = true) }
        binding.btnRetry.setOnClickListener { loadData() }

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = confirmExit()
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AutomationController.state.collect { state ->
                    binding.tvPlanNotice.isVisible = state.running
                }
            }
        }

        loadData()
    }

    private fun setupTabs() {
        Dept.entries.forEach { dept ->
            binding.tabDept.addTab(
                binding.tabDept.newTab()
                    .setText(dept.label)
                    .setTag(dept.key),
            )
        }
        binding.tabDept.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentDept = Dept.fromKey(tab.tag as? String ?: "") ?: Dept.TECH
                refreshList()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun loadData() {
        if (loading) return
        loading = true
        showLoading(true)

        lifecycleScope.launch {
            val result = runCatching {
                ServiceLocator.catalogIndex() to ServiceLocator.loadPlan()
            }
            // 先结束 loading，refreshList 才能正确恢复按钮状态。
            loading = false
            result.onSuccess { (catalog, plan) ->
                catalogIndex = catalog
                planVersion = plan.version
                baseDepartments = plan.departments
                planMap.clear()
                Dept.entries.forEach { dept ->
                    planMap[dept] = plan.departments[dept.key]
                        .orEmpty()
                        .toMutableList()
                }
                dirty = false
                backCallback.isEnabled = false
                showLoading(false)
                refreshList()
            }.onFailure { throwable ->
                showLoadError(throwable)
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.progress.isVisible = show
        binding.groupError.isVisible = false
        binding.rvPlan.isVisible = !show
        binding.tvEmpty.isVisible = false
        binding.btnAdd.isEnabled = !show
        binding.btnSave.isEnabled = !show
        if (show) binding.tvPlanInfo.text = getString(R.string.loading)
    }

    private fun showLoadError(throwable: Throwable) {
        binding.progress.isVisible = false
        binding.rvPlan.isVisible = false
        binding.tvEmpty.isVisible = false
        binding.groupError.isVisible = true
        binding.tvError.text = getString(
            R.string.plan_loading_failed_fmt,
            throwable.message ?: throwable.javaClass.simpleName,
        )
        binding.btnAdd.isEnabled = false
        binding.btnSave.isEnabled = false
        binding.tvPlanInfo.text = ""
    }

    private fun refreshList() {
        if (loading) return
        val list = planMap[currentDept].orEmpty()
        adapter.submit(list)

        val enabledCount = list.count { it.enabled }
        val info = getString(R.string.plan_info_fmt, list.size, enabledCount)
        binding.tvPlanInfo.text = if (dirty) {
            "$info · ${getString(R.string.plan_modified)}"
        } else {
            info
        }

        binding.tvEmpty.isVisible = list.isEmpty()
        binding.rvPlan.isVisible = list.isNotEmpty()
        binding.groupError.isVisible = false
        binding.progress.isVisible = false
        binding.btnAdd.isEnabled = catalogIndex != null && !loading
        binding.btnSave.isEnabled = !loading
        binding.tvPlanNotice.isVisible = AutomationController.state.value.running
    }

    private fun markDirty() {
        dirty = true
        backCallback.isEnabled = true
        refreshList()
    }

    private fun onToggle(index: Int, enabled: Boolean) {
        val list = planMap[currentDept] ?: return
        val old = list.getOrNull(index) ?: return
        if (old.enabled == enabled) return
        list[index] = old.copy(enabled = enabled)
        markDirty()
    }

    private fun move(index: Int, delta: Int) {
        val list = planMap[currentDept] ?: return
        val target = index + delta
        if (index !in list.indices || target !in list.indices) return
        val entry = list.removeAt(index)
        list.add(target, entry)
        markDirty()
    }

    private fun onDelete(index: Int) {
        val list = planMap[currentDept] ?: return
        val entry = list.getOrNull(index) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.plan_delete_confirm_title)
            .setMessage(getString(R.string.plan_delete_confirm_message_fmt, entry.item))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                list.removeAt(index)
                markDirty()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun openAddDialog() {
        val catalog = catalogIndex
        if (catalog == null) {
            toast(R.string.plan_add_catalog_missing)
            return
        }
        val dept = currentDept
        val allEntries = catalog.entries(dept)
        if (allEntries.isEmpty()) {
            toast(R.string.plan_department_empty)
            return
        }

        val dialogBinding = DialogAddCatalogBinding.inflate(layoutInflater)
        var filtered = allEntries

        fun applyFilter(query: String) {
            filtered = if (query.isBlank()) {
                allEntries
            } else {
                allEntries.filter {
                    it.name.contains(query, ignoreCase = true) ||
                        it.category.contains(query, ignoreCase = true)
                }
            }
            dialogBinding.lvCatalog.adapter = CatalogAdapter(filtered)
            dialogBinding.tvCatalogEmpty.isVisible = filtered.isEmpty()
        }

        applyFilter("")
        dialogBinding.etSearch.doAfterTextChanged { text ->
            applyFilter(text?.toString().orEmpty())
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.plan_add_title_fmt, dept.label))
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialogBinding.lvCatalog.setOnItemClickListener { _, _, position, _ ->
            val entry = filtered.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            askCount(entry.name)
        }

        dialog.show()
    }

    private fun askCount(itemName: String) {
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = getString(R.string.plan_count_hint)
            setText("1")
            setSelection(text.length)
        }
        container.addView(
            input,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.plan_count_title_fmt, itemName))
            .setView(container)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val count = input.text?.toString()?.trim()?.toIntOrNull()
                if (count == null || count == 0 || count < -1) {
                    toast(R.string.plan_count_invalid)
                    return@setOnClickListener
                }
                val list = planMap[currentDept]
                if (list == null) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                if (list.any { it.item == itemName }) {
                    toast(R.string.plan_add_duplicate)
                    return@setOnClickListener
                }
                list.add(PlanEntry(item = itemName, count = count, enabled = true))
                dialog.dismiss()
                markDirty()
            }
        }

        dialog.show()
    }

    private fun savePlan(showMessage: Boolean) {
        if (loading) return
        if (!dirty) {
            if (showMessage) toast(R.string.plan_save_no_change)
            return
        }
        lifecycleScope.launch {
            binding.btnSave.isEnabled = false
            val saved = persistPlan()
            binding.btnSave.isEnabled = true
            if (saved) {
                refreshList()
                if (showMessage) {
                    toast(
                        if (AutomationController.state.value.running) {
                            R.string.plan_saved_running
                        } else {
                            R.string.plan_saved
                        },
                    )
                }
            }
        }
    }

    private suspend fun persistPlan(): Boolean {
        val merged = LinkedHashMap<String, List<PlanEntry>>()
        // 保留不属于四个部门的扩展键，避免丢失未来 schema 数据
        baseDepartments.forEach { (key, value) ->
            if (Dept.fromKey(key) == null) merged[key] = value
        }
        Dept.entries.forEach { dept ->
            merged[dept.key] = planMap[dept].orEmpty().toList()
        }

        return runCatching {
            ServiceLocator.configRepository().savePlan(
                Plan(version = planVersion, departments = merged),
            )
        }.fold(
            onSuccess = {
                baseDepartments = merged
                dirty = false
                backCallback.isEnabled = false
                true
            },
            onFailure = { throwable ->
                toast(
                    getString(
                        R.string.plan_save_failed_fmt,
                        throwable.message ?: throwable.javaClass.simpleName,
                    ),
                )
                false
            },
        )
    }

    private fun confirmExit() {
        if (!dirty) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.plan_discard_title)
            .setMessage(R.string.plan_discard_message)
            .setPositiveButton(R.string.plan_discard_save) { _, _ ->
                lifecycleScope.launch {
                    if (persistPlan()) {
                        toast(R.string.plan_saved)
                        finish()
                    }
                }
            }
            .setNegativeButton(R.string.plan_discard_exit) { _, _ -> finish() }
            .setNeutralButton(R.string.action_cancel, null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
