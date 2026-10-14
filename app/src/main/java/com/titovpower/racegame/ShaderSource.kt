package com.titovpower.racegame

/**
 * GLSL ES 1.00 source for the scene shader. Per-pixel lighting with:
 * - sky/ground hemisphere ambient and a warm sun,
 * - specular highlights and sky reflections on glossy vertices (car paint, glass),
 * - procedural detail noise for grass and asphalt,
 * - a procedural sky with sun glow and clouds (mode 1),
 * - distance fog that fades into the horizon color.
 *
 * Modes: 0 = lit, 1 = sky, 2 = unlit (vertex alpha = opacity; shadows, lit brake lights).
 */
object ShaderSource {
    const val VERTEX = """
uniform mat4 uMvp;
uniform mat4 uModel;
attribute vec3 aPos;
attribute vec3 aNormal;
attribute vec4 aColor;
varying vec3 vWorld;
varying vec3 vNormal;
varying vec4 vColor;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = (uModel * vec4(aNormal, 0.0)).xyz;
    vColor = aColor;
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
varying vec3 vWorld;
varying vec3 vNormal;
varying vec4 vColor;

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
    float diffuse = max(dot(n, uSunDir), 0.0);
    vec3 ambient = mix(uGroundBounce, uSkyZenith * 0.8 + 0.25, n.y * 0.5 + 0.5);
    vec3 col = base * (ambient * 0.85 + vec3(1.0, 0.96, 0.88) * diffuse * 0.72);

    float gloss = vColor.a;
    if (gloss > 0.0) {
        vec3 h = normalize(uSunDir + v);
        float spec = pow(max(dot(n, h), 0.0), 16.0 + gloss * 240.0) * gloss * 1.4;
        vec3 r = reflect(-v, n);
        float fresnel = pow(1.0 - max(dot(n, v), 0.0), 4.0);
        vec3 env = r.y > 0.0 ? sky(r) : uGroundBounce * 0.7;
        col = mix(col, env, gloss * (0.06 + 0.55 * fresnel)) + vec3(spec);
    }

    float f = clamp((dist - uFogRange.x) / (uFogRange.y - uFogRange.x), 0.0, 1.0);
    f = f * f * (3.0 - 2.0 * f);
    gl_FragColor = vec4(mix(col, uFogColor, f), uAlpha);
}
"""
}
