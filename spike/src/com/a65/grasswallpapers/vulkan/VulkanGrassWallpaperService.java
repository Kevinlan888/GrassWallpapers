package com.a65.grasswallpapers.vulkan;

import android.content.SharedPreferences;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.concurrent.atomic.AtomicLong;

public final class VulkanGrassWallpaperService extends WallpaperService {
    private static final AtomicLong NEXT_NATIVE_OWNER = new AtomicLong(1);

    @Override
    public Engine onCreateEngine() {
        return new VulkanEngine();
    }

    private final class VulkanEngine extends Engine {
        private final long nativeOwnerId = NEXT_NATIVE_OWNER.getAndIncrement();
        private boolean visible;
        private boolean surfaceReady;
        private boolean rendering;
        private int surfaceWidth;
        private int surfaceHeight;
        private SharedPreferences preferences;
        private final SharedPreferences.OnSharedPreferenceChangeListener
                preferenceListener = (sharedPreferences, key) ->
                        applySceneSettings();

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setTouchEventsEnabled(true);
            preferences = WallpaperSettings.preferences(
                    VulkanGrassWallpaperService.this);
            preferences.registerOnSharedPreferenceChangeListener(
                    preferenceListener);
            applySceneSettings();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceReady = true;
            startRendering();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format,
                int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceWidth = width;
            surfaceHeight = height;
            if (rendering) {
                VulkanNative.resize(nativeOwnerId, width, height);
            } else {
                startRendering();
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            stopRendering();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            this.visible = visible;
            if (visible) {
                applySceneSettings();
                if (rendering) {
                    int result = VulkanNative.setPaused(
                            nativeOwnerId, false);
                    if (result == VulkanNative.PAUSE_NO_RENDERER) {
                        rendering = false;
                        startRendering();
                    }
                } else {
                    startRendering();
                }
            } else if (rendering) {
                VulkanNative.setPaused(nativeOwnerId, true);
            }
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                handleTap(event.getX(), event.getY());
            } else {
                super.onTouchEvent(event);
            }
        }

        @Override
        public android.os.Bundle onCommand(String action, int x, int y, int z,
                android.os.Bundle extras, boolean resultRequested) {
            if ("android.wallpaper.tap".equals(action)) {
                handleTap(x, y);
            }
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }

        @Override
        public void onDestroy() {
            if (preferences != null) {
                preferences.unregisterOnSharedPreferenceChangeListener(
                        preferenceListener);
                preferences = null;
            }
            stopRendering();
            super.onDestroy();
        }

        private void startRendering() {
            if (rendering || !surfaceReady || !visible) {
                return;
            }
            Surface surface = getSurfaceHolder().getSurface();
            applySceneSettings();
            if (!VulkanNative.start(surface, nativeOwnerId)) {
                return;
            }
            rendering = true;
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                VulkanNative.resize(nativeOwnerId, surfaceWidth, surfaceHeight);
            }
        }

        private void applySceneSettings() {
            VulkanNative.configureSceneMode(
                    WallpaperSettings.isSyncWithLocalTime(
                            VulkanGrassWallpaperService.this),
                    WallpaperSettings.getRotationSeconds(
                            VulkanGrassWallpaperService.this));
        }

        private void stopRendering() {
            if (rendering) {
                VulkanNative.stop(nativeOwnerId);
                rendering = false;
            }
        }

        private void handleTap(float x, float y) {
            if (WallpaperSettings.isSyncWithLocalTime(
                    VulkanGrassWallpaperService.this)) {
                return;
            }
            int width = surfaceWidth;
            if (width <= 0) {
                width = getSurfaceHolder().getSurfaceFrame().width();
            }
            if (width <= 0) {
                return;
            }
            if (x < width * 0.5f) {
                VulkanNative.cycleSeason();
            } else {
                VulkanNative.cycleTime();
            }
        }
    }
}
