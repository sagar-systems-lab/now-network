package com.sagarsystemslab.nownetwork.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

interface SecureSecretStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
}

@Singleton
class AndroidKeystoreSecretStore @Inject constructor(
    @ApplicationContext context: Context,
) : SecureSecretStore {
    private val preferences = context.getSharedPreferences(
        "now_secure_secrets_v1",
        Context.MODE_PRIVATE,
    )

    override fun read(key: String): String? {
        val encoded = preferences.getString(key, null) ?: return null

        return runCatching {
            val parts = encoded.split(':', limit = 3)
            require(parts.size == 3 && parts[0] == ENVELOPE_VERSION)
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.doFinal(ciphertext).decodeToString()
        }.getOrElse {
            preferences.edit().remove(key).apply()
            null
        }
    }

    override fun write(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())

        val encoded = buildString {
            append(ENVELOPE_VERSION)
            append(':')
            append(Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            append(':')
            append(
                Base64.encodeToString(
                    cipher.doFinal(value.encodeToByteArray()),
                    Base64.NO_WRAP,
                ),
            )
        }

        preferences.edit().putString(key, encoded).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "now.local.secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val ENVELOPE_VERSION = "v1"
    }
}
