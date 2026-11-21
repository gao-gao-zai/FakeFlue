package com.flue.launcher.surface.secret

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 读密码的结果。读不出来既不算验证成功，也不能当作没设置而自动清空（R12）。 */
sealed interface SecretRead {
    data object NotSet : SecretRead
    data class Available(val digits: List<Int>) : SecretRead
    data object Unreadable : SecretRead
}

/** 加解密实现；正式用 Android Keystore，单元测试换成假实现。 */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * 隐藏密码的存储（R12、R13）。只存一份密文，不存位数等明文；不写日志。
 * 设备级：两种模式共用同一个密码。保存用 commit，写入失败时原密码不变。
 */
class SecretStore(private val prefs: SharedPreferences, private val cipher: SecretCipher) {

    fun read(): SecretRead {
        val stored = runCatching { prefs.getString(KEY_BLOB, null) }.getOrNull() ?: return SecretRead.NotSet
        val digits = runCatching {
            SecretRules.decode(String(cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)), Charsets.US_ASCII))
        }.getOrNull()
        return if (digits == null) SecretRead.Unreadable else SecretRead.Available(digits)
    }

    val isSet: Boolean get() = read() is SecretRead.Available

    fun save(digits: List<Int>): Boolean {
        if (!SecretRules.isValid(digits)) return false
        return runCatching {
            val blob = cipher.encrypt(SecretRules.encode(digits).toByteArray(Charsets.US_ASCII))
            // 先确认能解回同样的内容再落盘，避免存进一份以后读不出的密文。
            check(SecretRules.decode(String(cipher.decrypt(blob), Charsets.US_ASCII)) == digits)
            prefs.edit().putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
        }.getOrDefault(false)
    }

    companion object {
        const val FILE_NAME = "fflue_secret"
        private const val KEY_BLOB = "blob"

        fun create(context: Context): SecretStore = SecretStore(
            context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
            KeystoreSecretCipher(),
        )
    }
}

/** AES-GCM，密钥只在 Android Keystore 里，不导出。密文格式：12 字节 IV + 密文。 */
class KeystoreSecretCipher(private val alias: String = "fflue_secret_key") : SecretCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_SIZE))
        return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
