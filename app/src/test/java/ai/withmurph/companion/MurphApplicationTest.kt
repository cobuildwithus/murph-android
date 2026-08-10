package ai.withmurph.companion

import android.app.Application
import androidx.work.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MurphApplicationTest {
    @Test
    fun coldComponentStartupDoesNotConstructTheAppGraph() {
        val onCreate = MurphApplication::class.java.getMethod("onCreate")

        assertEquals(Application::class.java, onCreate.declaringClass)
        assertTrue(Configuration.Provider::class.java.isAssignableFrom(MurphApplication::class.java))
    }
}
