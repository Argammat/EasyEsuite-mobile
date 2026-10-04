package com.easyesuite.app

import android.app.Application
import com.easyesuite.app.di.AppContainer

class EasyEsuiteApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
