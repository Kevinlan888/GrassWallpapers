package com.a65.grasswallpapers.vulkan;

import android.util.Log;
import android.view.Surface;

final class VulkanNative {
    private static final String TAG = "VulkanGrassWallpaper";
    private static final boolean LOADED;
    static final int PAUSE_APPLIED = 1;
    static final int PAUSE_NO_RENDERER = 0;
    static final int PAUSE_OTHER_OWNER = -1;
    static final int PAUSE_UNAVAILABLE = -2;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("vulkanspike");
            loaded = true;
        } catch (Throwable error) {
            Log.e(TAG, "Unable to load libvulkanspike.so", error);
        }
        LOADED = loaded;
    }

    private VulkanNative() {
    }

    static boolean start(Surface surface, long ownerId) {
        if (!LOADED || surface == null || !surface.isValid()) {
            return false;
        }
        try {
            nativeStart(surface, ownerId);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "nativeStart failed", error);
            return false;
        }
    }

    static void stop(long ownerId) {
        if (!LOADED) {
            return;
        }
        try {
            nativeStop(ownerId);
        } catch (Throwable error) {
            Log.e(TAG, "nativeStop failed", error);
        }
    }

    static void resize(long ownerId, int width, int height) {
        if (!LOADED) {
            return;
        }
        try {
            nativeResize(ownerId, width, height);
        } catch (Throwable error) {
            Log.e(TAG, "nativeResize failed", error);
        }
    }

    static int setPaused(long ownerId, boolean paused) {
        if (!LOADED) {
            return PAUSE_UNAVAILABLE;
        }
        try {
            return nativeSetPaused(ownerId, paused);
        } catch (Throwable error) {
            Log.e(TAG, "nativeSetPaused failed", error);
            return PAUSE_UNAVAILABLE;
        }
    }

    static void configureSceneMode(boolean syncWithLocalTime,
            int rotationSeconds) {
        if (!LOADED) {
            return;
        }
        try {
            nativeConfigureSceneMode(syncWithLocalTime, rotationSeconds);
        } catch (Throwable error) {
            Log.e(TAG, "nativeConfigureSceneMode failed", error);
        }
    }

    static void cycleSeason() {
        if (!LOADED) {
            return;
        }
        try {
            nativeCycleSeason();
        } catch (Throwable error) {
            Log.e(TAG, "nativeCycleSeason failed", error);
        }
    }

    static void cycleTime() {
        if (!LOADED) {
            return;
        }
        try {
            nativeCycleTime();
        } catch (Throwable error) {
            Log.e(TAG, "nativeCycleTime failed", error);
        }
    }

    static String status() {
        if (!LOADED) {
            return "ERROR: libvulkanspike.so could not be loaded";
        }
        try {
            return nativeGetStatus();
        } catch (Throwable error) {
            return "ERROR: " + error;
        }
    }

    private static native void nativeStart(Surface surface, long ownerId);

    private static native void nativeStop(long ownerId);

    private static native void nativeResize(long ownerId, int width, int height);

    private static native int nativeSetPaused(long ownerId, boolean paused);

    private static native void nativeConfigureSceneMode(
            boolean syncWithLocalTime, int rotationSeconds);

    private static native void nativeCycleSeason();

    private static native void nativeCycleTime();

    private static native String nativeGetStatus();
}
