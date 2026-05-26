#pragma once

#include <cstdint>

struct PreviewRendererNative {
    int frameWidth = 0;
    int frameHeight = 0;
    int viewWidth = 0;
    int viewHeight = 0;

    unsigned int program = 0;
    unsigned int yTexture = 0;
    unsigned int uvTexture = 0;
    unsigned int vao = 0;
    unsigned int vbo = 0;

    int yTexLoc = -1;
    int uvTexLoc = -1;
    int rotLoc = -1;
    int mirLoc = -1;

    int rotationDegrees = 0;
    bool mirrorHorizontal = false;

    void onSurfaceCreated();
    void setDisplayTransform(int rotationDegrees, bool mirrorHorizontal);
    void onSurfaceChanged(int viewWidth, int viewHeight);
    void resize(int frameWidth, int frameHeight);
    void draw(const uint8_t* nv21, int frameWidth, int frameHeight);
    void clearView();
    void release();

private:
    void ensureProgram();
    void ensureTextures(int frameWidth, int frameHeight);
    void ensureQuad();
    static unsigned int compileShader(unsigned int type, const char* source);
    static unsigned int linkProgram(unsigned int vert, unsigned int frag);
};
