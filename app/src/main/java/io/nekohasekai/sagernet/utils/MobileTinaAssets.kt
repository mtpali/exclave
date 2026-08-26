package io.nekohasekai.sagernet.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import java.lang.ref.WeakReference
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Decrypts the MobileTina state artwork in memory without creating plaintext files. */
object MobileTinaAssets {
    enum class State(val rawId: Int, val sha256: String) {
        WHITE(R.raw.mt_auto_white, "70c061dda74ec72e677c7061e2245b8a36f36b2675647791b53586bc2c32a4fe"),
        YELLOW(R.raw.mt_auto_yellow, "843afec129b2bbf6ed205854a78d41719054bb3905d706c73ba9e96b6451b6e5"),
        BLUE(R.raw.mt_auto_blue, "e13ee902042ab5e560ee71744db016fea2404f9b5d6c4b664cdd05f1187bfc75"),
        RED(R.raw.mt_auto_red, "a24baa9ccb77c06453f9864634974b769879eeb42bb9a87908971a9713e6bf28"),
    }

    private val cache = mutableMapOf<Int, WeakReference<Bitmap>>()

    private fun cipherMaterial(): ByteArray {
        // Derive the scrambling material from integrity metadata already embedded in the app.
        // Nothing here is a credential or security boundary; R8 still obscures the data flow.
        val source = State.values().joinToString("|") { it.sha256.reversed() } + "|mt/assets/v2"
        return MessageDigest.getInstance("SHA-512").digest(source.toByteArray())
    }

    fun apply(imageView: ImageView, state: State) {
        val bitmap = synchronized(cache) { cache[state.rawId]?.get() } ?: decode(state)
        imageView.setImageBitmap(bitmap)
    }

    private fun decode(state: State): Bitmap? = runCatching {
        val encrypted = SagerNet.application.resources.openRawResource(state.rawId).use { it.readBytes() }
        val material = cipherMaterial()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(material.copyOfRange(0, 32), "AES"),
                IvParameterSpec(material.copyOfRange(32, 48)),
            )
        }
        val plain = cipher.doFinal(encrypted)
        val digest = MessageDigest.getInstance("SHA-256").digest(plain)
            .joinToString("") { "%02x".format(it) }
        check(digest == state.sha256) { "MobileTina asset integrity check failed" }
        BitmapFactory.decodeByteArray(plain, 0, plain.size)
            ?.also { synchronized(cache) { cache[state.rawId] = WeakReference(it) } }
            ?: error("Unable to decode MobileTina image")
    }.onFailure(Logs::w).getOrNull()
}
