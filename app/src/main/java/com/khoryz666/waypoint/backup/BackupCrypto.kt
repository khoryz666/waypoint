package com.khoryz666.waypoint.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPassphrase : Exception("Wrong passphrase or damaged backup")
class NotABackup : Exception("Not a Waypoint backup")

/** A passphrase-derived key and the salt it came from; cached so routine backups need no passphrase. */
class BackupKey(val salt: ByteArray, val key: ByteArray) {
    fun encoded(): ByteArray = salt + key

    companion object {
        fun decode(bytes: ByteArray) = BackupKey(bytes.copyOfRange(0, SALT_BYTES), bytes.copyOfRange(SALT_BYTES, bytes.size))
    }
}

const val SALT_BYTES = 16
private const val IV_BYTES = 12
private val MAGIC = "WPB1".toByteArray()

/**
 * The backup file format (spec 9.2): gzip, then AES-256-GCM with a PBKDF2-HMAC-SHA256 key
 * (600,000 iterations, random 16-byte salt). File = "WPB1" | salt | iv | ciphertext+tag.
 */
object BackupCrypto {
    const val ITERATIONS = 600_000
    private val random = SecureRandom()

    fun deriveKey(passphrase: CharArray, salt: ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes), iterations: Int = ITERATIONS): BackupKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return BackupKey(salt, SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
    }

    fun seal(plain: ByteArray, key: BackupKey): ByteArray {
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain) } }.toByteArray()
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.key, "AES"), GCMParameterSpec(128, iv)) }
        return MAGIC + key.salt + iv + cipher.doFinal(gz)
    }

    fun salt(file: ByteArray): ByteArray {
        if (file.size < MAGIC.size + SALT_BYTES + IV_BYTES || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw NotABackup()
        return file.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
    }

    /** Decrypts with a key derived from the file's own salt. */
    fun open(file: ByteArray, passphrase: CharArray, iterations: Int = ITERATIONS): ByteArray = open(file, deriveKey(passphrase, salt(file), iterations))

    fun open(file: ByteArray, key: BackupKey): ByteArray {
        val salt = salt(file)
        if (!salt.contentEquals(key.salt)) throw WrongPassphrase()
        val ivStart = MAGIC.size + SALT_BYTES
        val iv = file.copyOfRange(ivStart, ivStart + IV_BYTES)
        val gz = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key.key, "AES"), GCMParameterSpec(128, iv)) }
                .doFinal(file, ivStart + IV_BYTES, file.size - ivStart - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            throw WrongPassphrase()
        }
        return GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
    }
}
