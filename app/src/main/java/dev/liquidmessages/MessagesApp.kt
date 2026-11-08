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
        Notifier.channels(this)
    }
}
