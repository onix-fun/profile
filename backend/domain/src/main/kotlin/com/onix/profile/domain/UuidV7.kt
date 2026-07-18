package com.onix.profile.domain

import java.security.SecureRandom
import java.util.UUID

object UuidV7 {
    private val random = SecureRandom()

    fun generate(): UUID {
        val timestamp = System.currentTimeMillis() and 0x0000ffffffffffffL
        val most = (timestamp shl 16) or 0x7000L or (random.nextLong() and 0x0fffL)
        val least = Long.MIN_VALUE or (random.nextLong() and 0x3fffffffffffffffL)
        return UUID(most, least)
    }
}
