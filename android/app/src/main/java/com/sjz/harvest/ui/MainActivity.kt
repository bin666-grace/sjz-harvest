package com.sjz.harvest.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sjz.harvest.R
import com.sjz.harvest.core.config.ServiceLocator
import com.sjz.harvest.core.logging.LogBus
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.databinding.ActivityMainBinding
import com.sjz.harvest.service.AutomationController
import com.sjz.harvest.service.AutomationUiState
import com.sjz.harvest.service.SjzAccessibilityService
import com.sjz.harvest.tools.DiagnosticItem
import com.sjz.harvest.tools.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面：运行状态、四部门状态、控制按钮、日志。
 * Activity 只观察 StateFlow；退出界面不会停止后台自动化。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var logAdapter: LogAdapter

    private var gamePackage: String = "com.tencent.tmgp.dfm"
    private var dryRunEnabled: Boolean = false
    private var lastState: AutomationUiState = AutomationUiState()
    private var lastErrorShown: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // 无论是否授权都允许继续；没有通知权限时自动化仍可运行。
        doStartAutomation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        LogBus.install(applicationContext, 300)

        logAdapter = LogAdapter()
        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = logAdapter

        setupButtons()
        collectFlows()
    }

    private fun setupButtons() {
        binding.btnAccessibility.setOnClickListener {
            SjzAccessibilityService.openSettings(this)
        }
        binding.btnStart.setOnClickListener { requestStart() }
        binding.btnPause.setOnClickListener {
            if (lastState.paused) {
                AutomationController.resume()
            } else {
                AutomationController.pause()
            }
        }
        binding.btnStop.setOnClickListener { confirmStop() }
        binding.btnDiagnostics.setOnClickListener { runDiagnostics() }
        binding.btnPlan.setOnClickListener {
            startActivity(Intent(this, PlanActivity::class.java))
        }
        binding.btnCalibration.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun collectFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AutomationController.state.collect { renderState(it) } }
                launch {
                    SjzAccessibilityService.instance.collect {
                        updateAccessibilityStatus()
                        updateForegroundStatus()
                    }
                }
                launch {
                    combine(LogBus.lines, AutomationController.state) { lines, state ->
                        if (lines.isNotEmpty()) lines else state.logs
                    }.collect { renderLogs(it) }
                }
                launch {
                    // 每秒刷新倒计时 / 前台包名 / 无障碍开关状态
                    while (true) {
                        updateCountdown()
                        updateAccessibilityStatus()
                        updateForegroundStatus()
                        delay(1_000)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        updateAccessibilityStatus()
        updateForegroundStatus()
        refreshConfig()
    }

    private fun refreshConfig() {
        lifecycleScope.launch {
            runCatching { ServiceLocator.appConfig() }
                .onSuccess { config ->
                    gamePackage = config.gamePackage
                    dryRunEnabled = config.safety.dryRun
                    binding.tvInputBackend.text = if (dryRunEnabled) {
                        getString(R.string.main_backend_dry_run_fmt, config.input.backend)
                    } else {
                        getString(R.string.main_input_backend_fmt, config.input.backend)
                    }
                    updateForegroundStatus()
                }
                .onFailure {
                    binding.tvInputBackend.text = getString(R.string.main_input_backend_none)
                }
        }
    }

    private fun updateAccessibilityStatus() {
        val enabled = runCatching {
            SjzAccessibilityService.isEnabled(this)
        }.getOrDefault(false)
        val connected = SjzAccessibilityService.instance.value != null

        val text: String
        val colorRes: Int
        when {
            connected -> {
                text = getString(R.string.main_accessibility_connected)
                colorRes = R.color.sjz_ok
            }

            enabled -> {
                text = getString(R.string.main_accessibility_on_unconnected)
                colorRes = R.color.sjz_warn
            }

            else -> {
                text = getString(R.string.main_accessibility_off)
                colorRes = R.color.sjz_error
            }
        }
        binding.tvAccessibility.text = "● $text"
        binding.tvAccessibility.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun updateForegroundStatus() {
        val foreground = runCatching {
            SjzAccessibilityService.instance.value?.foregroundPackage()
        }.getOrNull()

        if (foreground.isNullOrBlank()) {
            binding.tvForeground.text = getString(R.string.main_foreground_none)
            binding.tvForeground.setTextColor(
                ContextCompat.getColor(this, R.color.sjz_muted),
            )
            return
        }

        val isGame = foreground == gamePackage
        val suffix = if (isGame) {
            getString(R.string.main_game_foreground)
        } else {
            getString(R.string.main_game_not_foreground)
        }
        binding.tvForeground.text =
            getString(R.string.main_foreground_fmt, foreground) + " · " + suffix
        binding.tvForeground.setTextColor(
            ContextCompat.getColor(
                this,
                if (isGame) R.color.sjz_ok else R.color.sjz_warn,
            ),
        )
    }

    private fun renderState(state: AutomationUiState) {
        lastState = state

        val phase = state.phase.ifBlank { getString(R.string.main_run_phase_idle) }
        binding.tvRunState.text = getString(R.string.main_run_state_fmt, phase)
        binding.tvRunState.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    state.error != null -> R.color.sjz_error
                    state.running -> R.color.sjz_ok
                    else -> R.color.sjz_on_surface
                },
            ),
        )

        binding.tvCycleCount.text = getString(
            R.string.main_cycle_count_fmt,
            state.cycleCount,
        )

        val error = state.error
        val message = error ?: state.message
        if (message.isNullOrBlank()) {
            binding.tvMessage.visibility = View.GONE
        } else {
            binding.tvMessage.visibility = View.VISIBLE
            binding.tvMessage.text = message
            binding.tvMessage.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (error != null) R.color.sjz_error else R.color.sjz_muted,
                ),
            )
        }

        if (error != null && error != lastErrorShown) {
            lastErrorShown = error
            Toast.makeText(
                this,
                getString(R.string.main_run_error_fmt, error),
                Toast.LENGTH_LONG,
            ).show()
        }
        if (error == null) lastErrorShown = null

        binding.btnStart.isEnabled = !state.running
        binding.btnPause.isEnabled = state.running || state.paused
        binding.btnStop.isEnabled = state.running || state.paused
        binding.btnPause.text = if (state.paused) {
            getString(R.string.main_btn_resume)
        } else {
            getString(R.string.main_btn_pause)
        }

        Dept.entries.forEach { dept ->
            val status = state.statuses[dept.key]
            val text = when {
                status != null -> status
                state.running -> getString(R.string.main_dept_status_unknown)
                else -> getString(R.string.main_dept_status_pending)
            }
            val target = when (dept) {
                Dept.TECH -> binding.tvTechStatus
                Dept.WORK -> binding.tvWorkStatus
                Dept.MEDICAL -> binding.tvMedicalStatus
                Dept.ARMOR -> binding.tvArmorStatus
            }
            target.text = text
            target.setTextColor(
                ContextCompat.getColor(this, departmentStatusColor(status)),
            )
        }

        updateCountdown()
    }

    private fun departmentStatusColor(status: String?): Int = when {
        status == null -> R.color.sjz_muted
        status.contains("完成") || status.contains("收取") || status.contains("领取") ->
            R.color.sjz_warn

        status.contains("制造") -> R.color.sjz_primary
        status.contains("空闲") -> R.color.sjz_ok
        else -> R.color.sjz_on_surface
    }

    private fun updateCountdown() {
        val state = lastState
        val target = state.nextWakeAtMs
        if (!state.running || state.paused || target == null) {
            binding.tvNextWake.text = getString(R.string.main_next_wake_none)
            return
        }
        val remainingSec = ((target - System.currentTimeMillis()) / 1000)
            .coerceAtLeast(0)
            .toInt()
        val text = if (remainingSec >= 60) {
            getString(
                R.string.main_minutes_seconds_fmt,
                remainingSec / 60,
                remainingSec % 60,
            )
        } else {
            getString(R.string.main_seconds_fmt, remainingSec)
        }
        binding.tvNextWake.text = getString(R.string.main_next_wake_fmt, text)
    }

    private fun renderLogs(lines: List<String>) {
        logAdapter.submit(lines)
        binding.tvLogEmpty.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
        if (lines.isNotEmpty()) {
            binding.rvLogs.post {
                if (logAdapter.itemCount > 0) {
                    binding.rvLogs.scrollToPosition(logAdapter.itemCount - 1)
                }
            }
        }
    }

    private fun requestStart() {
        if (!runCatching { SjzAccessibilityService.isEnabled(this) }.getOrDefault(false)) {
            showAccessibilityDialog(connected = false)
            return
        }
        if (SjzAccessibilityService.instance.value == null) {
            showAccessibilityDialog(connected = true)
            return
        }
        // dry-run 只记录、不点击。旧版本/测试用的配置可能把它留在开启状态，
        // 用户看到的就是「自动化在跑但什么都不点」。开始前必须显式确认。
        if (dryRunEnabled) {
            showDryRunDialog()
            return
        }
        startAfterGuards()
    }

    private fun startAfterGuards() {
        // MIUI/EMUI 等机型：不把本 App 排除出电池优化时，进程可能在游戏切前台后
        // 被冻结，表现为自动化「卡住」，恢复后误截 App 自己的界面。只提示一次。
        maybeWarnBatteryOptimization {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                doStartAutomation()
            }
        }
    }

    private fun showDryRunDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_dry_run_title)
            .setMessage(R.string.main_dry_run_message)
            .setPositiveButton(R.string.main_dry_run_switch) { _, _ ->
                disableDryRunAndStart()
            }
            .setNeutralButton(R.string.main_dry_run_continue) { _, _ ->
                startAfterGuards()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 关闭持久化的 dry-run 后立即开始，让「开始」按钮一定产生真实点击。 */
    private fun disableDryRunAndStart() {
        lifecycleScope.launch {
            runCatching {
                val config = ServiceLocator.appConfig()
                ServiceLocator.configRepository().saveAppConfig(
                    config.copy(safety = config.safety.copy(dryRun = false)),
                )
            }.onSuccess {
                dryRunEnabled = false
                Toast.makeText(
                    this@MainActivity,
                    R.string.main_dry_run_switched,
                    Toast.LENGTH_SHORT,
                ).show()
                refreshConfig()
                startAfterGuards()
            }.onFailure { throwable ->
                Toast.makeText(
                    this@MainActivity,
                    getString(
                        R.string.main_dry_run_switch_failed_fmt,
                        throwable.message ?: throwable.javaClass.simpleName,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun maybeWarnBatteryOptimization(continueStart: () -> Unit) {
        val powerManager = getSystemService(PowerManager::class.java)
        val ignoring = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: true
        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (ignoring || preferences.getBoolean(KEY_BATTERY_PROMPTED, false)) {
            continueStart()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_battery_title)
            .setMessage(R.string.main_battery_message)
            .setPositiveButton(R.string.main_battery_open) { _, _ ->
                preferences.edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply()
                openBatteryOptimizationSettings()
            }
            .setNegativeButton(R.string.main_battery_continue) { _, _ ->
                preferences.edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply()
                continueStart()
            }
            .setCancelable(false)
            .show()
    }

    // 本应用不做商店分发；该 API 是让用户主动放行长任务的标准入口。
    @SuppressLint("BatteryLife")
    private fun openBatteryOptimizationSettings() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val started = runCatching {
            startActivity(direct)
            true
        }.getOrDefault(false)
        if (!started) {
            runCatching { startActivity(fallback) }
                .onFailure {
                    Toast.makeText(
                        this,
                        getString(R.string.main_battery_manual_hint),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun doStartAutomation() {
        runCatching { AutomationController.start(this) }
            .onFailure { throwable ->
                Toast.makeText(
                    this,
                    getString(
                        R.string.main_start_failed_fmt,
                        throwable.message ?: throwable.javaClass.simpleName,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
    }

    private fun showAccessibilityDialog(connected: Boolean) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(
                if (connected) {
                    R.string.main_accessibility_not_connected_title
                } else {
                    R.string.main_accessibility_dialog_title
                },
            )
            .setMessage(
                if (connected) {
                    R.string.main_accessibility_not_connected_message
                } else {
                    R.string.main_accessibility_dialog_message
                },
            )
            .setPositiveButton(R.string.main_btn_go_settings) { _, _ ->
                SjzAccessibilityService.openSettings(this)
            }
            .setNegativeButton(R.string.action_cancel, null)

        if (connected) {
            builder.setNeutralButton(R.string.main_btn_start_anyway) { _, _ ->
                doStartAutomation()
            }
        }
        builder.show()
    }

    private fun confirmStop() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_stop_dialog_title)
            .setMessage(R.string.main_stop_dialog_message)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                AutomationController.stop()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun runDiagnostics() {
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_diag_running_title)
            .setMessage(R.string.main_diag_running_message)
            .setCancelable(false)
            .create()
        progress.show()

        lifecycleScope.launch {
            val items = try {
                withContext(Dispatchers.IO) {
                    Diagnostics.run(applicationContext)
                }
            } catch (throwable: Throwable) {
                listOf(
                    DiagnosticItem(
                        getString(R.string.main_btn_diagnostics),
                        false,
                        throwable.message ?: throwable.javaClass.simpleName,
                    ),
                )
            }
            if (isFinishing || isDestroyed) return@launch
            progress.dismiss()
            showDiagnosticsResult(items)
        }
    }

    private fun showDiagnosticsResult(items: List<DiagnosticItem>) {
        val message = if (items.isEmpty()) {
            getString(R.string.main_diag_empty)
        } else {
            buildString {
                items.forEachIndexed { index, item ->
                    if (index > 0) append("\n\n")
                    append(if (item.ok) "✅ " else "❌ ")
                    append(item.name)
                    append('\n')
                    append("    ")
                    append(item.detail)
                }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_diag_result_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    companion object {
        private const val PREFS_NAME = "sjz_main"
        private const val KEY_BATTERY_PROMPTED = "battery_prompted"
    }
}
