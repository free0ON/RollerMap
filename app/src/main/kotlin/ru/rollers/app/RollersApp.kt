package ru.rollers.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.osmdroid.config.Configuration
import ru.rollers.app.data.Api
import ru.rollers.app.data.Session

class RollersApp : Application() {
    lateinit var session: Session; private set
    lateinit var api: Api; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // osmdroid: User-Agent обязателен по правилам тайловых серверов OSM
        Configuration.getInstance().apply {
            userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = cacheDir.resolve("osmdroid")
            osmdroidTileCache = cacheDir.resolve("osmdroid/tiles")
        }
        session = Session(this)
        api = Api(session)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(RIDE_CHANNEL, getString(R.string.ride_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val RIDE_CHANNEL = "ride"
        lateinit var instance: RollersApp; private set
    }
}
