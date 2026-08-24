package de.lobianco.saftssh.linux

import android.app.Application
import de.lobianco.saftssh.linux.data.logging.LogFileManager

/** Only job: start log capture as early as possible, before anything else in the plugin runs. */
class LinuxPluginApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LogFileManager.init(this)
    }
}
