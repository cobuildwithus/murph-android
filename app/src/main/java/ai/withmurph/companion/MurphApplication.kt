package ai.withmurph.companion

import android.app.Application
import androidx.work.Configuration
import ai.withmurph.companion.app.AppGraph
import ai.withmurph.companion.health.MurphHealthWorkerFactory

class MurphApplication : Application(), Configuration.Provider {
    val graph: AppGraph by lazy(LazyThreadSafetyMode.NONE) {
        AppGraph.create(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(MurphHealthWorkerFactory())
            .build()
}
