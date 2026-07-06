import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Localhost decrypt server. Holds the RSA PRIVATE key (the only thing that can unlock).
 * Reads a pulled .elog file, unwraps each session key, decrypts every line, returns plaintext.
 *
 * Binds to 127.0.0.1 ONLY: the response is decrypted secrets and must never leave the box.
 *
 * Build & run:
 *   kotlinc DecryptServer.kt -include-runtime -d server.jar
 *   java -jar server.jar private_key.pem 8734
 *   curl "http://127.0.0.1:8734/logs?file=$PWD/session-XXXX.elog"
 */

private const val HEADER_PREFIX = "ELOGv1|"
private val dec = Base64.getDecoder()

// Must match the writer exactly (SHA-256 + MGF1-SHA-256), or RSA unwrap fails.
private val OAEP = OAEPParameterSpec(
    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
)

private fun loadPrivateKey(pem: String): PrivateKey {
    val b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("\\s".toRegex(), "")
    val der = dec.decode(b64)
    return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
}

private fun decryptFile(file: File, privateKey: PrivateKey): String {
    val out = StringBuilder()
    var currentKey: SecretKeySpec? = null

    file.forEachLine { line ->
        if (line.isEmpty()) return@forEachLine

        if (line.startsWith(HEADER_PREFIX)) {
            // New session block: unwrap its AES key with the RSA private key.
            val wrapped = dec.decode(line.removePrefix(HEADER_PREFIX))
            val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            rsa.init(Cipher.DECRYPT_MODE, privateKey, OAEP)
            currentKey = SecretKeySpec(rsa.doFinal(wrapped), "AES")
        } else {
            val key = currentKey ?: return@forEachLine // data before any header -> skip
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEachLine
            try {
                val nonce = dec.decode(line.substring(0, idx))
                val ct = dec.decode(line.substring(idx + 1))
                val gcm = Cipher.getInstance("AES/GCM/NoPadding")
                gcm.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
                out.append(String(gcm.doFinal(ct), StandardCharsets.UTF_8)).append('\n')
            } catch (e: Exception) {
                // GCM authentication also fails here if a line was tampered with or truncated.
                out.append("[!! undecryptable line skipped: ${e.javaClass.simpleName} ]\n")
            }
        }
    }
    return out.toString()
}

fun main(args: Array<String>) {
    val privateKey = loadPrivateKey(File(args.getOrElse(0) { "private_key.pem" }).readText())
    val port = args.getOrElse(1) { "8734" }.toInt()

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0) // loopback only

    server.createContext("/logs") { ex: HttpExchange ->
        try {
            val path = ex.requestURI.query
                ?.split("&")
                ?.firstOrNull { it.startsWith("file=") }
                ?.substringAfter("file=")
                ?.let { URLDecoder.decode(it, "UTF-8") }

            when {
                path == null -> respond(ex, 400, "usage: /logs?file=/absolute/path/session.elog")
                !File(path).isFile -> respond(ex, 404, "not found: $path")
                else -> respond(ex, 200, decryptFile(File(path), privateKey))
            }
        } catch (e: Exception) {
            respond(ex, 500, "error: ${e.message}")
        }
    }

    server.executor = null
    server.start()
    println("Decrypt server on http://127.0.0.1:$port  (loopback only)")
    println("Try: curl \"http://127.0.0.1:$port/logs?file=\$PWD/session-XXXX.elog\"")
}

private fun respond(ex: HttpExchange, code: Int, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}
