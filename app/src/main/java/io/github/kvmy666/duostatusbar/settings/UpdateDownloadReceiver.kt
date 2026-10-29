package io.github.kvmy666.duostatusbar.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.kvmy666.duostatusbar.L

/**
 * The notification's Download action lands here and enqueues the foreground download worker.
 * A `PendingIntent` cannot start a worker directly, so a tiny receiver is the bridge. It is not exported
 * and only the app's own `PendingIntent` targets it.
 */
class UpdateDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            UpdateDownloadWorker.enqueue(
                context.applicationContext,
                intent.getStringExtra(UpdateDownloadWorker.KEY_VERSION).orEmpty(),
                intent.getStringExtra(UpdateDownloadWorker.KEY_APK_URL).orEmpty(),
                intent.getStringExtra(UpdateDownloadWorker.KEY_SHA).orEmpty()
            )
        } catch (t: Throwable) {
            L.w("download receiver: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    companion object {
        const val ACTION = "io.github.gengo0202.vivoduobar.DOWNLOAD_UPDATE"
    }
}
