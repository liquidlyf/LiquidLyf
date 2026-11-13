package io.github.liquidlyf.core.util

import java.time.Instant

/** The current time, injected so tests can fix it. */
fun interface Clock {
    fun now(): Instant

    companion object {
        val SYSTEM: Clock = Clock { Instant.now() }
    }
}
