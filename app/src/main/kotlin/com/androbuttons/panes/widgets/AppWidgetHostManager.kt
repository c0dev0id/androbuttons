package com.androbuttons.panes.widgets

import android.appwidget.AppWidgetHost
import android.content.Context

/**
 * Singleton that owns the single [AppWidgetHost] for the app.
 * Both [OverlayService] and [WidgetPickerActivity] live in the same process
 * and share this host so that widget views created in the service keep
 * receiving RemoteViews updates even after the picker activity finishes.
 */
object AppWidgetHostManager {

    private const val HOST_ID = 1024
    private var host: AppWidgetHost? = null

    fun getHost(context: Context): AppWidgetHost {
        if (host == null) host = AppWidgetHost(context.applicationContext, HOST_ID)
        return host!!
    }
}
