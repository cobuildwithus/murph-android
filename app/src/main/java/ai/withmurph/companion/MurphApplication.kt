package ai.withmurph.companion

import android.app.Application
import ai.withmurph.companion.app.AppGraph

class MurphApplication : Application() {
    val graph: AppGraph by lazy(LazyThreadSafetyMode.NONE) {
        AppGraph.create(this)
    }
}
