package com.sjz.harvest.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.sjz.harvest.R
import com.sjz.harvest.core.config.AppConfig
import com.sjz.harvest.core.config.ServiceLocator
import com.sjz.harvest.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

/**
 * 设置页：输入后端、dry-run、自动买材料、安全限制、时序。
 * 所有修改保存到 app_config.json，并在下一次 start 时生效。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var loadedConfig: AppConfig? = null
    private var loading = false
    private var suppressAutoBuyCallback = false

    private val backendKeys = listOf("auto", "root", "accessibility", "dry_run")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupBackendSpinner()

        binding.swAutoBuy.setOnCheckedChangeListener { _, checked ->
            handleAutoBuyToggle(checked)
        }
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnReset.setOnClickListener { confirmReset() }
        binding.btnRetry.setOnClickListener { loadData() }

        loadData()
    }

    private fun setupBackendSpinner() {
        val labels = listOf(
            getString(R.string.settings_backend_auto),
            getString(R.string.settings_backend_root),
            getString(R.string.settings_backend_accessibility),
            getString(R.string.settings_backend_dry_run),
        )
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels,
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBackend.adapter = adapter
    }

    private fun loadData() {
        if (loading) return
        loading = true
        binding.progress.isVisible = true
        binding.groupError.isVisible = false
        binding.groupContent.isVisible = false
        binding.btnSave.isEnabled = false

        lifecycleScope.launch {
            runCatching {
                ServiceLocator.appConfig() to ServiceLocator.profile()
            }.onSuccess { (config, profile) ->
                loadedConfig = config
                bindConfig(config)
                bindProfile(profile)
                binding.groupContent.isVisible = true
                binding.groupError.isVisible = false
                binding.btnSave.isEnabled = true
            }.onFailure { throwable ->
                binding.groupContent.isVisible = false
                binding.groupError.isVisible = true
                binding.tvError.text = getString(
                    R.string.settings_load_failed_fmt,
                    throwable.message ?: throwable.javaClass.simpleName,
                )
                binding.btnSave.isEnabled = false
            }
            binding.progress.isVisible = false
            loading = false
        }
    }

    private fun bindConfig(config: AppConfig) {
        val backendIndex = backendKeys.indexOf(config.input.backend).coerceAtLeast(0)
        binding.spinnerBackend.setSelection(backendIndex, false)

        binding.swDryRun.isChecked = config.safety.dryRun
        binding.swOcrDump.isChecked = config.logging.dumpOcrText

        suppressAutoBuyCallback = true
        binding.swAutoBuy.isChecked = config.safety.autoBuyMaterials
        suppressAutoBuyCallback = false

        binding.etMaxCycles.setText(config.safety.maxCycles.toString())
        binding.etMaxRuntime.setText(config.safety.maxRuntimeMinutes.toString())
        binding.etMinInterval.setText(config.capture.minIntervalMs.toString())
        binding.etActionDelayMin.setText(config.input.actionDelayMinMs.toString())
        binding.etActionDelayMax.setText(config.input.actionDelayMaxMs.toString())
        binding.etCycleBuffer.setText(config.timing.cycleBufferSec.toString())
    }

    private fun bindProfile(profile: com.sjz.harvest.core.config.UiProfile) {
        binding.tvProfileInfo.text = getString(
            R.string.settings_profile_fmt,
            profile.name,
            profile.baseWidth,
            profile.baseHeight,
        )
        binding.tvSupportedWidths.text = getString(
            R.string.settings_supported_widths_fmt,
            profile.supportedWidths.joinToString(", "),
        )
    }

    private fun handleAutoBuyToggle(checked: Boolean) {
        if (suppressAutoBuyCallback || !checked) return

        // 先恢复为关闭，等用户确认后再真正打开，避免误触直接生效。
        suppressAutoBuyCallback = true
        binding.swAutoBuy.isChecked = false
        suppressAutoBuyCallback = false

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_auto_buy_confirm_title)
            .setMessage(R.string.settings_auto_buy_confirm_message)
            .setPositiveButton(R.string.settings_auto_buy_confirm_ok) { _, _ ->
                suppressAutoBuyCallback = true
                binding.swAutoBuy.isChecked = true
                suppressAutoBuyCallback = false
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun saveSettings() {
        val config = loadedConfig ?: return
        val backend = backendKeys.getOrElse(binding.spinnerBackend.selectedItemPosition) {
            config.input.backend
        }

        val maxCycles = readLong(binding.etMaxCycles)
        if (maxCycles == null) return toast(R.string.settings_invalid_number)
        if (maxCycles < 0 || maxCycles > Int.MAX_VALUE) {
            return toast(R.string.settings_invalid_cycles)
        }

        val maxRuntime = readLong(binding.etMaxRuntime)
        if (maxRuntime == null) return toast(R.string.settings_invalid_number)
        if (maxRuntime < 0 || maxRuntime > Int.MAX_VALUE) {
            return toast(R.string.settings_invalid_runtime)
        }

        val minInterval = readLong(binding.etMinInterval)
        if (minInterval == null) return toast(R.string.settings_invalid_number)
        if (minInterval < 333) return toast(R.string.settings_invalid_interval)

        val delayMin = readLong(binding.etActionDelayMin)
        if (delayMin == null) return toast(R.string.settings_invalid_number)
        val delayMax = readLong(binding.etActionDelayMax)
        if (delayMax == null) return toast(R.string.settings_invalid_number)
        if (delayMin < 0 || delayMax < 0 || delayMin > delayMax) {
            return toast(R.string.settings_invalid_delay)
        }

        val buffer = readLong(binding.etCycleBuffer)
        if (buffer == null) return toast(R.string.settings_invalid_number)
        if (buffer < 0 || buffer > Int.MAX_VALUE) {
            return toast(R.string.settings_invalid_buffer)
        }

        val updated = config.copy(
            input = config.input.copy(
                backend = backend,
                actionDelayMinMs = delayMin,
                actionDelayMaxMs = delayMax,
            ),
            capture = config.capture.copy(minIntervalMs = minInterval),
            timing = config.timing.copy(cycleBufferSec = buffer.toInt()),
            safety = config.safety.copy(
                dryRun = binding.swDryRun.isChecked,
                autoBuyMaterials = binding.swAutoBuy.isChecked,
                maxCycles = maxCycles.toInt(),
                maxRuntimeMinutes = maxRuntime.toInt(),
            ),
            logging = config.logging.copy(
                dumpOcrText = binding.swOcrDump.isChecked,
            ),
        )

        lifecycleScope.launch {
            binding.btnSave.isEnabled = false
            runCatching {
                ServiceLocator.configRepository().saveAppConfig(updated)
            }.onSuccess {
                loadedConfig = updated
                toast(R.string.settings_saved)
            }.onFailure { throwable ->
                toast(
                    getString(
                        R.string.settings_save_failed_fmt,
                        throwable.message ?: throwable.javaClass.simpleName,
                    ),
                )
            }
            binding.btnSave.isEnabled = true
        }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_reset_title)
            .setMessage(R.string.settings_reset_message)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                lifecycleScope.launch {
                    binding.btnSave.isEnabled = false
                    runCatching {
                        ServiceLocator.configRepository().resetToDefaults()
                    }.onSuccess {
                        toast(R.string.settings_reset_done)
                        loadData()
                    }.onFailure { throwable ->
                        toast(
                            getString(
                                R.string.settings_reset_failed_fmt,
                                throwable.message ?: throwable.javaClass.simpleName,
                            ),
                        )
                        binding.btnSave.isEnabled = true
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun readLong(edit: TextInputEditText): Long? =
        edit.text?.toString()?.trim()?.toLongOrNull()

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
