package com.workout2scroll.ml

import java.io.InputStream
import java.security.MessageDigest

object VideoFingerprint {
    fun calculate(input: InputStream, checkActive: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}