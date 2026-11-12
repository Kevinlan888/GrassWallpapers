package com.a65.grasswallpapers.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.SystemClock;

import com.a65.grasswallpapers.R;

import java.util.Calendar;
import java.util.Random;

/**
 * Canvas reimplementation of the RenderScript grass scene shipped with the A65.
 * Blend boundaries and blade parameters follow the original scripts.
 */
public abstract class BaseGrassRenderer {
    protected static final int LAYER_DAY = 0;
    protected static final int LAYER_SUNRISE = 1;
    protected static final int LAYER_SUNSET = 2;
    protected static final int LAYER_NIGHT = 3;

    private static final float DAWN = 6.0f / 24.0f;
    private static final float MORNING = 8.0f / 24.0f;
    private static final float AFTERNOON = 17.0f / 24.0f;
    private static final float DUSK = 19.5f / 24.0f;
    private static final float MAX_BEND = 0.09f;
    private static final float HALF_PI = (float) (Math.PI * 0.5);
    private static final int PREVIEW_CYCLE_MS = 30_000;
    private static final float BLADE_LENGTH_SCALE_PER_HEIGHT = 0.0065f;
    private static final float BLADE_WIDTH_TO_LENGTH = 0.55f;

    protected final Context context;
    protected final Random random = new Random();
    protected int width;
    protected int height;
    protected float bladeLengthScale = 1.0f;
    protected float bladeWidthScale = 1.0f;
    protected float offset = 0.5f;

    private final Bitmap[] backgrounds = new Bitmap[4];
    private final Paint backgroundPaint = new Paint(Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint grassPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint directPaint = new Paint(Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path bladePath = new Path();
    private final Rect destRect = new Rect();
    private final float[] hsv = new float[3];
    private final float[] leftX = new float[16];
    private final float[] leftY = new float[16];
    private final float[] rightX = new float[16];
    private final float[] rightY = new float[16];
    private final Calendar calendar = Calendar.getInstance();
    private final Scene scene = new Scene();

    private Blade[] blades = new Blade[0];
    private Bitmap backgroundCache;
    private Canvas backgroundCanvas;
    private int cacheBase = -1;
    private int cacheOverlay = -1;
    private int cacheAlpha = -1;
    private long lastClockRead;
    private float localPhase;
    private Float forcedPhase;
    private boolean released;

    protected BaseGrassRenderer(Context context) {
        this.context = context.getApplicationContext();
        grassPaint.setStyle(Paint.Style.FILL);
    }

    protected final void loadBackgrounds(int dayResource) {
        backgrounds[LAYER_DAY] = decode(dayResource);
        backgrounds[LAYER_SUNRISE] = decode(R.drawable.sky_sunrise);
        backgrounds[LAYER_SUNSET] = decode(R.drawable.sky_sunset);
        backgrounds[LAYER_NIGHT] = decode(R.drawable.sky_night);
    }

    private Bitmap decode(int resource) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        return BitmapFactory.decodeResource(context.getResources(), resource, options);
    }

    protected abstract int getBladeCount();

    public void setSize(int width, int height) {
        if (released || width <= 0 || height <= 0) {
            return;
        }
        if (this.width == width && this.height == height) {
            return;
        }

        this.width = width;
        this.height = height;
        this.bladeLengthScale = height * BLADE_LENGTH_SCALE_PER_HEIGHT;
        this.bladeWidthScale = bladeLengthScale * BLADE_WIDTH_TO_LENGTH;
        destroyBackgroundCache();
        createBlades();
        onSizeChanged();
    }

    protected void onSizeChanged() {
    }

    public void setOffset(float offset) {
        this.offset = clamp(offset, 0.0f, 1.0f);
    }

    public void setPhaseOverride(Float phase) {
        this.forcedPhase = phase;
    }

    public void tap(float x, float y) {
    }

    public final void draw(Canvas canvas, boolean isPreview) {
        if (released || width <= 0 || height <= 0 || canvas == null) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        float phase;
        if (forcedPhase != null) {
            phase = forcedPhase;
        } else if (isPreview) {
            phase = (now % PREVIEW_CYCLE_MS) / (float) PREVIEW_CYCLE_MS;
        } else {
            phase = localTimePhase(now);
        }

        fillScene(phase);
        ensureBackgroundCache();
        if (backgroundCache != null) {
            canvas.drawBitmap(backgroundCache, 0.0f, 0.0f, null);
        } else {
            drawBackgroundDirect(canvas);
        }

        drawBlades(canvas, scene.grassBrightness, now);
        drawEffects(canvas, scene, now);
    }

    private float localTimePhase(long now) {
        if (now - lastClockRead >= 1000L) {
            calendar.setTimeInMillis(System.currentTimeMillis());
            int seconds = calendar.get(Calendar.HOUR_OF_DAY) * 3600
                    + calendar.get(Calendar.MINUTE) * 60
                    + calendar.get(Calendar.SECOND);
            localPhase = seconds / 86400.0f;
            lastClockRead = now;
        }
        return localPhase;
    }

    private void fillScene(float phase) {
        scene.base = LAYER_NIGHT;
        scene.overlay = -1;
        scene.overlayAlpha = 0.0f;
        scene.grassBrightness = 0.0f;
        scene.night = true;
        scene.phase = phase;

        if (phase >= DAWN && phase <= MORNING) {
            float half = DAWN + (MORNING - DAWN) * 0.5f;
            if (phase <= half) {
                scene.base = LAYER_NIGHT;
                scene.overlay = LAYER_SUNRISE;
                scene.overlayAlpha = norm(DAWN, half, phase);
                scene.grassBrightness = scene.overlayAlpha;
                scene.night = true;
            } else {
                scene.base = LAYER_SUNRISE;
                scene.overlay = LAYER_DAY;
                scene.overlayAlpha = norm(half, MORNING, phase);
                scene.grassBrightness = 1.0f;
                scene.night = false;
            }
        } else if (phase > MORNING && phase < AFTERNOON) {
            scene.base = LAYER_DAY;
            scene.grassBrightness = 1.0f;
            scene.night = false;
        } else if (phase >= AFTERNOON && phase <= DUSK) {
            float half = AFTERNOON + (DUSK - AFTERNOON) * 0.5f;
            if (phase <= half) {
                scene.base = LAYER_DAY;
                scene.overlay = LAYER_SUNSET;
                scene.overlayAlpha = norm(AFTERNOON, half, phase);
                scene.grassBrightness = 1.0f - scene.overlayAlpha;
                scene.night = false;
            } else {
                scene.base = LAYER_SUNSET;
                scene.overlay = LAYER_NIGHT;
                scene.overlayAlpha = norm(half, DUSK, phase);
                scene.grassBrightness = 0.0f;
                scene.night = true;
            }
        }
    }

    private void ensureBackgroundCache() {
        int alphaBucket = Math.round(scene.overlayAlpha * 64.0f);
        if (backgroundCache != null
                && cacheBase == scene.base
                && cacheOverlay == scene.overlay
                && cacheAlpha == alphaBucket) {
            return;
        }

        if (backgroundCache == null
                || backgroundCache.getWidth() != width
                || backgroundCache.getHeight() != height) {
            destroyBackgroundCache();
            try {
                backgroundCache = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
                backgroundCanvas = new Canvas(backgroundCache);
            } catch (OutOfMemoryError ignored) {
                backgroundCache = null;
                return;
            }
        }

        destRect.set(0, 0, width, height);
        backgroundCanvas.drawColor(Color.BLACK);
        backgroundPaint.setAlpha(255);
        drawLayer(backgroundCanvas, backgrounds[scene.base]);
        if (scene.overlay >= 0 && scene.overlayAlpha > 0.0f) {
            backgroundPaint.setAlpha(Math.round(scene.overlayAlpha * 255.0f));
            drawLayer(backgroundCanvas, backgrounds[scene.overlay]);
        }
        backgroundPaint.setAlpha(255);

        cacheBase = scene.base;
        cacheOverlay = scene.overlay;
        cacheAlpha = alphaBucket;
    }

    private void drawBackgroundDirect(Canvas canvas) {
        destRect.set(0, 0, width, height);
        directPaint.setAlpha(255);
        canvas.drawBitmap(backgrounds[scene.base], null, destRect, directPaint);
        if (scene.overlay >= 0 && scene.overlayAlpha > 0.0f) {
            directPaint.setAlpha(Math.round(scene.overlayAlpha * 255.0f));
            canvas.drawBitmap(backgrounds[scene.overlay], null, destRect, directPaint);
            directPaint.setAlpha(255);
        }
    }

    private void drawLayer(Canvas canvas, Bitmap bitmap) {
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, destRect, backgroundPaint);
        }
    }

    private void createBlades() {
        int count = Math.max(1, getBladeCount());
        blades = new Blade[count];
        for (int i = 0; i < count; i++) {
            Blade blade = new Blade();
            blade.x = random.nextFloat() * width * 2.0f - width;
            blade.y = height;
            blade.size = 4 + random.nextInt(5);
            blade.scale = random.nextFloat() * 0.6f + 0.2f;
            blade.lengthX = random.nextFloat() * 4.5f + 3.0f;
            blade.lengthY = random.nextFloat() * 5.5f + 2.0f;
            blade.hardness = random.nextFloat() + 0.2f;
            blade.offset = random.nextFloat() * 0.2f - 0.1f;
            blade.hue = random.nextFloat() * 0.02f + 0.2f;
            blade.saturation = random.nextFloat() * 0.22f + 0.78f;
            blade.brightness = random.nextFloat() * 0.65f + 0.35f;
            blade.turbulenceX = (blade.x / width) * 2.88f;
            blades[i] = blade;
        }
    }

    private void drawBlades(Canvas canvas, float brightness, long now) {
        float xOffset = (1.0f - offset) * width;
        float noiseTime = now * 0.00004f;
        float halfTessellation = 0.25f * bladeWidthScale;

        for (Blade blade : blades) {
            float newAngle = (turbulence(blade.turbulenceX, noiseTime) - 0.5f) * 0.5f;
            blade.angle = clamp(
                    blade.angle + (newAngle + blade.offset - blade.angle) * 0.15f,
                    -MAX_BEND,
                    MAX_BEND);

            float currentAngle = HALF_PI;
            float baseX = blade.x + xOffset;
            float baseY = blade.y;
            float bottomX = baseX;
            float bottomY = baseY;
            float deltaAngle = blade.angle * blade.hardness;
            float baseWidth = blade.size * blade.scale * bladeWidthScale;
            int points = 0;

            for (int size = blade.size; size > 0; size--) {
                float topX = bottomX
                        - (float) Math.cos(currentAngle) * blade.lengthX * bladeLengthScale;
                float topY = bottomY
                        - (float) Math.sin(currentAngle) * blade.lengthY * bladeLengthScale;
                float halfWidth = size * blade.scale * bladeWidthScale;
                float innerWidth = halfWidth - blade.scale * bladeWidthScale;

                leftX[points] = topX - innerWidth;
                leftY[points] = topY;
                rightX[points] = topX + innerWidth;
                rightY[points] = topY;
                points++;

                bottomX = topX;
                bottomY = topY;
                currentAngle += deltaAngle;
            }

            float baseBottom = baseY + halfTessellation;
            float baseLeft = baseX - baseWidth;
            float baseRight = baseX + baseWidth;

            bladePath.reset();
            bladePath.moveTo(baseLeft, baseBottom);
            for (int i = 0; i < points; i++) {
                bladePath.lineTo(leftX[i], leftY[i]);
            }
            for (int i = points - 1; i >= 0; i--) {
                bladePath.lineTo(rightX[i], rightY[i]);
            }
            bladePath.lineTo(baseRight, baseBottom);
            bladePath.close();

            hsv[0] = blade.hue;
            hsv[1] = blade.saturation;
            hsv[2] = blade.brightness * brightness;
            grassPaint.setColor(Color.HSVToColor(255, hsv));
            canvas.drawPath(bladePath, grassPaint);
        }
    }

    protected void drawEffects(Canvas canvas, Scene scene, long now) {
    }

    private static float norm(float start, float end, float value) {
        if (end <= start) {
            return value >= end ? 1.0f : 0.0f;
        }
        return clamp((value - start) / (end - start), 0.0f, 1.0f);
    }

    protected static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    private static float turbulence(float x, float time) {
        float sum = 0.0f;
        float amplitude = 0.5f;
        float frequency = 1.0f;
        float total = 0.0f;
        for (int octave = 0; octave < 4; octave++) {
            sum += amplitude * valueNoise(
                    x * frequency + octave * 13.17f,
                    time * frequency + octave * 7.13f);
            total += amplitude;
            amplitude *= 0.5f;
            frequency *= 2.0f;
        }
        return sum / total;
    }

    private static float valueNoise(float x, float y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        float fx = smooth(x - x0);
        float fy = smooth(y - y0);

        float a = hash(x0, y0);
        float b = hash(x1, y0);
        float c = hash(x0, y1);
        float d = hash(x1, y1);
        float top = a + (b - a) * fx;
        float bottom = c + (d - c) * fx;
        return top + (bottom - top) * fy;
    }

    private static float smooth(float value) {
        return value * value * (3.0f - 2.0f * value);
    }

    private static float hash(int x, int y) {
        int value = x * 374761393 + y * 668265263;
        value = (value ^ (value >>> 13)) * 1274126177;
        value ^= value >>> 16;
        return (value & 0x7fffffff) / 2147483647.0f;
    }

    private void destroyBackgroundCache() {
        if (backgroundCache != null) {
            backgroundCache.recycle();
            backgroundCache = null;
        }
        backgroundCanvas = null;
        cacheBase = -1;
        cacheOverlay = -1;
        cacheAlpha = -1;
    }

    public void release() {
        released = true;
        destroyBackgroundCache();
        for (int i = 0; i < backgrounds.length; i++) {
            if (backgrounds[i] != null) {
                backgrounds[i].recycle();
                backgrounds[i] = null;
            }
        }
        releaseResources();
    }

    protected void releaseResources() {
    }

    protected static final class Scene {
        public int base;
        public int overlay;
        public float overlayAlpha;
        public float grassBrightness;
        public boolean night;
        public float phase;
    }

    private static final class Blade {
        float x;
        float y;
        int size;
        float scale;
        float lengthX;
        float lengthY;
        float hardness;
        float offset;
        float hue;
        float saturation;
        float brightness;
        float turbulenceX;
        float angle;
    }
}
