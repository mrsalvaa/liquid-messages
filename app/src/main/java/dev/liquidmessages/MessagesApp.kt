// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages

import android.app.Application
import dev.liquidmessages.data.Repo
import dev.liquidmessages.data.Stores
import dev.liquidmessages.notify.Notifier
import dev.liquidmessages.util.Strings

class MessagesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.app = this
        Stores.init(this)
        Repo.init(this)
        // Quien tenía elegido un ícono que ya no existe pasa al primero.
        if (dev.liquidmessages.util.AppIcons.migrate(this, Stores.settings.value.appIcon)) Stores.settings.update { it.copy(appIcon = 0) }
        Notifier.channels(this)
        // Las alarmas de los programados se pierden si se fuerza el cierre de la app: se vuelven a poner.
        dev.liquidmessages.schedule.Scheduler.rearmAll(this)
    }
}
