package com.sjz.harvest.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sjz.harvest.R
import com.sjz.harvest.core.config.ServiceLocator
import com.sjz.harvest.core.config.UiProfile
import com.sjz.harvest.core.model.PointI
import com.sjz.harvest.core.model.RectI
import com.sjz.harvest.databinding.ActivityCalibrationBinding
import com.sjz.harvest.service.SjzAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 校准页：截取当前游戏画面，拖框/点选后换算为 1920×1080 基准坐标，
 * 保存到 profile.json（或本地扩展配置）。
 */
class CalibrationActivity : AppCompatActivity() {

    private enum class Step(val labelRes: Int, val isRect: Boolean) {
        VIEWPORT(R.string.calibration_step_viewport, true),
        SCROLL_FROM(R.string.calibration_step_scroll_from, false),
        SCROLL_TO(R.string.calibration_step_scroll_to, false),
        COLLECT_POINT(R.string.calibration_step_collect, false),
        CRAFT_POINT(R.string.calibration_step_craft, false),
    }

    private lateinit var binding: ActivityCalibrationBinding
    private var profile: UiProfile? = null
    private var captured: Bitmap? = null
    private var currentStep: Step = Step.VIEWPORT
    private var capturing = false

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupStepSpinner()
        setupEditor()

        binding.btnHelp.setOnClickListener { showHelpDialog() }
        binding.btnCapture.setOnClickListener { startCapture() }
        binding.btnClear.setOnClickListener { confirmClear() }
        binding.btnSave.setOnClickListener { saveCurrent() }

        loadProfile()
    }

    private fun setupStepSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Step.entries.map { getString(it.labelRes) },
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerStep.adapter = adapter

        binding.spinnerStep.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) {
                currentStep = Step.entries.getOrElse(position) { Step.VIEWPORT }
                applyStep()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupEditor() {
        binding.roiEditor.onSelectionChanged = { rect, point ->
            binding.tvSelection.text = getString(
                R.string.calibration_selection_fmt,
                formatSelection(rect, point),
            )
        }
    }

    private fun loadProfile() {
        lifecycleScope.launch {
            binding.progress.isVisible = true
            runCatching { ServiceLocator.profile() }
                .onSuccess { loaded ->
                    profile = loaded
                    binding.roiEditor.baseWidth = loaded.baseWidth
                    binding.roiEditor.baseHeight = loaded.baseHeight
                    binding.tvBaseNote.text = getString(
                        R.string.calibration_base_note,
                        loaded.baseWidth,
                        loaded.baseHeight,
                    )
                    binding.btnSave.isEnabled = true
                    applyStep()
                }
                .onFailure { throwable ->
                    toast(
                        getString(
                            R.string.calibration_profile_failed_fmt,
                            throwable.message ?: throwable.javaClass.simpleName,
                        ),
                    )
                }
            binding.progress.isVisible = false
        }
    }

    private fun applyStep() {
        val step = currentStep
        binding.tvExtraNote.isVisible = step == Step.CRAFT_POINT
        binding.roiEditor.mode = if (step.isRect) {
            RoiEditorView.Mode.RECT
        } else {
            RoiEditorView.Mode.POINT
        }
        profile?.let {
            binding.roiEditor.baseWidth = it.baseWidth
            binding.roiEditor.baseHeight = it.baseHeight
        }

        val (rect, point) = storedValue(step)
        binding.roiEditor.setExisting(rect, point)
        updateCurrentValueText()
        binding.tvSelection.text = getString(
            R.string.calibration_selection_fmt,
            formatSelection(rect, point),
        )
    }

    private fun storedValue(step: Step): Pair<RectI?, PointI?> {
        val current = profile ?: return null to null
        return when (step) {
            Step.VIEWPORT -> current.itemList.viewport to null
            Step.SCROLL_FROM -> null to current.itemList.scrollFrom
            Step.SCROLL_TO -> null to current.itemList.scrollTo
            Step.COLLECT_POINT -> null to current.dashboard.collectFixedPoint
            // 旧版本把开始制造兜底点存在 SharedPreferences；存在就作为初始值迁移。
            Step.CRAFT_POINT -> null to (current.craft.startPoint ?: loadExtraPoint())
        }
    }

    private fun updateCurrentValueText() {
        val (rect, point) = storedValue(currentStep)
        val value = when {
            rect != null -> getString(
                R.string.calibration_rect_fmt,
                rect.x, rect.y, rect.right, rect.bottom, rect.w, rect.h,
            )

            point != null -> getString(R.string.calibration_point_fmt, point.x, point.y)
            else -> getString(R.string.calibration_unset)
        }
        binding.tvCurrentValue.text = getString(R.string.calibration_current_fmt, value)
    }

    private fun formatSelection(rect: RectI?, point: PointI?): String = when {
        rect != null -> getString(
            R.string.calibration_rect_fmt,
            rect.x, rect.y, rect.right, rect.bottom, rect.w, rect.h,
        )

        point != null -> getString(R.string.calibration_point_fmt, point.x, point.y)
        else -> getString(R.string.calibration_value_none)
    }

    private fun startCapture() {
        if (capturing) return
        if (profile == null) {
            toast(R.string.calibration_loading)
            return
        }
        if (SjzAccessibilityService.instance.value == null) {
            showNoServiceDialog()
            return
        }

        capturing = true
        binding.btnCapture.isEnabled = false
        binding.btnClear.isEnabled = false
        binding.btnSave.isEnabled = false

        lifecycleScope.launch {
            try {
                for (second in 3 downTo 1) {
                    binding.tvCountdown.text = getString(
                        R.string.calibration_countdown_fmt,
                        second,
                    )
                    delay(1_000)
                }
                binding.tvCountdown.text = getString(R.string.calibration_capture_hint)

                val service = SjzAccessibilityService.instance.value
                    ?: throw IllegalStateException(
                        getString(R.string.calibration_no_service_message),
                    )
                val bitmap = service.captureBitmap()
                if (bitmap.width <= 0 || bitmap.height <= 0) {
                    throw IllegalStateException("empty bitmap")
                }

                val old = captured
                captured = bitmap
                binding.roiEditor.setBitmap(bitmap)
                if (old != null && old !== bitmap) {
                    runCatching { old.recycle() }
                }
                applyStep()
                binding.tvCountdown.text = getString(
                    R.string.calibration_captured_fmt,
                    bitmap.width,
                    bitmap.height,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val message = getString(
                    R.string.calibration_capture_failed_fmt,
                    throwable.message ?: throwable.javaClass.simpleName,
                )
                binding.tvCountdown.text = message
                toast(message)
            } finally {
                capturing = false
                if (!isFinishing && !isDestroyed) {
                    binding.btnCapture.isEnabled = true
                    binding.btnClear.isEnabled = true
                    binding.btnSave.isEnabled = true
                }
            }
        }
    }

    private fun saveCurrent() {
        val current = profile
        if (current == null) {
            toast(R.string.calibration_loading)
            return
        }

        val step = currentStep
        val rect = binding.roiEditor.selectedRect
        val point = binding.roiEditor.selectedPoint

        if (step.isRect && rect == null) {
            toast(R.string.calibration_no_selection)
            return
        }
        if (!step.isRect && point == null) {
            toast(R.string.calibration_no_selection)
            return
        }

        val updated = when (step) {
            Step.VIEWPORT -> current.copy(
                itemList = current.itemList.copy(viewport = rect),
            )

            Step.SCROLL_FROM -> current.copy(
                itemList = current.itemList.copy(scrollFrom = point),
            )

            Step.SCROLL_TO -> current.copy(
                itemList = current.itemList.copy(scrollTo = point),
            )

            Step.COLLECT_POINT -> current.copy(
                dashboard = current.dashboard.copy(collectFixedPoint = point),
            )

            Step.CRAFT_POINT -> current.copy(
                craft = current.craft.copy(startPoint = point),
            )
        }

        lifecycleScope.launch {
            binding.btnSave.isEnabled = false
            runCatching {
                ServiceLocator.configRepository().saveProfile(updated)
            }.onSuccess {
                profile = updated
                toast(R.string.calibration_saved)
                updateCurrentValueText()
            }.onFailure { throwable ->
                toast(
                    getString(
                        R.string.calibration_save_failed_fmt,
                        throwable.message ?: throwable.javaClass.simpleName,
                    ),
                )
            }
            binding.btnSave.isEnabled = true
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.calibration_clear_confirm_title)
            .setMessage(R.string.calibration_clear_confirm_message)
            .setPositiveButton(R.string.action_clear) { _, _ -> clearCurrent() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun clearCurrent() {
        val current = profile
        if (current == null) {
            toast(R.string.calibration_loading)
            return
        }

        val step = currentStep
        // 旧版本的扩展配置也一并清掉，避免迁移值再次出现。
        if (step == Step.CRAFT_POINT) {
            prefs.edit().remove(KEY_CRAFT_POINT).apply()
        }

        val updated = when (step) {
            Step.VIEWPORT -> current.copy(
                itemList = current.itemList.copy(viewport = null),
            )

            Step.SCROLL_FROM -> current.copy(
                itemList = current.itemList.copy(scrollFrom = null),
            )

            Step.SCROLL_TO -> current.copy(
                itemList = current.itemList.copy(scrollTo = null),
            )

            Step.COLLECT_POINT -> current.copy(
                dashboard = current.dashboard.copy(collectFixedPoint = null),
            )

            Step.CRAFT_POINT -> current.copy(
                craft = current.craft.copy(startPoint = null),
            )
        }

        lifecycleScope.launch {
            binding.btnSave.isEnabled = false
            runCatching {
                ServiceLocator.configRepository().saveProfile(updated)
            }.onSuccess {
                profile = updated
                binding.roiEditor.clearSelection()
                binding.tvSelection.text = getString(
                    R.string.calibration_selection_fmt,
                    getString(R.string.calibration_value_none),
                )
                updateCurrentValueText()
                toast(R.string.calibration_cleared)
            }.onFailure { throwable ->
                toast(
                    getString(
                        R.string.calibration_save_failed_fmt,
                        throwable.message ?: throwable.javaClass.simpleName,
                    ),
                )
            }
            binding.btnSave.isEnabled = true
        }
    }

    private fun showHelpDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.calibration_help)
            .setMessage(R.string.calibration_instructions)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun showNoServiceDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.calibration_no_service_title)
            .setMessage(R.string.calibration_no_service_message)
            .setPositiveButton(R.string.calibration_dialog_go) { _, _ ->
                SjzAccessibilityService.openSettings(this)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun loadExtraPoint(): PointI? {
        val raw = prefs.getString(KEY_CRAFT_POINT, null) ?: return null
        val parts = raw.split(',')
        if (parts.size != 2) return null
        val x = parts[0].trim().toIntOrNull() ?: return null
        val y = parts[1].trim().toIntOrNull() ?: return null
        return PointI(x, y)
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val PREFS_NAME = "calibration_extras"
        const val KEY_CRAFT_POINT = "craft_start_point"
    }
}
