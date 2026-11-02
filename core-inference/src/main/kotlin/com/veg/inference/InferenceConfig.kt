package com.veg.inference

import com.veg.device.RuntimeParams

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
