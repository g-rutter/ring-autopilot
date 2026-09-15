package com.ringautopilot.app.ring

import com.ringautopilot.app.model.RingMode
import kotlinx.coroutines.flow.StateFlow

interface RingService {
    val mode: StateFlow<RingMode>

    suspend fun refreshMode(): Result<RingMode>
    suspend fun setMode(mode: RingMode): Result<Unit>
}
