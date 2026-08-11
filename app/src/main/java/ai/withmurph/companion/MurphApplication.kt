package ai.withmurph.companion

import android.app.Application
import androidx.work.Configuration
import ai.withmurph.companion.app.AppGraph
import ai.withmurph.companion.health.ForegroundVitalSyncWorkerFactory

class MurphApplication : Application(), Configuration.Provider {
    val graph: AppGraph by lazy(LazyThreadSafetyMode.NONE) {
        AppGraph.create(this)
    }

    override fun getWorkManagerConfiguration(): Configuration = Configuration.Builder()
        .setWorkerFactory(ForegroundVitalSyncWorkerFactory())
        .build()
}
