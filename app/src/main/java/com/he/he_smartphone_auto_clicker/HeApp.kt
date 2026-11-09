package com.he.he_smartphone_auto_clicker

import android.app.Application
import com.he.he_smartphone_auto_clicker.i18n.I18n

class HeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
    }
}
