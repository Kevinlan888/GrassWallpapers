package com.a65.grasswallpapers.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.a65.grasswallpapers.R;

public final class MtkGrassRenderer extends BaseGrassRenderer {
    private static final int NORMAL_COUNT = 10;
    private static final int EXTRA_COUNT = 50;
    private static final float SPEED_VARIANCE = 0.3f;
    private static final float DANDELION_SCREEN_SECONDS = 18.0f;
    private static final float FIREFLY_SCREEN_SECONDS = 14.0f;
    private static final long MAX_INITIAL_DELAY = 5_000L;
    private static final long MAX_BLOW_INTERVAL = 180_000L;

    private final Particle[] normalParticles = new Particle[NORMAL_COUNT];
    private final Particle[] extraParticles = new Particle[EXTRA_COUNT];
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);

    private Bitmap fireflyDim;
    private Bitmap fireflyLit;
    private Bitmap dandelion;
    private float particleSize;
    private boolean particleModeReady;
    private boolean nightMode;
    private int windDirection;
    private long windChangeAt;
    private long lastFrame;
    private float pendingTapX;
    private float pendingTapY;
    private boolean hasPendingTap;

    public MtkGrassRenderer(Context context) {
        super(context);
        loadBackgrounds(R.drawable.sky_day_mtk);
        fireflyDim = decode(R.drawable.firefly_dim);
        fireflyLit = decode(R.drawable.firefly_lit);
        dandelion = decode(R.drawable.dandelion);
        for (int i = 0; i < normalParticles.length; i++) {
            normalParticles[i] = new Particle();
        }
        for (int i = 0; i < extraParticles.length; i++) {
            extraParticles[i] = new Particle();
            extraParticles[i].extra = true;
        }
    }

    private Bitmap decode(int resource) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        return BitmapFactory.decodeResource(context.getResources(), resource, options);
    }

    @Override
    protected int getBladeCount() {
        return 100;
    }

    @Override
    protected void onSizeChanged() {
        particleSize = clamp(width * 0.07f, 32.0f, 220.0f);
        particleModeReady = false;
        lastFrame = 0L;
    }

    @Override
    public void tap(float x, float y) {
        pendingTapX = clamp(x, 0.0f, width);
        pendingTapY = clamp(y, 0.0f, height);
        hasPendingTap = true;
    }

    @Override
    protected void drawEffects(Canvas canvas, Scene scene, long now) {
        if (!particleModeReady || nightMode != scene.night) {
            initializeParticles(scene.night, now);
        }

        long delta = lastFrame == 0L ? 0L : Math.min(100L, now - lastFrame);
        lastFrame = now;

        if (hasPendingTap) {
            spawnExtraParticle(pendingTapX, pendingTapY, now);
            hasPendingTap = false;
        }

        if (nightMode) {
            updateFireflies(normalParticles, now, delta);
            updateFireflies(extraParticles, now, delta);
        } else {
            updateDandelions(normalParticles, now, delta);
            updateDandelions(extraParticles, now, delta);
        }

        drawParticles(canvas, normalParticles);
        drawParticles(canvas, extraParticles);
    }

    private void initializeParticles(boolean night, long now) {
        nightMode = night;
        particleModeReady = true;
        windDirection = 0;
        windChangeAt = now + randomLong(60_000L, MAX_BLOW_INTERVAL);
        lastFrame = now;

        for (Particle particle : normalParticles) {
            particle.active = true;
            particle.extra = false;
            if (night) {
                initializeFirefly(particle, now, true);
            } else {
                initializeDandelion(particle, now, true);
            }
        }

        for (Particle particle : extraParticles) {
            particle.active = false;
            particle.startTime = now;
        }
    }

    private void initializeFirefly(Particle particle, long now, boolean initial) {
        particle.startTime = now + randomLong(0L, MAX_INITIAL_DELAY);
        particle.originX = random(0.0f, width * 2.0f);
        particle.originY = initial
                ? random(height * 0.18f, height * 1.02f)
                : height + random(0.0f, particleSize);
        particle.angle = 0.0f;
        initializeFireflyVelocity(particle);
        particle.nextBlink = now + randomLong(1_200L, 5_000L);
        particle.litUntil = now + randomLong(0L, 900L);
    }

    private void initializeDandelion(Particle particle, long now, boolean initial) {
        particle.startTime = now + randomLong(0L, MAX_INITIAL_DELAY);
        if (windDirection == 0) {
            particle.originX = 0.0f;
        } else {
            particle.originX = width * 2.0f;
        }
        particle.originY = initial
                ? random(height * 0.06f, height * 0.96f)
                : random(0.0f, height * 0.96f);
        particle.angle = random(-30.0f, 30.0f);
        initializeDandelionVelocity(particle);
        particle.nextBlink = Long.MAX_VALUE;
        particle.litUntil = 0L;
    }

    private void updateFireflies(Particle[] particles, long now, long delta) {
        float speed = width / (FIREFLY_SCREEN_SECONDS * 1000.0f);
        for (Particle particle : particles) {
            if (!particle.active || now < particle.startTime) {
                continue;
            }

            particle.originX += particle.dx * speed * delta;
            particle.originY += particle.dy * speed * delta;

            if (particle.originX < 0.0f || particle.originX > width * 2.0f
                    || particle.originY < 0.0f || particle.originY > height + particleSize) {
                if (particle.extra) {
                    particle.active = false;
                } else {
                    initializeFirefly(particle, now, false);
                }
                continue;
            }

            if (now >= particle.nextBlink) {
                particle.litUntil = now + randomLong(260L, 1_050L);
                particle.nextBlink = now + randomLong(1_200L, 5_000L);
            }
        }
    }

    private void updateDandelions(Particle[] particles, long now, long delta) {
        if (now >= windChangeAt) {
            windDirection = windDirection == 0 ? 1 : 0;
            windChangeAt = now + randomLong(60_000L, MAX_BLOW_INTERVAL);
        }

        float speed = width / (DANDELION_SCREEN_SECONDS * 1000.0f);
        for (Particle particle : particles) {
            if (!particle.active || now < particle.startTime) {
                continue;
            }

            particle.originX += particle.dx * speed * delta;
            particle.originY += particle.dy * speed * delta;

            if (particle.originX < -particleSize || particle.originX > width * 2.0f + particleSize
                    || particle.originY < -particleSize || particle.originY > height + particleSize) {
                if (particle.extra) {
                    particle.active = false;
                } else {
                    initializeDandelion(particle, now, false);
                }
            }
        }
    }

    private void spawnExtraParticle(float x, float y, long now) {
        for (Particle particle : extraParticles) {
            if (particle.active) {
                continue;
            }

            particle.active = true;
            particle.startTime = now;
            particle.originX = x + offset * width;
            particle.originY = y;
            if (nightMode) {
                particle.angle = 0.0f;
                initializeFireflyVelocity(particle);
                particle.nextBlink = now;
                particle.litUntil = now + 1_200L;
            } else {
                particle.angle = random(-30.0f, 30.0f);
                initializeDandelionVelocity(particle);
                particle.nextBlink = Long.MAX_VALUE;
                particle.litUntil = 0L;
            }
            return;
        }
    }

    private void drawParticles(Canvas canvas, Particle[] particles) {
        float half = particleSize * 0.5f;
        float drawOffset = offset * width;

        for (Particle particle : particles) {
            if (!particle.active || particle.startTime > lastFrame) {
                continue;
            }

            float x = particle.originX - drawOffset;
            float y = particle.originY;
            if (x < -particleSize || x > width + particleSize
                    || y < -particleSize || y > height + particleSize) {
                continue;
            }

            Bitmap bitmap;
            if (nightMode) {
                bitmap = particle.litUntil > lastFrame ? fireflyLit : fireflyDim;
            } else {
                bitmap = dandelion;
            }
            if (bitmap == null) {
                continue;
            }

            canvas.save();
            canvas.translate(x, y);
            if (!nightMode && particle.angle != 0.0f) {
                canvas.rotate(particle.angle);
            }
            canvas.drawBitmap(bitmap, -half, -half, particlePaint);
            canvas.restore();
        }
    }

    private float random(float min, float max) {
        return min + random.nextFloat() * (max - min);
    }

    private void initializeFireflyVelocity(Particle particle) {
        particle.dx = randomSign() * random(1.0f - SPEED_VARIANCE,
                1.0f + SPEED_VARIANCE);
        particle.dy = -random(1.0f - SPEED_VARIANCE,
                1.0f + SPEED_VARIANCE);
    }

    private void initializeDandelionVelocity(Particle particle) {
        float horizontalSpeed = random(1.0f - SPEED_VARIANCE,
                1.0f + SPEED_VARIANCE);
        particle.dx = windDirection == 0 ? horizontalSpeed : -horizontalSpeed;
        particle.dy = randomSign() * random(1.0f - SPEED_VARIANCE,
                1.0f + SPEED_VARIANCE);
    }

    private float randomSign() {
        return random.nextBoolean() ? 1.0f : -1.0f;
    }

    private long randomLong(long min, long max) {
        if (max <= min) {
            return min;
        }
        return min + (long) (random.nextDouble() * (max - min));
    }

    @Override
    protected void releaseResources() {
        if (fireflyDim != null) {
            fireflyDim.recycle();
            fireflyDim = null;
        }
        if (fireflyLit != null) {
            fireflyLit.recycle();
            fireflyLit = null;
        }
        if (dandelion != null) {
            dandelion.recycle();
            dandelion = null;
        }
    }

    private static final class Particle {
        boolean active;
        boolean extra;
        long startTime;
        long nextBlink;
        long litUntil;
        float originX;
        float originY;
        float dx;
        float dy;
        float angle;
    }
}
