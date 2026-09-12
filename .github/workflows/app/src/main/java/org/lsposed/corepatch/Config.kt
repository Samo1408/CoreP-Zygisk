package org.lsposed.corepatch


object Config {
    const val BYPASS_DOWNGRADE = "downgrade"
    const val BYPASS_VERIFICATION = "bypass_verification"
    const val BYPASS_RESOURCE_ARSC_RESTRICTIONS = "bypass_resource_arsc_restrictions"
    const val BYPASS_DIGEST = "bypass_digest"
    const val BYPASS_EXACT_SIGNATURE_MATCH = "bypass_exact_sig_match"
    const val USE_PREVIOUS_SIGNATURES = "use_previous_signatures"
    const val ALLOW_HIDDEN_APIS_FOR_SYSTEM_APPS = "allow_hidden_apis_for_system_apps"
    const val BYPASS_SHARED_USER = "bypass_shared_user"
    const val DISABLE_VERIFICATION_AGENT = "disable_verification_agent"
    const val BYPASS_BLOCK = "bypass_block"

    private val allConfig = arrayOf(
        BYPASS_DOWNGRADE,
        BYPASS_VERIFICATION,
        BYPASS_RESOURCE_ARSC_RESTRICTIONS,
        BYPASS_DIGEST,
        BYPASS_EXACT_SIGNATURE_MATCH,
        USE_PREVIOUS_SIGNATURES,
        ALLOW_HIDDEN_APIS_FOR_SYSTEM_APPS,
        BYPASS_SHARED_USER,
        DISABLE_VERIFICATION_AGENT,
        BYPASS_BLOCK
    )

    fun printAllConfig() {
        allConfig.forEach {
            ZygiskHelper.log("$it: ${getConfig(it)}")
        }
    }

    fun isBypassDowngradeEnabled(): Boolean {
        return getConfig(BYPASS_DOWNGRADE)
    }

    fun isBypassVerificationEnabled(): Boolean {
        return getConfig(BYPASS_VERIFICATION)
    }

    fun isBypassResourceArscRestrictionsEnabled(): Boolean {
        return getConfig(BYPASS_RESOURCE_ARSC_RESTRICTIONS)
    }

    fun isBypassDigestEnabled(): Boolean {
        return getConfig(BYPASS_DIGEST)
    }

    fun isBypassExactSignatureMatch(): Boolean {
        return getConfig(BYPASS_EXACT_SIGNATURE_MATCH)
    }

    fun isUsePreviousSignaturesEnabled(): Boolean {
        return getConfig(USE_PREVIOUS_SIGNATURES)
    }

    fun isAllowHiddenApisForSystemAppsEnabled(): Boolean {
        return getConfig(ALLOW_HIDDEN_APIS_FOR_SYSTEM_APPS)
    }

    fun isBypassSharedUserEnabled(): Boolean {
        return getConfig(BYPASS_SHARED_USER)
    }

    fun isDisableVerificationAgentEnabled(): Boolean {
        return getConfig(DISABLE_VERIFICATION_AGENT)
    }

    fun isBypassBlockEnabled(): Boolean {
        return getConfig(BYPASS_BLOCK)
    }

    fun getConfig(key: String): Boolean {
        return ConfigStore.get(key)
    }

    fun setConfig(key: String, value: Boolean) {
        ConfigStore.set(key, value)
    }
}
