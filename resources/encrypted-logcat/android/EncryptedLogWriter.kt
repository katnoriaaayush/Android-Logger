import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Encrypts log lines and appends them to a file in internal storage.
 *
 * How it works:
 *  - On construction it invents a random AES-256 "session key" (the fast padlock),
 *    wraps it ONCE with the RSA public key, and writes that as a header line.
 *  - Every log line is then encrypted with AES-256-GCM (hardware-accelerated),
 *    so hundreds of lines/second is trivial.
 *  - The device only ever holds the public key, so it cannot read the file back.
 *
 * File format (newline-delimited, all fields Base64):
 *   ELOGv1|<wrappedSessionKey>          <- header, one per session
 *   <nonce>:<ciphertext+tag>            <- one per log line
 *   <nonce>:<ciphertext+tag>
 *   ...
 * On app restart a new instance appends a NEW header block (its own fresh key),
 * so a single file can contain several session blocks. The server handles that.
 */
class EncryptedLogWriter(
    publicKey: PublicKey,
    outputFile: File
) : AutoCloseable {

    companion object {
        private const val HEADER_PREFIX = "ELOGv1|"
        private const val GCM_TAG_BITS = 128

        // Explicit OAEP spec so Android (Conscrypt) and the JVM server agree on the
        // MGF1 hash. Without this they can silently disagree (SHA-1 vs SHA-256) and
        // decryption fails. The SERVER must use this exact same spec.
        private val OAEP = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
        )

        /** Load an X.509 PEM ("-----BEGIN PUBLIC KEY-----"). */
        fun loadPublicKey(pem: String): PublicKey {
            val b64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
            val der = Base64.getDecoder().decode(b64)
            return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        }
    }

    private val b64 = Base64.getEncoder()
    private val counter = AtomicLong(0)
    private val sessionKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val writer: BufferedWriter

    init {
        outputFile.parentFile?.mkdirs()
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(outputFile, true), Charsets.UTF_8))

        // Wrap the session key once and write this session's header.
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, publicKey, OAEP)
        val wrapped = rsa.doFinal(sessionKey.encoded)
        synchronized(writer) {
            writer.write(HEADER_PREFIX + b64.encodeToString(wrapped))
            writer.newLine()
            writer.flush()
        }
    }

    /** Encrypt one log line and append it. Thread-safe. */
    fun writeLine(line: String) {
        // 96-bit nonce from a monotonic counter. Since the session key is fresh,
        // the counter safely starts at 0 every run -> a (key, nonce) pair can never repeat.
        val nonce = ByteArray(12)
        ByteBuffer.wrap(nonce, 4, 8).putLong(counter.getAndIncrement())

        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ct = gcm.doFinal(line.toByteArray(Charsets.UTF_8))

        val record = b64.encodeToString(nonce) + ":" + b64.encodeToString(ct)
        synchronized(writer) {
            writer.write(record)
            writer.newLine()
            writer.flush() // per-line durability. For max throughput, drop this and flush on a timer/batch.
        }
    }

    override fun close() {
        synchronized(writer) {
            writer.flush()
            writer.close()
        }
    }
}
