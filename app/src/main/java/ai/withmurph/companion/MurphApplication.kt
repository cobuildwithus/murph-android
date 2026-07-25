package ai.withmurph.companion

import android.app.Application
import ai.withmurph.companion.app.AppGraph

class MurphApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.create(this)
    }
}
