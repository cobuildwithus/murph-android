package ai.withmurph.companion

import android.app.Application
import androidx.work.Configuration
import ai.withmurph.companion.app.AppGraph
import ai.withmurph.companion.health.MurphHealthWorkerFactory

class MurphApplication : Application(), Configuration.Provider {
    lateinit var graph: AppGraph
        private set

    override fun getWorkManagerConfiguration(): Configuration =
        Configuration.Builder()
            .setWorkerFactory(MurphHealthWorkerFactory())
            .build()

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.create(this)
    }
}
