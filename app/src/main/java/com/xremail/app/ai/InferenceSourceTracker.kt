package com.xremail.app.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether AI inference is running on-device (Gemini Nano) or in
 * the cloud. Exposed as a StateFlow so UI can show an indicator badge.
 */
class InferenceSourceTracker {

    data class Stats(
        val onDeviceCount: Int = 0,
        val cloudCount: Int = 0,
        val lastSource: EmailClassifier.InferenceSource = EmailClassifier.InferenceSource.MOCK,
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    fun recordInference(source: EmailClassifier.InferenceSource) {
        _stats.value = _stats.value.let { s ->
            when (source) {
                EmailClassifier.InferenceSource.ON_DEVICE ->
                    s.copy(onDeviceCount = s.onDeviceCount + 1, lastSource = source)
                EmailClassifier.InferenceSource.CLOUD ->
                    s.copy(cloudCount = s.cloudCount + 1, lastSource = source)
                EmailClassifier.InferenceSource.MOCK ->
                    s.copy(lastSource = source)
            }
        }
    }
}
