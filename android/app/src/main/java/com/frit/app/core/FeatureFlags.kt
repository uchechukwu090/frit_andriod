package com.frit.app.core

data class FeatureFlags(
    val voiceEnabled: Boolean = true,
    val wakeEnabled: Boolean = true,
    val marketEnabled: Boolean = true,
    val memoryEnabled: Boolean = true,
    val visionEnabled: Boolean = true,
    val automationEnabled: Boolean = true
)