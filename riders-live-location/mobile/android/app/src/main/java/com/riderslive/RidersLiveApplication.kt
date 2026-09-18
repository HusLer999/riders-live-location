package com.riderslive

import android.app.Application
import com.riderslive.security.CryptoManager

class RidersLiveApplication : Application() {
    lateinit var cryptoManager: CryptoManager
        private set

    override fun onCreate() {
        super.onCreate()
        cryptoManager = CryptoManager(this)
    }
}
