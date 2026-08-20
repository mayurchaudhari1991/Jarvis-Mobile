package com.jarvis.inference

import com.jarvis.device.RuntimeParams

data class InferenceConfig(
    val threadCount: Int,
    val contextTokens: Int,
    val batchSize: Int
)

fun RuntimeParams.toInferenceConfig() = InferenceConfig(
    threadCount = threadCount,
    contextTokens = contextTokens,
    batchSize = batchSize
)
