package io.nekohasekai.sagernet.utils

import android.content.Context

internal object N {

    init {
        System.loadLibrary("v")
    }

    external fun a(context: Context)

    private external fun b(
        context: Context,
        signerDigest: ByteArray,
        textDigest: ByteArray,
        nonce: Long,
    ): Long

    fun c(
        context: Context,
        signerDigest: ByteArray,
        textDigest: ByteArray,
        nonce: Long,
    ): Boolean = b(context, signerDigest, textDigest, nonce) ==
        proof(signerDigest, textDigest, nonce)

    private fun proof(signerDigest: ByteArray, textDigest: ByteArray, nonce: Long): Long {
        var state = nonce xor -7046029254386353131L
        signerDigest.forEach { value ->
            state = java.lang.Long.rotateLeft(state xor (value.toLong() and 0xffL), 11) *
                1099511628211L
            state = state xor (state ushr 29)
        }
        textDigest.forEach { value ->
            state = java.lang.Long.rotateLeft(state xor (value.toLong() and 0xffL), 7) *
                1099511628211L
            state = state xor (state ushr 31)
        }
        return state xor -2960836687051489901L
    }
}
