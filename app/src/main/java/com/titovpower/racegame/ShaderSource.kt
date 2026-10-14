package com.titovpower.racegame

/**
 * GLSL ES 1.00 source for the scene shader. Per-pixel lighting with:
 * - sky/ground hemisphere ambient and a warm sun,
 * - specular highlights and sky reflections on glossy vertices (car paint, glass),
 * - procedural detail noise for grass and asphalt,
 * - a procedural sky with sun glow and clouds (mode 1),
 * - real-time sun shadows from a depth map (cars), bilinear-filtered,
 * - lighting in linear space with a filmic (ACES) tone curve,
 * - distance fog that fades into the horizon color.
 *
 * Modes: 0 = lit, 1 = sky, 2 = unlit (vertex alpha = opacity; shadows, lit brake lights).
 */
object ShaderSource {
    const val VERTEX = """
uniform mat4 uMvp;
uniform mat4 uModel;
uniform mat4 uLightVP;
attribute vec3 aPos;
attribute vec3 aNormal;
attribute vec4 aColor;
varying vec3 vWorld;
varying vec3 vNormal;
varying vec4 vColor;
varying vec4 vShadow;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = (uModel * vec4(aNormal, 0.0)).xyz;
    vColor = aColor;
    // Look up the shadow map slightly off the surface to avoid self-shadowing acne.
    vShadow = uLightVP * vec4(w.xyz + normalize(vNormal) * 0.05, 1.0);
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

    const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec3 uCamPos;
uniform vec3 uSunDir;
uniform vec3 uFogColor;
uniform vec3 uSkyZenith;
uniform vec3 uSkyHorizon;
uniform vec3 uGroundBounce;
uniform vec3 uTint;
uniform vec2 uFogRange;
uniform float uAlpha;
uniform float uMode;
uniform float uDetail;
uniform float uDetailScale;
uniform sampler2D uShadowMap;
uniform float uShadowTexel;
uniform float uShadowOn;
varying vec3 vWorld;
varying vec3 vNormal;
varying vec4 vColor;
varying vec4 vShadow;

float unpackDepth(vec4 c) {
    return dot(c, vec4(1.0, 1.0 / 255.0, 1.0 / 65025.0, 1.0 / 16581375.0));
}

/** 1 = fully lit by the sun, 0 = in a car's shadow. */
float sunVisibility() {
    if (uShadowOn < 0.5) return 1.0;
    vec3 s = vShadow.xyz / vShadow.w * 0.5 + 0.5;
    float edge = min(min(s.x, s.y), min(1.0 - s.x, 1.0 - s.y));
    if (edge <= 0.0 || s.z >= 1.0) return 1.0;
    float d = s.z - 0.0015;
    // Bilinear-filtered comparison of the four nearest texels: soft, stair-free edges.
    vec2 uv = s.xy / uShadowTexel - 0.5;
    vec2 f = fract(uv);
    vec2 b = (floor(uv) + 0.5) * uShadowTexel;
    float t = uShadowTexel;
    float l00 = d > unpackDepth(texture2D(uShadowMap, b)) ? 0.0 : 1.0;
    float l10 = d > unpackDepth(texture2D(uShadowMap, b + vec2(t, 0.0))) ? 0.0 : 1.0;
    float l01 = d > unpackDepth(texture2D(uShadowMap, b + vec2(0.0, t))) ? 0.0 : 1.0;
    float l11 = d > unpackDepth(texture2D(uShadowMap, b + vec2(t, t))) ? 0.0 : 1.0;
    float lit = mix(mix(l00, l10, f.x), mix(l01, l11, f.x), f.y) * 4.0;
    return mix(1.0, lit * 0.25, smoothstep(0.0, 0.06, edge));
}

vec3 toLinear(vec3 c) { return c * c; }

vec3 filmic(vec3 x) {
    // ACES fit (Narkowicz), then back to display gamma.
    x *= 0.9;
    vec3 c = clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
    return sqrt(c);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

vec3 sky(vec3 dir) {
    float h = max(dir.y, 0.0);
    vec3 c = mix(uSkyHorizon, uSkyZenith, pow(h, 0.5));
    float s = max(dot(dir, uSunDir), 0.0);
    c += vec3(1.0, 0.92, 0.75) * (pow(s, 900.0) * 2.0 + pow(s, 24.0) * 0.22 + pow(s, 4.0) * 0.06);
    return c;
}

void main() {
    vec3 toCam = uCamPos - vWorld;
    float dist = length(toCam);
    vec3 v = toCam / dist;

    if (uMode > 0.5 && uMode < 1.5) {
        vec3 dir = -v;
        vec3 c = sky(dir);
        if (dir.y > 0.0) {
            vec2 uv = dir.xz / (dir.y + 0.08) * 1.2;
            float n = noise(uv) * 0.5 + noise(uv * 2.3 + 7.0) * 0.3 + noise(uv * 5.1 - 3.0) * 0.2;
            float cloud = smoothstep(0.52, 0.78, n) * smoothstep(0.0, 0.2, dir.y);
            vec3 cloudColor = mix(vec3(0.78, 0.82, 0.88), vec3(1.0), smoothstep(0.55, 0.8, n));
            c = mix(c, cloudColor, cloud * 0.9);
        }
        gl_FragColor = vec4(c, 1.0);
        return;
    }

    vec3 base = vColor.rgb * uTint;
    if (uMode > 1.5) {
        gl_FragColor = vec4(base, vColor.a * uAlpha);
        return;
    }

    if (uDetail > 0.0) {
        vec2 p = vWorld.xz * uDetailScale;
        float n = noise(p) * 0.45 + noise(p * 3.1 + 11.0) * 0.35 + noise(p * 9.7 - 5.0) * 0.2;
        float near = clamp(1.0 - dist / 160.0, 0.0, 1.0);
        base *= 1.0 + (n - 0.5) * uDetail * (0.5 + 0.5 * near);
    }

    vec3 n = normalize(vNormal);
    float sunLit = sunVisibility();
    float diffuse = max(dot(n, uSunDir), 0.0) * sunLit;
    vec3 albedo = toLinear(base);
    // Hemisphere ambient: sky from above, grass bounce from below.
    vec3 ambient = toLinear(mix(uGroundBounce, uSkyZenith * 0.75 + 0.3, n.y * 0.5 + 0.5)) * 1.05;
    vec3 sunColor = vec3(1.0, 0.93, 0.8) * 2.6;
    vec3 col = albedo * (ambient + sunColor * diffuse);

    float gloss = vColor.a;
    if (gloss > 0.0) {
        vec3 h = normalize(uSunDir + v);
        float spec = pow(max(dot(n, h), 0.0), 16.0 + gloss * 400.0) * gloss * (2.0 + gloss * 10.0) * sunLit;
        vec3 r = reflect(-v, n);
        float fresnel = 0.04 + 0.96 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
        vec3 env = toLinear(r.y > 0.0 ? sky(r) : uGroundBounce * 0.6);
        col = mix(col, env, gloss * (0.05 + 0.75 * fresnel)) + sunColor * spec;
    }
    col = filmic(col);

    float f = clamp((dist - uFogRange.x) / (uFogRange.y - uFogRange.x), 0.0, 1.0);
    f = f * f * (3.0 - 2.0 * f);
    gl_FragColor = vec4(mix(col, uFogColor, f), uAlpha);
}
"""
}

/** Depth-only pass from the sun: depth packed into RGBA8 (GLES 2 has no portable depth textures). */
object ShadowShaderSource {
    const val VERTEX = """
uniform mat4 uMvp;
attribute vec3 aPos;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

    const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
void main() {
    vec4 e = fract(gl_FragCoord.z * vec4(1.0, 255.0, 65025.0, 16581375.0));
    e -= e.yzww * vec4(1.0 / 255.0, 1.0 / 255.0, 1.0 / 255.0, 0.0);
    gl_FragColor = e;
}
"""
}
