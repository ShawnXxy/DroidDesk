package com.orailnoor.droiddesk.display

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisplayRepositoryApi29CompatibilityTest {
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q, maxSdkVersion = Build.VERSION_CODES.Q)
    fun snapshotDoesNotResolveApi31DisplayProductInfo() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val displays = DisplayRepository(context).snapshot()

        assertTrue(displays.any(DesktopDisplay::isDefault))
    }
}
