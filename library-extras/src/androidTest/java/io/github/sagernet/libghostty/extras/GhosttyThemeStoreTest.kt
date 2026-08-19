package io.github.sagernet.libghostty.extras

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GhosttyThemeStoreTest {

    @Test
    fun loadThemesFromAssets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("no dark themes", GhosttyThemeStore.listThemes(context, isDark = true).isNotEmpty())
        assertTrue("no light themes", GhosttyThemeStore.listThemes(context, isDark = false).isNotEmpty())
        val theme = GhosttyThemeStore.loadTheme(context, GhosttyThemeStore.DEFAULT_DARK_THEME)
        assertNotNull("default dark theme failed to load", theme)
        assertNotNull("default dark theme has no background", theme!!.background)
    }
}
