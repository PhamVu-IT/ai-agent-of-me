package vn.phamvu.trolyai.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Mật khẩu ngẫu nhiên 32 byte để mã hoá cơ sở dữ liệu.
 * Mật khẩu được khoá lại bằng một khoá AES nằm trong chip bảo mật của máy (Android Keystore),
 * khoá này không bao giờ rời khỏi chip, nên chép file đi máy khác cũng không giải mã được.
 */
object DbKey {
    private const val ALIAS = "trolyai_db_key"
    private const val PREFS = "secure"

    fun passphrase(ctx: Context): ByteArray {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("wrapped", null)
        if (stored != null) {
            val (iv, data) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
            return c.doFinal(data)
        }
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val enc = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(pass), Base64.NO_WRAP)
        prefs.edit().putString("wrapped", enc).apply()
        return pass
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }
}
