import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * Generates the RSA keypair.
 *
 *   public_key.pem  -> bundle in the Android app (it can only LOCK)
 *   private_key.pem -> keep ONLY on the decrypt server (it can UNLOCK)
 *
 * Run:  kotlinc KeyGen.kt -include-runtime -d keygen.jar && java -jar keygen.jar
 */
fun main() {
    val gen = KeyPairGenerator.getInstance("RSA")
    gen.initialize(3072) // 2048 is fine too; the wrap op happens once per file, so speed is irrelevant here
    val pair = gen.generateKeyPair()

    val enc = Base64.getEncoder()
    fun pem(type: String, bytes: ByteArray): String {
        val body = enc.encodeToString(bytes).chunked(64).joinToString("\n")
        return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
    }

    File("public_key.pem").writeText(pem("PUBLIC KEY", pair.public.encoded))    // X.509 SubjectPublicKeyInfo
    File("private_key.pem").writeText(pem("PRIVATE KEY", pair.private.encoded)) // PKCS#8

    println("Wrote public_key.pem  -> put in the Android app's assets/")
    println("Wrote private_key.pem -> keep ONLY on the decrypt server. Never ship it to the device.")
}
