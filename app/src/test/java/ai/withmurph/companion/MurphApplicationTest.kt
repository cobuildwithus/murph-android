package ai.withmurph.companion

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test

class MurphApplicationTest {
    @Test
    fun coldComponentStartupDoesNotConstructTheAppGraph() {
        val onCreate = MurphApplication::class.java.getMethod("onCreate")

        assertEquals(Application::class.java, onCreate.declaringClass)
    }
}
