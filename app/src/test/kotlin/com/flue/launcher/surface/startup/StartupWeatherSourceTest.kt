package com.flue.launcher.surface.startup

import android.app.Application
import com.flue.launcher.FlueApplication
import com.flue.launcher.watchface.standard.AndroidWatchDataProvider
import com.flue.launcher.watchface.standard.WatchWeatherHttpTransport
import com.flue.launcher.watchface.standard.WatchWeatherLocation
import com.flue.launcher.watchface.standard.WatchWeatherRepository
import com.flue.launcher.watchface.standard.WatchWeatherSources
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class StartupWeatherSourceTest {
    @Test
    fun backgroundWeatherInitializationPreservesDefaultProviderLocationTags() {
        val context = RuntimeEnvironment.getApplication()
        val repository = WatchWeatherRepository(
            context,
            transport = WatchWeatherHttpTransport { _, _ -> null },
        )
        val location = WatchWeatherLocation(31.2304, 121.4737, "上海", elevation = 12.0)
        repository.setManualLocation(location)
        val defaultInstance = WatchWeatherRepository::class.java.getDeclaredField("defaultInstance")
            .apply { isAccessible = true }
        val previousDefault = defaultInstance.get(null)
        val previousSource = WatchWeatherSources.applicationSource
        val app = FlueApplication()
        Shadows.shadowOf(app).callAttach(context)
        try {
            defaultInstance.set(null, repository)
            WatchWeatherSources.applicationSource = null
            app.onCreate()
            runBlocking {
                withTimeout(10_000L) {
                    app.repositories.applicationScope.coroutineContext[Job]!!.children.toList().joinAll()
                }
            }

            val snapshot = AndroidWatchDataProvider(context).snapshot(System.currentTimeMillis())

            assertEquals(location.latitude, snapshot.latitude ?: Double.NaN, 0.0)
            assertEquals(location.longitude, snapshot.longitude ?: Double.NaN, 0.0)
            assertEquals(location.elevation!!, snapshot.altitudeMeters ?: Double.NaN, 0.0)
        } finally {
            try {
                runBlocking {
                    withTimeout(10_000L) {
                        app.repositories.applicationScope.coroutineContext[Job]!!.cancelAndJoin()
                    }
                }
            } finally {
                defaultInstance.set(null, previousDefault)
                WatchWeatherSources.applicationSource = previousSource
            }
        }
    }
}
