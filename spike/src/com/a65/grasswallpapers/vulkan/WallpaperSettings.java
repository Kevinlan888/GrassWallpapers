package com.a65.grasswallpapers.vulkan;

import android.content.Context;
import android.content.SharedPreferences;

final class WallpaperSettings {
    static final int DEFAULT_ROTATION_SECONDS = 300;
    static final int MIN_ROTATION_SECONDS = 10;
    static final int MAX_ROTATION_SECONDS = 24 * 60 * 60;

    private static final String PREFERENCES_NAME = "grass_wallpaper_settings";
    private static final String KEY_SYNC_WITH_LOCAL_TIME = "sync_with_local_time";
    private static final String KEY_ROTATION_SECONDS = "rotation_seconds";

    private WallpaperSettings() {
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    static boolean isSyncWithLocalTime(Context context) {
        return preferences(context).getBoolean(KEY_SYNC_WITH_LOCAL_TIME, true);
    }

    static void setSyncWithLocalTime(Context context, boolean enabled) {
        preferences(context).edit()
                .putBoolean(KEY_SYNC_WITH_LOCAL_TIME, enabled)
                .apply();
    }

    static int getRotationSeconds(Context context) {
        int seconds = preferences(context).getInt(
                KEY_ROTATION_SECONDS, DEFAULT_ROTATION_SECONDS);
        return Math.max(MIN_ROTATION_SECONDS,
                Math.min(MAX_ROTATION_SECONDS, seconds));
    }

    static void setRotationSeconds(Context context, int seconds) {
        preferences(context).edit()
                .putInt(KEY_ROTATION_SECONDS,
                        Math.max(MIN_ROTATION_SECONDS,
                                Math.min(MAX_ROTATION_SECONDS, seconds)))
                .apply();
    }
}
