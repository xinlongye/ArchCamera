#include "preview_renderer.h"

#include <GLES3/gl3.h>
#include <android/log.h>

#define LOG_TAG "PreviewRendererNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char* kVertSrc = R"(#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aTexCoord;
uniform int uRotation;
uniform int uMirror;
out vec2 vTexCoord;

vec2 mapTexCoord(vec2 uv) {
    vec2 t = uv;
    if (uRotation == 90) {
        t = vec2(uv.y, 1.0 - uv.x);
    } else if (uRotation == 180) {
        t = vec2(1.0 - uv.x, 1.0 - uv.y);
    } else if (uRotation == 270) {
        t = vec2(1.0 - uv.y, uv.x);
    }
    if (uMirror != 0) {
        t.x = 1.0 - t.x;
    }
    return t;
}

void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTexCoord = mapTexCoord(aTexCoord);
}
)";

constexpr const char* kFragSrc = R"(#version 300 es
precision mediump float;
in vec2 vTexCoord;
uniform sampler2D yTex;
uniform sampler2D uvTex;
out vec4 fragColor;
void main() {
    float y = texture(yTex, vTexCoord).r;
    // NV21 VU plane uploaded as GL_RG: .r = V, .g = U (not NV12 UV).
    vec2 vu = texture(uvTex, vTexCoord).rg - vec2(0.5, 0.5);
    float r = y + 1.402 * vu.x;
    float g = y - 0.344 * vu.y - 0.714 * vu.x;
    float b = y + 1.772 * vu.y;
    fragColor = vec4(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0), clamp(b, 0.0, 1.0), 1.0);
}
)";

constexpr float kQuadVerts[] = {
        // pos      // tex
        -1.f, -1.f, 0.f, 1.f,
         1.f, -1.f, 1.f, 1.f,
        -1.f,  1.f, 0.f, 0.f,
         1.f,  1.f, 1.f, 0.f,
};

void deleteTexture(unsigned int& tex) {
    if (tex != 0) {
        glDeleteTextures(1, &tex);
        tex = 0;
    }
}

}  // namespace

unsigned int PreviewRendererNative::compileShader(unsigned int type, const char* source) {
    unsigned int shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    int ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("Shader compile failed: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

unsigned int PreviewRendererNative::linkProgram(unsigned int vert, unsigned int frag) {
    unsigned int prog = glCreateProgram();
    glAttachShader(prog, vert);
    glAttachShader(prog, frag);
    glLinkProgram(prog);
    int ok = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetProgramInfoLog(prog, sizeof(log), nullptr, log);
        LOGE("Program link failed: %s", log);
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}

void PreviewRendererNative::ensureProgram() {
    if (program != 0) {
        return;
    }
    unsigned int vert = compileShader(GL_VERTEX_SHADER, kVertSrc);
    unsigned int frag = compileShader(GL_FRAGMENT_SHADER, kFragSrc);
    if (vert == 0 || frag == 0) {
        if (vert) glDeleteShader(vert);
        if (frag) glDeleteShader(frag);
        return;
    }
    program = linkProgram(vert, frag);
    glDeleteShader(vert);
    glDeleteShader(frag);
    if (program != 0) {
        yTexLoc = glGetUniformLocation(program, "yTex");
        uvTexLoc = glGetUniformLocation(program, "uvTex");
        rotLoc = glGetUniformLocation(program, "uRotation");
        mirLoc = glGetUniformLocation(program, "uMirror");
    }
}

void PreviewRendererNative::setDisplayTransform(int rotationDeg, bool mirror) {
    int r = rotationDeg % 360;
    if (r != 0 && r != 90 && r != 180 && r != 270) {
        r = 0;
    }
    rotationDegrees = r;
    mirrorHorizontal = mirror;
}

void PreviewRendererNative::ensureQuad() {
    if (vao != 0) {
        return;
    }
    glGenVertexArrays(1, &vao);
    glGenBuffers(1, &vbo);
    glBindVertexArray(vao);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadVerts), kQuadVerts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                          reinterpret_cast<void*>(2 * sizeof(float)));
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void PreviewRendererNative::ensureTextures(int fw, int fh) {
    if (fw <= 0 || fh <= 0) {
        return;
    }
    const int uvW = fw / 2;
    const int uvH = fh / 2;

    if (yTexture == 0) {
        glGenTextures(1, &yTexture);
    }
    glBindTexture(GL_TEXTURE_2D, yTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, fw, fh, 0, GL_RED, GL_UNSIGNED_BYTE, nullptr);

    if (uvTexture == 0) {
        glGenTextures(1, &uvTexture);
    }
    glBindTexture(GL_TEXTURE_2D, uvTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RG8, uvW, uvH, 0, GL_RG, GL_UNSIGNED_BYTE, nullptr);

    glBindTexture(GL_TEXTURE_2D, 0);
}

void PreviewRendererNative::onSurfaceCreated() {
    // GLSurfaceView onPause/onResume destroys the EGL context; old GL names are invalid.
    // Do not glDelete here — resources are already gone with the lost context.
    program = 0;
    yTexture = 0;
    uvTexture = 0;
    vao = 0;
    vbo = 0;
    yTexLoc = -1;
    uvTexLoc = -1;
    rotLoc = -1;
    mirLoc = -1;
    ensureProgram();
    ensureQuad();
    if (frameWidth > 0 && frameHeight > 0) {
        ensureTextures(frameWidth, frameHeight);
    }
}

void PreviewRendererNative::onSurfaceChanged(int vw, int vh) {
    viewWidth = vw;
    viewHeight = vh;
}

void PreviewRendererNative::resize(int fw, int fh) {
    if (fw == frameWidth && fh == frameHeight && yTexture != 0) {
        return;
    }
    frameWidth = fw;
    frameHeight = fh;
    deleteTexture(yTexture);
    deleteTexture(uvTexture);
    if (fw > 0 && fh > 0) {
        ensureTextures(fw, fh);
    }
}

void PreviewRendererNative::clearView() {
    const int vw = viewWidth > 0 ? viewWidth : frameWidth;
    const int vh = viewHeight > 0 ? viewHeight : frameHeight;
    if (vw <= 0 || vh <= 0) {
        return;
    }
    glViewport(0, 0, vw, vh);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
}

void PreviewRendererNative::draw(const uint8_t* nv21, int fw, int fh) {
    if (program == 0 || nv21 == nullptr || fw <= 0 || fh <= 0) {
        return;
    }
    if (fw != frameWidth || fh != frameHeight) {
        resize(fw, fh);
    }
    ensureTextures(fw, fh);

    const int ySize = fw * fh;
    const int uvW = fw / 2;
    const int uvH = fh / 2;
    const uint8_t* yPlane = nv21;
    const uint8_t* vuPlane = nv21 + ySize;

    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, yTexture);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, fw, fh, GL_RED, GL_UNSIGNED_BYTE, yPlane);

    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, uvTexture);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, uvW, uvH, GL_RG, GL_UNSIGNED_BYTE, vuPlane);

    glViewport(0, 0, viewWidth > 0 ? viewWidth : fw, viewHeight > 0 ? viewHeight : fh);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(program);
    if (yTexLoc >= 0) {
        glUniform1i(yTexLoc, 0);
    }
    if (uvTexLoc >= 0) {
        glUniform1i(uvTexLoc, 1);
    }
    if (rotLoc >= 0) {
        glUniform1i(rotLoc, rotationDegrees);
    }
    if (mirLoc >= 0) {
        glUniform1i(mirLoc, mirrorHorizontal ? 1 : 0);
    }

    glBindVertexArray(vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);
}

void PreviewRendererNative::release() {
    if (vbo != 0) {
        glDeleteBuffers(1, &vbo);
        vbo = 0;
    }
    if (vao != 0) {
        glDeleteVertexArrays(1, &vao);
        vao = 0;
    }
    deleteTexture(yTexture);
    deleteTexture(uvTexture);
    if (program != 0) {
        glDeleteProgram(program);
        program = 0;
    }
}
