package com.sqa.logtest

import android.net.Uri

/**
 * Cross-user URI helper. A singleUser provider call from a background→foreground
 * user is only reliable when the target user id is baked into the URI as the
 * `<userId>@authority` prefix. The framework's ContentProvider.maybeAddUserId
 * does exactly that but is @hide; we call it by reflection and fall back to
 * building the prefix by hand (identical result), so this works whether or not
 * the hidden method is reachable.
 *
 * Requires the caller to hold INTERACT_ACROSS_USERS_FULL.
 */
object CrossUser {

    fun addUserId(uri: Uri, userId: Int): Uri {
        return try {
            val m = Class.forName("android.content.ContentProvider")
                .getMethod("maybeAddUserId", Uri::class.java, Int::class.javaPrimitiveType)
            m.invoke(null, uri, userId) as Uri
        } catch (_: Exception) {
            // content://<userId>@authority/path
            val authority = uri.authority ?: return uri
            uri.buildUpon().encodedAuthority("$userId@$authority").build()
        }
    }
}
