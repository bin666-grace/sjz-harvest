package com.sjz.harvest.input

import com.sjz.harvest.core.model.PointI

interface InputController {
    suspend fun tap(point: PointI)

    suspend fun swipe(from: PointI, to: PointI, durationMs: Long)

    suspend fun back()

    fun isAvailable(): Boolean

    fun backendName(): String

    fun close()
}
