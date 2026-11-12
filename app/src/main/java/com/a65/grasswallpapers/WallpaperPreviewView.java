package com.a65.grasswallpapers;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import com.a65.grasswallpapers.wallpaper.AospGrassRenderer;
import com.a65.grasswallpapers.wallpaper.BaseGrassRenderer;
import com.a65.grasswallpapers.wallpaper.MtkGrassRenderer;

public final class WallpaperPreviewView extends View {
    private static final long FRAME_DELAY_MS = 33L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            invalidate();
            handler.postDelayed(this, FRAME_DELAY_MS);
        }
    };

    private BaseGrassRenderer renderer;
    private int variant = -1;
    private boolean running;
    private boolean attached;

    public WallpaperPreviewView(Context context) {
        this(context, null);
    }

    public WallpaperPreviewView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WallpaperPreviewView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        int variant = 0;
        if (attrs != null) {
            TypedArray values = context.obtainStyledAttributes(
                    attrs, R.styleable.WallpaperPreviewView, defStyleAttr, 0);
            variant = values.getInt(R.styleable.WallpaperPreviewView_grassVariant, 0);
            values.recycle();
        }

        this.variant = variant;
        renderer = createRenderer(variant);
        setWillNotDraw(false);
    }

    public void setGrassVariant(int variant) {
        if (this.variant == variant) {
            return;
        }

        boolean wasRunning = running;
        stopAnimation();
        this.variant = variant;
        renderer = createRenderer(variant);
        if (getWidth() > 0 && getHeight() > 0) {
            renderer.setSize(getWidth(), getHeight());
        }
        invalidate();
        if (wasRunning) {
            updateAnimationState();
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        renderer.setSize(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        renderer.draw(canvas, true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        updateAnimationState();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateAnimationState();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateAnimationState();
    }

    private void updateAnimationState() {
        if (attached && isShown()) {
            startAnimation();
        } else {
            stopAnimation();
        }
    }

    private void startAnimation() {
        if (running) {
            return;
        }
        running = true;
        handler.post(frame);
    }

    private void stopAnimation() {
        running = false;
        handler.removeCallbacks(frame);
    }

    private BaseGrassRenderer createRenderer(int variant) {
        BaseGrassRenderer nextRenderer;
        if (variant == 1) {
            nextRenderer = new MtkGrassRenderer(getContext());
            nextRenderer.setPhaseOverride(0.91f);
        } else {
            nextRenderer = new AospGrassRenderer(getContext());
            nextRenderer.setPhaseOverride(0.45f);
        }
        return nextRenderer;
    }
}
