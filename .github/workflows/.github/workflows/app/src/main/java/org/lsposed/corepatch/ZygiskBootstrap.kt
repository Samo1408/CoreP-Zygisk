package org.lsposed.corepatch

import android.os.Build
import java.lang.reflect.Method
import java.lang.reflect.Executable

/**
 * Bootstrap invoked from the Zygisk native entrypoint before SystemServer starts.
 *
 * We hook ZygoteInit.zygoteInit because its ClassLoader argument is the real
 * SystemServer PathClassLoader. This avoids relying on module-file access after
 * specialization.
 */
object ZygiskBootstrap {
    @JvmStatic
    fun installEarly() {
        try {
            val zygoteInit = Class.forName("com.android.internal.os.ZygoteInit", false, null)
            val candidates = zygoteInit.declaredMethods.filter {
                it.name == "zygoteInit" &&
                    it.parameterTypes.any { p -> ClassLoader::class.java.isAssignableFrom(p) }
            }
            val target = candidates.firstOrNull() ?: return
            target.isAccessible = true

            ZygiskHelper.hookBefore(target) { cb ->
                val loader = cb.args.lastOrNull { it is ClassLoader } as? ClassLoader
                if (loader != null) {
                    installSystemServerHooks(loader)
                }
            }
            ZygiskHelper.log("early bootstrap hook installed on $target")
        } catch (t: Throwable) {
            ZygiskHelper.log("early bootstrap installation failed", t)
        }
    }

    @Synchronized
    private fun installSystemServerHooks(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        ZygiskHelper.setHostClassLoader(classLoader)
        Config.printAllConfig()

        val hooks = listOf(
            ApkSignatureVerifierHook,
            ApkSigningBlockUtilsHook,
            ApplicationInfoHook,
            AssetManagerHook,
            InstallPackageHelperHook,
            KeySetManagerServiceHook,
            MessageDigestHook,
            NtConfigListServiceImplHook,
            PackageManagerServiceHook,
            PackageManagerServiceUtilsHook,
            ReconcilePackageUtilsHook,
            ScanPackageUtilsHook,
            SigningDetailsHook,
            SharedUserSettingHook,
            StrictJarVerifierHook,
            VerificationParamsHook,
            VerifyingSessionHook,
        )
        hooks.forEach {
            try { it.init() } catch (t: Throwable) {
                ZygiskHelper.log("[${it.name}] failed", t)
            }
        }
        ZygiskHelper.log("system_server hooks installed on SDK ${Build.VERSION.SDK_INT}")
    }

    private var installed = false
}
