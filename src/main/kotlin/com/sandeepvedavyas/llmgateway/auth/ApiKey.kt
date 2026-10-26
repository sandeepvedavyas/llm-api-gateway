package com.sandeepvedavyas.llmgateway.auth

data class ApiKey(
    val id: String,
    val secret: String,
    val requestsPerMinute: Int,
)
