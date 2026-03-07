package com.androbuttons

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 35])
class MainActivityTest {

    @Test
    fun applicationContext_isNotNull() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNotNull(app)
    }
}
