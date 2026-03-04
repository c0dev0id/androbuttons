package com.androbuttons

import android.service.notification.NotificationListenerService

/**
 * Stub NotificationListenerService whose sole role is to provide a valid
 * ComponentName to MediaSessionManager.getActiveSessions(), allowing
 * OverlayService to enumerate active media sessions without holding a
 * full notification-listener implementation.
 */
class MediaListenerService : NotificationListenerService()
