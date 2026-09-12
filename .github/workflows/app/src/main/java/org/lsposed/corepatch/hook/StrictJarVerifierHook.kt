package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.ZygiskHelper.hookAfter
import org.lsposed.corepatch.ZygiskHelper.hookBefore
import org.lsposed.corepatch.ZygiskHelper.hostClassLoader

object StrictJarVerifierHook : BaseHook() {
    override val name = "StrictJarVerifierHook"

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    override fun hook() {
        val strictJarVerifierClazz = hostClassLoader.loadClass("android.util.jar.StrictJarVerifier")

        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/util/jar/StrictJarVerifier.java;l=529
        // private static boolean verifyMessageDigest(byte[] expected, byte[] encodedActual)
        val verifyMessageDigestMethod =
            strictJarVerifierClazz.declaredMethods.first { m -> m.name == "verifyMessageDigest" && m.returnType == Boolean::class.java }
        hookBefore(verifyMessageDigestMethod) { callback ->
            if (Config.isBypassVerificationEnabled()) {
                callback.returnAndSkip(true)
            }
        }

        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/util/jar/StrictJarVerifier.java;l=502
        // private boolean verify(
        //     Attributes attributes,
        //     String entry,
        //     byte[] data,
        //     int start,
        //     int end,
        //     boolean ignoreSecondEndline,
        //     boolean ignorable)
        val verifyMethod =
            strictJarVerifierClazz.declaredMethods.first { m -> m.name == "verify" && m.returnType == Boolean::class.java }
        // The original LSPosed implementation patched the constructor field after construction.
        // LSPlant's public Java-hook API is method-oriented, so we apply the same state change
        // at the first verification entry point instead of relying on constructor interception.
        val rollbackField =
            strictJarVerifierClazz.declaredFields.first { it.name == "signatureSchemeRollbackProtectionsEnforced" }
        rollbackField.isAccessible = true
        hookBefore(verifyMethod) { callback ->
            if (Config.isBypassVerificationEnabled()) {
                rollbackField.setBoolean(callback.thisObject, false)
                callback.returnAndSkip(true)
            }
        }

        val pkcs7Clazz = hostClassLoader.loadClass("sun.security.pkcs.PKCS7")
        val pkcs7Constructor = pkcs7Clazz.declaredConstructors.first { c ->
            c.parameterTypes.size == 1 && c.parameterTypes[0] == ByteArray::class.java
        }
        val getSignerInfosMethod = pkcs7Clazz.getDeclaredMethod("getSignerInfos")
        val signerInfoClazz = hostClassLoader.loadClass("sun.security.pkcs.SignerInfo")
        val getCertificateChainMethod =
            signerInfoClazz.getDeclaredMethod("getCertificateChain", pkcs7Clazz)

        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/util/jar/StrictJarVerifier.java;l=324
        // static Certificate[] verifyBytes(byte[] blockBytes, byte[] sfBytes)
        val verifyBytesMethod = strictJarVerifierClazz.getDeclaredMethod(
            "verifyBytes", ByteArray::class.java, ByteArray::class.java
        )
        hookAfter(verifyBytesMethod) { callback ->
            if (Config.isBypassDigestEnabled() && !Config.isUsePreviousSignaturesEnabled()) {
                val block = pkcs7Constructor.newInstance(callback.args[0])
                val signerInfo = getSignerInfosMethod.invoke(block) as Array<*>
                if (signerInfo.isEmpty()) return@hookAfter
                val signer = signerInfo[0]
                val certs = getCertificateChainMethod.invoke(signer, block)
                callback.result = certs
                callback.throwable = null
            }
        }
    }
}
