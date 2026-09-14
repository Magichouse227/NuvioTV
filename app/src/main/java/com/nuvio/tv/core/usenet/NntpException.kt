package com.nuvio.tv.core.usenet

class NntpException(
    message: String,
    cause: Throwable? = null,
    val rateLimit: NntpRateLimit? = null
) : Exception(message, cause)
