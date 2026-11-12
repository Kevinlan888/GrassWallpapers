package com.a65.grasswallpapers.wallpaper;

import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

public abstract class BaseGrassWallpaperService extends android.service.wallpaper.WallpaperService {
    private static final long FRAME_DELAY_MS = 33L;

    protected abstract BaseGrassRenderer createRenderer();

    protected boolean supportsTouch() {
        return false;
    }

    @Override
    public Engine onCreateEngine() {
        return new GrassEngine();
    }

    private final class GrassEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable frameRunner = new Runnable() {
            @Override
            public void run() {
                if (destroyed || !visible || !surfaceReady) {
                    return;
                }
                drawFrame();
                if (!destroyed && visible && surfaceReady) {
                    handler.postDelayed(this, FRAME_DELAY_MS);
                }
            }
        };

        private BaseGrassRenderer renderer;
        private boolean visible;
        private boolean surfaceReady;
        private boolean destroyed;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            renderer = createRenderer();
            if (supportsTouch()) {
                setTouchEventsEnabled(true);
            }
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceReady = true;
            scheduleFrames();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (renderer != null) {
                renderer.setSize(width, height);
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            stopFrames();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            this.visible = visible;
            if (visible) {
                scheduleFrames();
            } else {
                stopFrames();
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep,
                float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            if (renderer != null) {
                renderer.setOffset(xOffset);
            }
        }

        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras,
                boolean resultRequested) {
            if (supportsTouch() && renderer != null
                    && "android.wallpaper.tap".equals(action)) {
                renderer.tap(x, y);
            }
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            if (!supportsTouch() || renderer == null) {
                super.onTouchEvent(event);
                return;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                renderer.tap(event.getX(), event.getY());
            }
        }

        private void scheduleFrames() {
            handler.removeCallbacks(frameRunner);
            if (visible && surfaceReady && !destroyed) {
                handler.post(frameRunner);
            }
        }

        private void stopFrames() {
            handler.removeCallbacks(frameRunner);
        }

        private void drawFrame() {
            if (renderer == null) {
                return;
            }
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) {
                    renderer.draw(canvas, isPreview());
                }
            } catch (RuntimeException ignored) {
                // The surface can be torn down between the visibility callback and lockCanvas().
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (RuntimeException ignored) {
                        // Surface already gone.
                    }
                }
            }
        }

        @Override
        public void onDestroy() {
            destroyed = true;
            visible = false;
            surfaceReady = false;
            stopFrames();
            if (renderer != null) {
                renderer.release();
                renderer = null;
            }
            super.onDestroy();
        }
    }
}
