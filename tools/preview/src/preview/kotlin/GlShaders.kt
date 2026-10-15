package com.titovpower.racegame

/**
 * GLSL ES 3.00 shaders of the OpenGL renderer (also run by the WebGL 2 screenshot preview).
 *
 * Lighting is physically based and in physical units scaled by the camera exposure: a sun
 * (GGX specular, Lambert diffuse) with soft shadows from two shadow-map cascades, plus image-based
 * light from the HDR sky (spherical harmonics for diffuse, the cubemap's blurred mips for
 * reflections), a clear-coat layer for car paint, soft contact shadows under the cars, height fog
 * colored by the sky, and ACES filmic tone mapping.
 */
object GlShaders {
    private const val COMMON = """
vec3 toneMap(vec3 x) {
    // ACES fit (Narkowicz), then sRGB.
    vec3 c = clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
    return pow(c, vec3(1.0 / 2.2));
}
"""

    const val VERTEX = """#version 300 es
uniform mat4 uViewProj;
uniform mat4 uModel;
uniform mat4 uLightVP0;
uniform mat4 uLightVP1;
uniform vec2 uNormalBias;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec4 aColor;
layout(location = 3) in vec2 aUv;
out vec3 vWorld;
out vec3 vNormal;
out vec4 vColor;
out vec2 vUv;
out vec4 vShadow0;
out vec4 vShadow1;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = mat3(uModel) * aNormal;
    vColor = aColor;
    vUv = aUv;
    // Shadow lookups slightly off the surface, against self-shadowing acne.
    vec3 n = normalize(vNormal);
    vShadow0 = uLightVP0 * vec4(w.xyz + n * uNormalBias.x, 1.0);
    vShadow1 = uLightVP1 * vec4(w.xyz + n * uNormalBias.y, 1.0);
    gl_Position = uViewProj * w;
}
"""

    val FRAGMENT = """#version 300 es
precision highp float;
precision highp sampler2DShadow;
in vec3 vWorld;
in vec3 vNormal;
in vec4 vColor;
in vec2 vUv;
in vec4 vShadow0;
in vec4 vShadow1;
uniform vec4 uBaseColor;
uniform float uMetallic;
uniform float uRoughness;
uniform float uClearcoat;
uniform float uClearcoatRoughness;
uniform vec3 uEmissive;
uniform float uHasTex;
uniform sampler2D uTex;
uniform samplerCube uSky;
uniform float uSkyMaxLod;
uniform float uSkyScale;
uniform vec3 uSh[9];
uniform vec3 uSunDir;
uniform vec3 uSunColor;
uniform vec3 uCamPos;
uniform sampler2DShadow uShadow0;
uniform sampler2DShadow uShadow1;
uniform vec2 uShadowTexel;
uniform float uShadowOn;
uniform vec4 uFog; // start distance, density, height falloff, max opacity
uniform vec4 uCars[${MAX_CARS}]; // x, z, sin(yaw), cos(yaw) for contact shadows
uniform int uCarCount;
out vec4 fragColor;
$COMMON
const float PI = 3.14159265;

float pcf(sampler2DShadow map, vec4 p, float texel) {
    vec3 s = p.xyz / p.w * 0.5 + 0.5;
    float o = texel * 1.2;
    float v = texture(map, s) * 2.0;
    v += texture(map, s + vec3(o, o, 0.0));
    v += texture(map, s + vec3(-o, o, 0.0));
    v += texture(map, s + vec3(o, -o, 0.0));
    v += texture(map, s + vec3(-o, -o, 0.0));
    return v / 6.0;
}

float inside(vec4 p, float margin) {
    vec3 s = p.xyz / p.w * 0.5 + 0.5;
    vec3 e = min(s, 1.0 - s);
    return step(margin, min(min(e.x, e.y), e.z));
}

/** 1 = in sunlight; the near cascade where it covers, else the far one. */
float sunVisibility() {
    if (uShadowOn < 0.5) return 1.0;
    if (inside(vShadow0, 0.02) > 0.5) return pcf(uShadow0, vShadow0, uShadowTexel.x);
    if (inside(vShadow1, 0.01) > 0.5) return pcf(uShadow1, vShadow1, uShadowTexel.y);
    return 1.0;
}

/** Ambient occlusion on the ground under and around the cars. */
float contactShadow(vec3 n) {
    if (n.y < 0.5 || vWorld.y > 0.8) return 1.0;
    float ao = 1.0;
    for (int i = 0; i < ${MAX_CARS}; i++) {
        if (i >= uCarCount) break;
        vec2 d = vWorld.xz - uCars[i].xy;
        vec2 l = vec2(d.x * uCars[i].w - d.y * uCars[i].z, d.x * uCars[i].z + d.y * uCars[i].w);
        vec2 q = max(abs(l) - vec2(0.75, 1.9), 0.0);
        float k = 1.0 - smoothstep(0.0, 0.9, length(q));
        ao *= 1.0 - 0.7 * k;
    }
    return ao;
}

vec3 irradiance(vec3 n) {
    return max(uSh[0]
        + uSh[1] * n.y + uSh[2] * n.z + uSh[3] * n.x
        + uSh[4] * (n.y * n.x) + uSh[5] * (n.y * n.z) + uSh[6] * (3.0 * n.z * n.z - 1.0)
        + uSh[7] * (n.z * n.x) + uSh[8] * (n.x * n.x - n.y * n.y), 0.0);
}

vec3 prefiltered(vec3 r, float roughness) {
    float lod = uSkyMaxLod * roughness * (2.0 - roughness);
    return textureLod(uSky, r, lod).rgb;
}

/** Split-sum environment BRDF, analytic fit (Karis). */
vec2 envBrdf(float NoV, float roughness) {
    vec4 r = roughness * vec4(-1.0, -0.0275, -0.572, 0.022) + vec4(1.0, 0.0425, 1.04, -0.04);
    float a = min(r.x * r.x, exp2(-9.28 * NoV)) * r.x + r.y;
    return vec2(-1.04, 1.04) * a + r.zw;
}

float ggx(float NoH, float a) {
    float a2 = a * a;
    float d = NoH * NoH * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d);
}

float smith(float NoV, float NoL, float a) {
    float a2 = a * a;
    float gv = NoL * sqrt(NoV * NoV * (1.0 - a2) + a2);
    float gl = NoV * sqrt(NoL * NoL * (1.0 - a2) + a2);
    return 0.5 / max(gv + gl, 1e-5);
}

vec3 fresnel(vec3 f0, float VoH) {
    return f0 + (1.0 - f0) * pow(1.0 - VoH, 5.0);
}

void main() {
    vec4 base = uBaseColor * vColor;
    if (uHasTex > 0.5) base *= texture(uTex, vUv);
    vec3 toCam = uCamPos - vWorld;
    float dist = length(toCam);
    vec3 v = toCam / dist;
    vec3 n = normalize(vNormal);
    // Back faces of double-sided surfaces: turn the normal to the viewer, unless it already faces
    // them (grass and leaf cards carry upward/outward normals so both sides light alike).
    if (!gl_FrontFacing && dot(n, v) < 0.0) n = -n;
    float NoV = max(dot(n, v), 1e-3);

    float rough = clamp(uRoughness, 0.045, 1.0);
    float a = rough * rough;
    vec3 diffuseColor = base.rgb * (1.0 - uMetallic);
    vec3 f0 = mix(vec3(0.04), base.rgb, uMetallic);

    // Sun.
    vec3 l = uSunDir;
    vec3 h = normalize(l + v);
    float NoL = max(dot(n, l), 0.0);
    float NoH = max(dot(n, h), 0.0);
    float VoH = max(dot(v, h), 0.0);
    float sun = sunVisibility() * NoL;
    vec3 spec = ggx(NoH, a) * smith(NoV, NoL, a) * fresnel(f0, VoH);
    vec3 col = (diffuseColor / PI + spec) * sun * uSunColor;

    // Sky light.
    float ao = contactShadow(n);
    vec3 r = reflect(-v, n);
    vec2 ab = envBrdf(NoV, rough);
    float horizon = clamp(1.0 + dot(r, n), 0.0, 1.0);
    vec3 ibl = diffuseColor * irradiance(n) + prefiltered(r, rough) * (f0 * ab.x + ab.y) * horizon * horizon;
    col += ibl * uSkyScale * ao;

    // Clear coat (car paint): a smooth varnish layer on top.
    if (uClearcoat > 0.0) {
        float ca = max(uClearcoatRoughness, 0.045);
        float fc = (0.04 + 0.96 * pow(1.0 - NoV, 5.0)) * uClearcoat;
        float ccSpec = ggx(NoH, ca * ca) * 0.25 / max(VoH * VoH, 1e-3) * (0.04 + 0.96 * pow(1.0 - VoH, 5.0)) * uClearcoat;
        col = col * (1.0 - fc) + ccSpec * sun * uSunColor + prefiltered(r, ca) * fc * uSkyScale * ao * horizon;
    }
    col += uEmissive;

    // Height fog, colored like the sky towards the horizon.
    float d = max(dist - uFog.x, 0.0);
    float dy = vWorld.y - uCamPos.y;
    float hf = uFog.z;
    float heightTerm = abs(dy) > 0.01
        ? (exp(-hf * uCamPos.y) - exp(-hf * vWorld.y)) / (hf * dy)
        : exp(-hf * uCamPos.y);
    float fog = min(1.0 - exp(-uFog.y * d * heightTerm), uFog.w);
    vec3 fogDir = normalize(vec3(-v.x, max(-v.y, 0.0) + 0.04, -v.z));
    vec3 fogColor = textureLod(uSky, fogDir, uSkyMaxLod - 3.0).rgb * uSkyScale;
    col = mix(col, fogColor, fog);

    fragColor = vec4(toneMap(col), base.a);
}
"""

    /** Sky: a full-screen triangle behind everything, looking up the cubemap per pixel. */
    const val SKY_VERTEX = """#version 300 es
const vec2 P[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
out vec2 vNdc;
void main() {
    vNdc = P[gl_VertexID];
    gl_Position = vec4(vNdc, 1.0, 1.0);
}
"""

    val SKY_FRAGMENT = """#version 300 es
precision highp float;
in vec2 vNdc;
uniform mat4 uInvViewProj;
uniform samplerCube uSky;
uniform float uSkyScale;
out vec4 fragColor;
$COMMON
void main() {
    vec4 p = uInvViewProj * vec4(vNdc, 1.0, 1.0);
    vec3 dir = normalize(p.xyz / p.w);
    fragColor = vec4(toneMap(texture(uSky, dir).rgb * uSkyScale), 1.0);
}
"""

    /** Depth only, from the sun. */
    const val SHADOW_VERTEX = """#version 300 es
uniform mat4 uLightVP;
uniform mat4 uModel;
layout(location = 0) in vec3 aPos;
void main() {
    gl_Position = uLightVP * uModel * vec4(aPos, 1.0);
}
"""

    const val SHADOW_FRAGMENT = """#version 300 es
precision mediump float;
void main() {}
"""

    const val MAX_CARS = 12
}
