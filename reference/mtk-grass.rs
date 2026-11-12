// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma version(1)
#pragma stateVertex(PVBackground)
#pragma stateFragment(PFBackground)
#pragma stateStore(PFSBackground)

#define RSID_BLADES_BUFFER 2

#define TESSELATION 0.5f
#define HALF_TESSELATION 0.25f

#define MAX_BEND 0.09f

#define SECONDS_IN_DAY 86400.0f

#define PI 3.1415926f
#define HALF_PI 1.570796326f

#define REAL_TIME 1

#define MAX_NORMAL           10
#define MAX_EXTRAS           50
#define DRAW_SIZE            State->drawSize    // Size in pixels of a cell

#define SPEED                0.1f   // (200 / 1000) Pixels per ms
#define SPEED_VARIANCE       0.3f
#define PARTICLE_NORMAL      0
#define PARTICLE_EXTRA       1
#define MAX_DELAY            5000 // Delay between a pulse going offscreen and restarting
#define MAX_STAY             5000 // the max time spend in staying on the grass
#define MAX_FLARE            1000 // the max time of flare
#define MAX_INTERVAL         5000 // the max interval between two flares
#define INTERVAL_VARIANCE    0.3f
#define MAX_BLOW_INTERVAL    180000 // max interval to change blow direction, 3 minutes

#define DEBUG				 1		// in debug mode or not

struct particle_s {
    char type;               // normal or extra
    char active;
    float angle;             // rotate angle
    char bladeNum;
    char sizeNum;
    int texture;            // texture ID
    int startTime;
    int stayEndTime;        // when to finish stay. stayEndTime=gNow+stay interval
    int silentEndTime;      // when to finish normal(no flares)
    int flareEndTime;       // when to finish flares
    float originX;
    float originY;
    float dx;
    float dy;
};

struct particle_s gNormal[MAX_NORMAL];
struct particle_s gExtras[MAX_EXTRAS];

int gNow;
int gType;      	// scene type:1 -> firefly, 0 -> dandelion
int gDirection;		// blow direction: 0 -> west, 1 -> east, 2 -> north, 3 -> south
int gBlowTime;		// a directional wind interval

// func must defined before used.
void alpha(float a) {
    color(1.0f, 1.0f, 1.0f, a);
}

/**
 * define fly behaviour for firefly
 * just update its speed and position
 * @param firefly
 * @param isInit initialize a particle or just update it
 */
void flyFirefly(struct particle_s * firefly, int isInit) {
	struct particle_s * p = firefly;
	int delta = gNow - p->startTime;
	//debugF("delta = ", delta);
	
    if (randf(1) > 0.5f) {
        p->dx = - randf2(1.0f - SPEED_VARIANCE, 1.0f + SPEED_VARIANCE);
    } else {
        p->dx = randf2(1.0f - SPEED_VARIANCE, 1.0f + SPEED_VARIANCE);
    }
    p->dy = - randf2(1.0f - SPEED_VARIANCE, 1.0f + SPEED_VARIANCE);
    
    if (isInit == 1) {
    	p->originX = randf2( 0, State->width * 2);
        p->originY = State->height;
    } else {
    	p->originX = p->originX + (p->dx * SPEED * delta);
        p->originY = p->originY + (p->dy * SPEED * delta);
    }
}
/**
 * define fly behaviour for dandelion
 * just update its speed and position
 * @param dandelion
 * @param isInit initialize a particle or just update it
 */
void flyDandelion(struct particle_s * dandelion, int isInit) {
	struct particle_s * p = dandelion;
	int delta = gNow - p->startTime;
	//debugF("delta = ", delta);
	
	if (randf(1) > 0.5f) {
        p->dy = - randf2(1.0f - SPEED_VARIANCE, 1.0f + SPEED_VARIANCE);
    } else {
        p->dy = randf2(1.0f - SPEED_VARIANCE, 1.0f + SPEED_VARIANCE);
    }
    // speed direction is based on wind direction
    if (gDirection == 0) {	// west wind
    	p->dx = randf2(1.0f - SPEED_VARIANCE, 1.0f + SPEED_VARIANCE);
    } else if (gDirection == 1) {	// east wind
    	p->dx = - randf2(1.0f - SPEED_VARIANCE, 1.0f + SPEED_VARIANCE);
    } else if (gDirection == 3) {	// north wind
    	//TODO
    } else if (gDirection == 4){	// south wind
    	//TODO
    } else {
    	debugF("error: unknow wind blow direction ", gDirection);
    }
    
    if (isInit == 1) {
    	if (gDirection == 0) {	// west wind
        	p->originX = 0.0f;
        	p->originY = randf2(0, State->height);
        } else if (gDirection == 1){	// east wind
        	p->originX = State->width * 2;
        	p->originY = randf2(0, State->height);
        } else if (gDirection == 3) {	// north wind
        	//TODO
        } else if (gDirection == 4) {	// south wind
        	//TODO
        } else {
        	debugF("error: unknow wind blow direction ", gDirection);
        }
        
    } else {
    	p->originX = p->originX + (p->dx * SPEED * delta);
        p->originY = p->originY + (p->dy * SPEED * delta);
    }
    
}

/**
* the behaviour of dandelion and firefly are different
*/
void initParticle(struct particle_s * p, int type) {
    p->startTime = gNow + (int)randf(MAX_DELAY);
    p->silentEndTime = gNow + (int)(randf2(1.0-INTERVAL_VARIANCE, 1.0+INTERVAL_VARIANCE) * MAX_INTERVAL);
    p->flareEndTime = gNow;
    p->stayEndTime = gNow;
    p->texture = NAMED_TDandelion;
    p->bladeNum = -1;
    p->sizeNum = -1;
    p->type = type;
    
    int isInit = 1;
    if (gType == 0) {   // now it is drawing dandelion
        flyDandelion(p, isInit);
        p->angle = randf2(-30.0f, 30.0f);
        
    } else if (gType == 1){     // now it is drawing firefly
        flyFirefly(p, isInit);
    } else {
        debugF("error: unknown type", gType);
    }
    if (type == PARTICLE_EXTRA) {
        p->active = 0;
    } else {
        p->active = 1;
    }
}

void initParticles() {
    // init global variables
    debugF("init global particles", 0);
    gNow = uptimeMillis();
    gType = 0;		// draw dandelion
    gDirection = 0;	// west wind
    gBlowTime = gNow + randf(MAX_BLOW_INTERVAL);
    
    int i;
    for (i=0; i<MAX_NORMAL; i++) {
        initParticle(&gNormal[i], PARTICLE_NORMAL);
    }
    for (i=0; i<MAX_EXTRAS; i++) {
        struct particle_s * p = &gExtras[i];
        p->type = PARTICLE_EXTRA;
        p->active = 0;
    }
}

void updateFirefly(struct particle_s * particleSet, int setSize) {
    struct particle_s * p = 0;
    int i;
    for (i = 0; i < setSize; i++) {
        p = &particleSet[i];
        int delta = gNow - p->startTime;
        int deltaStay = p->stayEndTime - gNow;
        int deltaFlare = p->flareEndTime - gNow;
        int deltaSilent = p->silentEndTime - gNow;
        int interval = p->flareEndTime - p->silentEndTime;

        if (p->active != 0 && delta >= 0) {
            if (p->originX < 0 || p->originX > State->width*2 || p->originY < 0) {
                initParticle(p, p->type);
                return; // must return here
            }
            // if not stay on grass now, then update its position
            if (deltaStay <= 0) {
            	int isInit = 0;
                flyFirefly(p, isInit);
            }
            // this must behind everything in wich p->startTime is used
            p->startTime = gNow;
            //<-- trick we should update the fields value at the end of code block
            //->>
            if (deltaFlare <= 0 && interval > 0) {
                p->silentEndTime = gNow + (int)(randf2(1.0-INTERVAL_VARIANCE, 1.0+INTERVAL_VARIANCE) * MAX_INTERVAL);
                p->texture = NAMED_TFirefly1;
            }else if (deltaSilent <= 0 && - interval > 0 ){
                p->flareEndTime = gNow + randf(MAX_FLARE);
                p->texture = NAMED_TFirefly2;
            }else if(deltaFlare > 0) {
                p->texture = NAMED_TFirefly2;
            }else {
                p->texture = NAMED_TFirefly1;
            }
        }
    }
}


void updateDandelion(struct particle_s * particleSet, int setSize) {
    struct particle_s * p = 0;
    int deltaBlow = gBlowTime - gNow;
    // change wind blow directions
    if (deltaBlow < 0) {
    	gDirection += 1;
    	// now just support west and east wind
    	if (gDirection > 1) {
    		gDirection = 0;
    	}
    	gBlowTime = gNow + randf(MAX_BLOW_INTERVAL);
    }
    int i;
    for (i = 0; i < setSize; i++) {
        p = &particleSet[i];

        int delta = gNow - p->startTime;
        int deltaStay = p->stayEndTime - gNow;
        
        if (p->active != 0 && delta >= 0) {
            if (p->originX < 0 || p->originX > State->width*2 || p->originY < 0 || p->originY > State->height) {
                initParticle(p, p->type);
                return;     // must return here
            }

            // if not stay on grass now, then update its position
            if (deltaStay <= 0) {
            	int isInit = 0;
            	flyDandelion(p, isInit);
            }
            // this must behind everything in wich p->startTime is used
            p->startTime = gNow;
            //<-- trick we should update the fields value at the end of code block
            //->>
            p->texture = NAMED_TDandelion;
        }
    }
}

void drawParticles(struct particle_s * particleSet, int setSize) {
    float matrix[16];
    struct particle_s * p = 0;
    int i;
    for (i=0; i<setSize; i++) {
        p = &particleSet[i];
        matrixLoadIdentity(matrix);
        int delta = gNow - p->startTime;
        if (p->active != 0 && delta >= 0) {
            // use depth buffer
            float z = randf2(-1.0f, 1.0f);
            float x1 = p->originX - DRAW_SIZE * 0.5f;
            float y1 = p->originY - DRAW_SIZE * 0.5f;
            float x2 = p->originX + DRAW_SIZE * 0.5f;
            float y2 = p->originY + DRAW_SIZE * 0.5f;

            matrixRotate(matrix, p->angle, 0.0f, 0.0f, 1.0f);
            vpLoadTextureMatrix(matrix);
            bindTexture(NAMED_PFTexture, 0, p->texture);

            drawRect(x1, y1, x2, y2, z);
        }
    }
    // restore matrix state
    matrixLoadIdentity(matrix);
    vpLoadTextureMatrix(matrix);
}

void drawFirefly() {
    bindProgramFragment(NAMED_PFTexture);
    bindProgramFragmentStore(NAMED_PSBlend);

    float matrix[16];
    matrixLoadIdentity(matrix);
    matrixTranslate(matrix, -(State->xOffset * State->width), 0, 0);
    vpLoadModelMatrix(matrix);

    updateFirefly(gNormal, MAX_NORMAL);
    drawParticles(gNormal, MAX_NORMAL);

    updateFirefly(gExtras, MAX_EXTRAS);
    drawParticles(gExtras, MAX_EXTRAS);
    // restore matrix state
    matrixLoadIdentity(matrix);
    vpLoadModelMatrix(matrix);

    //need to do so
    bindProgramFragment(NAMED_PFBackground);
    bindProgramFragmentStore(NAMED_PFSBackground);

}

void drawDandelion() {
    bindProgramFragment(NAMED_PFTexture);
    bindProgramFragmentStore(NAMED_PSBlend);

    float matrix[16];
    matrixLoadIdentity(matrix);
    matrixTranslate(matrix, -(State->xOffset * State->width), 0, 0);
    vpLoadModelMatrix(matrix);

    updateDandelion(gNormal, MAX_NORMAL);
    drawParticles(gNormal, MAX_NORMAL);

    updateDandelion(gExtras, MAX_EXTRAS);
    drawParticles(gExtras, MAX_EXTRAS);
    // restore matrix state
    matrixLoadIdentity(matrix);
    vpLoadModelMatrix(matrix);

    //need to do so
    bindProgramFragment(NAMED_PFBackground);
    bindProgramFragmentStore(NAMED_PFSBackground);

}

void addTap(int x, int y) {
    struct particle_s * p = 0;
    int i;
    for (i=0; i<MAX_EXTRAS; i++) {
        p = &gExtras[i];
        if (p->active == 0) {
            p->originX = x;
            p->originY = y;
            p->startTime = gNow;
            p->active = 1;

            break;
        }
    }
}

int cross(int bottomX, int bottomY, int topLeft, int topRight, int topY, struct particle_s * particle) {
    // Note: bottom > top, because y-coord grows down
    int left = (int)minf(topLeft, bottomX);
    int right = (int)maxf(topRight, bottomX);
    int top = topY;
    int bottom = bottomY;
    int x = particle->originX;
    int y = particle->originY;
    if ((x>left && x<right) && (y>top && y<bottom)) {
        return 1;
    }
    return 0;
}

void stay(int bottomX, int bottomY, int topLeft, int topRight, int topY,
          struct particle_s * particleSet, int setSize, char bladeNum, char sizeNum) {
    struct particle_s * p = 0;
    int i;
    for (i=0; i<setSize; i++) {
        p = &particleSet[i];
        int delta = gNow - p->startTime;

        if (p->active != 0 && delta >= 0) {
            int deltaStay = p->stayEndTime - gNow;
            // if not stay on grass
            if (deltaStay < 0) {
                int res = cross(bottomX, bottomY, topLeft, topRight, topY, p);
                if (res) {
                    p->stayEndTime = gNow + randf(MAX_STAY);
                    p->bladeNum = bladeNum;
                    p->sizeNum = sizeNum;
                }
            }else if (deltaStay > 0) {  // still stay on grass, then move with the grass
                if (p->bladeNum == bladeNum && p->sizeNum == sizeNum) {
                    p->originX = bottomX;
                    p->originY = (topY + bottomY) / 2;
                }
            }
        }
    }
}

void updateBlades()
{
    int bladesCount = State->bladesCount;
    struct Blades_s *bladeStruct = Blades;

    int i;
    for (i = 0; i < bladesCount; i ++) {
        float xpos = randf2(-State->width, State->width);
        bladeStruct->xPos = xpos;
        bladeStruct->turbulencex = xpos * 0.006f;
        bladeStruct->yPos = State->height;
        bladeStruct++;
    }
}

float time(int isPreview) {
    if (REAL_TIME && !isPreview) {
        return (hour() * 3600.0f + minute() * 60.0f + second()) / SECONDS_IN_DAY;
    }
    float t = uptimeMillis() / 30000.0f;
    return t - (int) t;
}

void drawNight(int width, int height) {
    bindTexture(NAMED_PFBackground, 0, NAMED_TNight);
    drawQuadTexCoords(
            0.0f, -32.0f, 0.0f,
            0.0f, 1.0f,
            width, -32.0f, 0.0f,
            2.0f, 1.0f,
            width, 1024.0f - 32.0f, 0.0f,
            2.0f, 0.0f,
            0.0f, 1024.0f - 32.0f, 0.0f,
            0.0f, 0.0f);
}

void drawSunrise(int width, int height) {
    bindTexture(NAMED_PFBackground, 0, NAMED_TSunrise);
    drawRect(0.0f, 0.0f, width, height, 0.0f);
}

void drawNoon(int width, int height) {
    bindTexture(NAMED_PFBackground, 0, NAMED_TSky);
    drawRect(0.0f, 0.0f, width, height, 0.0f);
}

void drawSunset(int width, int height) {
    bindTexture(NAMED_PFBackground, 0, NAMED_TSunset);
    drawRect(0.0f, 0.0f, width, height, 0.0f);
}

int drawBlade(struct Blades_s *bladeStruct, float *bladeBuffer, int *bladeColor,
        float brightness, float xOffset, float now, char bladeNum) {

    float scale = bladeStruct->scale;
    float angle = bladeStruct->angle;
    float xpos = bladeStruct->xPos + xOffset;
    int size = bladeStruct->size;
    // threshold for calculate cross
    char szStart = 0;
    char szEnd = (char)(size / 5);

    int color = hsbToAbgr(bladeStruct->h, bladeStruct->s,
                          lerpf(0, bladeStruct->b, brightness), 1.0f);

    float newAngle = (turbulencef2(bladeStruct->turbulencex, now, 4.0f) - 0.5f) * 0.5f;
    angle = clampf(angle + (newAngle + bladeStruct->offset - angle) * 0.15f, -MAX_BEND, MAX_BEND);

    float currentAngle = HALF_PI;

    float bottomX = xpos;
    float bottomY = bladeStruct->yPos;

    float d = angle * bladeStruct->hardness;

    float si = size * scale;
    float bottomLeft = bottomX - si;
    float bottomRight = bottomX + si;
    float bottom = bottomY + HALF_TESSELATION;

    bladeColor[0] = color;                          // V1.ABGR
    bladeBuffer[1] = bottomLeft;                    // V1.X
    bladeBuffer[2] = bottom;                        // V1.Y
    bladeBuffer[3] = 0.f;                           // V1.s
    bladeBuffer[4] = 0.f;                           // V1.t
                                                    //
    bladeColor[5] = color;                          // V2.ABGR
    bladeBuffer[6] = bottomRight;                   // V2.X
    bladeBuffer[7] = bottom;                        // V2.Y
    bladeBuffer[8] = 1.f;                           // V2.s
    bladeBuffer[9] = 0.f;                           // V2.t
    bladeBuffer += 10;
    bladeColor += 10;

    for ( ; size > 0; size -= 1) {
        float topX = bottomX - cosf_fast(currentAngle) * bladeStruct->lengthX;
        float topY = bottomY - sinf_fast(currentAngle) * bladeStruct->lengthY;

        si = (float)size * scale;
        float spi = si - scale;

        float topLeft = topX - spi;
        float topRight = topX + spi;

        bladeColor[0] = color;                          // V1.ABGR
        bladeBuffer[1] = topLeft;                       // V1.X
        bladeBuffer[2] = topY;                          // V1.Y
        bladeBuffer[3] = 0.f;                           // V1.s
        bladeBuffer[4] = 0.f;                           // V1.t

        bladeColor[5] = color;                          // V2.ABGR
        bladeBuffer[6] = topRight;                      // V2.X
        bladeBuffer[7] = topY;                          // V2.Y
        bladeBuffer[8] = 1.f;                           // V2.s
        bladeBuffer[9] = 0.f;                           // V2.t

        // calculate cross
        if (size >= szStart && size < szEnd) {
            int bX = bottomX - xOffset + State->width;
            int bY = bottomY;
            int tL = topLeft - xOffset + State->width;
            int tR = topRight - xOffset + State->width;
            int tY = topY;
            char sizeNum = (char)size;
            stay(bX, bY, tL, tR, tY, gNormal, MAX_NORMAL, bladeNum, sizeNum);
            stay(bX, bY, tL, tR, tY, gExtras, MAX_EXTRAS, bladeNum, sizeNum);
        }

        bladeBuffer += 10;
        bladeColor += 10;

        bottomX = topX;
        bottomY = topY;

        currentAngle += d;
    }

    bladeStruct->angle = angle;

    // 2 vertices per triangle, 5 properties per vertex (RGBA, X, Y, S, T)
    return bladeStruct->size * 10 + 10;
}

void drawBlades(float brightness, float xOffset) {
    // For anti-aliasing
    bindTexture(NAMED_PFGrass, 0, NAMED_TAa);

    int bladesCount = State->bladesCount;

    int i = 0;
    struct Blades_s *bladeStruct = Blades;
    float *bladeBuffer = loadArrayF(RSID_BLADES_BUFFER, 0);
    int *bladeColor = loadArrayI32(RSID_BLADES_BUFFER, 0);

    float now = uptimeMillis() * 0.00004f;

    for ( ; i < bladesCount; i += 1) {
        int offset = drawBlade(bladeStruct, bladeBuffer, bladeColor, brightness, xOffset, now, i);
        bladeBuffer += offset;
        bladeColor += offset;
        bladeStruct ++;
    }

    uploadToBufferObject(NAMED_BladesBuffer);
    drawSimpleMeshRange(NAMED_BladesMesh, 0, State->indexCount);
}

int main(int launchID) {
    gNow = uptimeMillis();

    if (Command->command != 0) {
        Command->command = 0;
        addTap(Command->x, Command->y);
    }

    int width = State->width;
    int height = State->height;

    float x = lerpf(width, 0, State->xOffset);

    float now = time(State->isPreview);
    alpha(1.0f);

    float newB = 1.0f;
    float dawn = State->dawn;
    float morning = State->morning;
    float afternoon = State->afternoon;
    float dusk = State->dusk;

    if (now >= 0.0f && now < dawn) {                    // Draw night
        drawNight(width, height);
        gType = 1;      // draw firefly
        newB = 0.0f;
    } else if (now >= dawn && now <= morning) {         // Draw sunrise
        float half = dawn + (morning - dawn) * 0.5f;

        if (now <= half) {                              // Draw night->sunrise
            drawNight(width, height);
            newB = normf(dawn, half, now);
            alpha(newB);
            drawSunrise(width, height);
            gType = 1;  // draw firefly
        } else {                                        // Draw sunrise->day
            drawSunrise(width, height);
            alpha(normf(half, morning, now));
            drawNoon(width, height);
            int curType = gType;
            gType = 0;  // draw dandelion
            // initialize everything when exchange between firefly and dandelion
            if (curType == 1) {
                debugF("firefly-->dandelion, initParticles", gType);
                initParticles();
            }
        }
    } else if (now > morning && now < afternoon) {      // Draw day
        drawNoon(width, height);
        gType = 0;      // draw dandelion
    } else if (now >= afternoon && now <= dusk) {       // Draw sunset
        float half = afternoon + (dusk - afternoon) * 0.5f;
        if (now <= half) {                              // Draw day->sunset
            drawNoon(width, height);
            newB = normf(afternoon, half, now);
            alpha(newB);
            newB = 1.0f - newB;
            drawSunset(width, height);
            gType = 0;  // draw dandelion
        } else {                                        // Draw sunset->night
            drawSunset(width, height);
            alpha(normf(half, dusk, now));
            drawNight(width, height);
            newB = 0.0f;
            int curType = gType;
            gType = 1;  // draw firefly
            // initialize everything when exchange between firefly and dandelion
            if (curType == 0) {
                debugF("dandelion-->firefly, initParticles", gType);
                initParticles();
                gType = 1;
            }
        }
    } else if (now > dusk) {                            // Draw night
        drawNight(width, height);
        gType = 1;      // draw firefly
        newB = 0.0f;
    }

    bindProgramFragment(NAMED_PFGrass);
    drawBlades(newB, x);
    if (gType == 0) {
        drawDandelion();
    } else if (gType == 1){
        drawFirefly();
    } else {
        debugF("error: unknown type", gType);
    }

    return 30;
}
