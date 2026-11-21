package com.flue.launcher.surface.lock

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.flue.launcher.surface.secret.KeystoreSecretCipher
import com.flue.launcher.surface.secret.SecretCipher

sealed interface PinRead {
    data object NotSet : PinRead
    data class Available(val digits: List<Int>) : PinRead

    /** 有密文但解不开：仍然上锁，只能用管理密码恢复（R33）。 */
    data object Unreadable : PinRead
}

/** 桌面密码的密文和错误计数，独立文件、独立 Keystore 密钥（R33）。 */
class DesktopLockStore(private val prefs: SharedPreferences, private val cipher: SecretCipher) {

    fun read(): PinRead {
        val stored = runCatching { prefs.getString(KEY_BLOB, null) }.getOrNull() ?: return PinRead.NotSet
        val digits = runCatching {
            DesktopPinRules.decode(String(cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)), Charsets.US_ASCII))
        }.getOrNull()
        return if (digits == null) PinRead.Unreadable else PinRead.Available(digits)
    }

    val enabled: Boolean get() = read() != PinRead.NotSet

    /** 先确认能解回同样内容再落盘；失败时原密码不变。成功会清掉错误计数。 */
    fun save(digits: List<Int>): Boolean {
        if (!DesktopPinRules.isValid(digits)) return false
        return runCatching {
            val blob = cipher.encrypt(DesktopPinRules.encode(digits).toByteArray(Charsets.US_ASCII))
            check(DesktopPinRules.decode(String(cipher.decrypt(blob), Charsets.US_ASCII)) == digits)
            prefs.edit()
                .putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP))
                .remove(KEY_FAILS)
                .remove(KEY_UNTIL)
                .commit()
        }.getOrDefault(false)
    }

    fun disable(): Boolean = prefs.edit().clear().commit()

    var lockout: LockoutState
        get() = LockoutState(prefs.getInt(KEY_FAILS, 0), prefs.getLong(KEY_UNTIL, 0))
        set(value) {
            prefs.edit().putInt(KEY_FAILS, value.fails).putLong(KEY_UNTIL, value.untilElapsed).commit()
        }

    companion object {
        const val FILE_NAME = "fflue_desktop_lock"
        private const val KEY_BLOB = "blob"
        private const val KEY_FAILS = "fails"
        private const val KEY_UNTIL = "until_elapsed"

        fun create(context: Context): DesktopLockStore = DesktopLockStore(
            context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
            KeystoreSecretCipher("fflue_desktop_lock_key"),
        )
    }
}
