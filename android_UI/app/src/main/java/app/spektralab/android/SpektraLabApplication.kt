package app.spektralab.android

import android.app.Application
import app.spektralab.android.i18n.Localization
import app.spektralab.android.model.AppSettings
import app.spektralab.android.model.StockCatalog

class SpektraLabApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Localization.init(this)
        AppSettings.init(this)
        StockCatalog.load(this)
    }
}
