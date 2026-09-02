package com.ecommerce.identity.security

import de.mkammerer.argon2.Argon2Factory
import de.mkammerer.argon2.Argon2Factory.Argon2Types

class PasswordHasher {
    private val argon2 = Argon2Factory.create(Argon2Types.ARGON2id, 16, 32)

    fun hash(password: String): String {
        val chars = password.toCharArray()
        return try {
            argon2.hash(3, 64 * 1024, 1, chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    fun verify(encodedHash: String, password: String): Boolean {
        val chars = password.toCharArray()
        return try {
            argon2.verify(encodedHash, chars)
        } finally {
            chars.fill('\u0000')
        }
    }
}
