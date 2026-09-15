package com.ringautopilot.app.ring

import com.ringautopilot.app.model.RingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UnconfiguredRingService : RingService {
    private val mutableMode = MutableStateFlow(RingMode.UNAVAILABLE)

    override val mode: StateFlow<RingMode> = mutableMode.asStateFlow()

    override suspend fun refreshMode(): Result<RingMode> =
        Result.failure(NotConfiguredException())

    override suspend fun setMode(mode: RingMode): Result<Unit> =
        Result.failure(NotConfiguredException())
}

class NotConfiguredException : IllegalStateException("Ring authentication is not configured")
