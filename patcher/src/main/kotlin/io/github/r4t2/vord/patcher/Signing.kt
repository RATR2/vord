package io.github.r4t2.vord.patcher

import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date

/** The one key every patched APK (base and all splits) is signed with. */
class SigningKey(val privateKey: PrivateKey, val certificate: X509Certificate) {
    companion object {
        private const val ALIAS = "vord"

        /** Loads the PKCS12 keystore at [file], creating a fresh self-signed key if missing. */
        fun loadOrCreate(file: File, password: CharArray): SigningKey {
            val store = KeyStore.getInstance("PKCS12")
            if (file.exists()) {
                file.inputStream().use { store.load(it, password) }
            } else {
                store.load(null, password)
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
                val name = X500Name("CN=Vord")
                val now = System.currentTimeMillis()
                val holder = JcaX509v3CertificateBuilder(
                    name, BigInteger.valueOf(now), Date(now - 86_400_000L),
                    Date(now + 30L * 365 * 86_400_000L), name, pair.public,
                ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
                val cert = JcaX509CertificateConverter().getCertificate(holder)
                store.setKeyEntry(ALIAS, pair.private, password, arrayOf(cert))
                file.parentFile?.mkdirs()
                file.outputStream().use { store.store(it, password) }
            }
            return SigningKey(
                store.getKey(ALIAS, password) as PrivateKey,
                store.getCertificate(ALIAS) as X509Certificate,
            )
        }
    }
}

object Signer {
    /** Signs [input] into [output] with APK signature schemes v2 and v3 (v1 is not needed at minSdk 24+). */
    fun sign(input: File, output: File, key: SigningKey, minSdk: Int = 24) {
        val config = ApkSigner.SignerConfig.Builder("vord", key.privateKey, listOf(key.certificate)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(minSdk)
            .setAlignmentPreserved(true)
            .setLibraryPageAlignmentBytes(16384)
            .build()
            .sign()
    }
}
