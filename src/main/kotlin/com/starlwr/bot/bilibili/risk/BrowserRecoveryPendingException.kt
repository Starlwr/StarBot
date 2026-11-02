package com.starlwr.bot.bilibili.risk

/** Local waiting state, never a newly observed server -352 or a retryable HTTP failure. */
class BrowserRecoveryPendingException(message: String) : IllegalStateException(message)
