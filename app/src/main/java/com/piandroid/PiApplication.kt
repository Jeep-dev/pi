package com.piandroid

import android.app.Application

/**
 * Process-level owner for Pi runtimes.
 *
 * Activities are disposable UI surfaces on Android. Keeping the runtime manager
 * here prevents an Activity recreation/background eviction from tearing down
 * event polling and forcing every Pi Session to reconnect.
 */
class PiApplication : Application() {
    internal val runtimeManager: PiSessionRuntimeManager by lazy {
        PiSessionRuntimeManager(applicationContext)
    }
}
