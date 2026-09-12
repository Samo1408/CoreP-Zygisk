package org.lsposed.corepatch.hook

import org.lsposed.corepatch.ZygiskHelper

open class BaseHook {
    open val name = "BaseHook"

    private var inited = false

    open fun hook() {

    }

    private fun hookInternal() {
        try {
            hook()
        } catch (t: Throwable) {
            ZygiskHelper.log("[$name] hook failed", t)
        }
    }

    fun init() {
        if (inited) return
        inited = true
        ZygiskHelper.log("[$name] init: $name")
        hookInternal()
        ZygiskHelper.log("[$name] init: $name done")
    }
}
