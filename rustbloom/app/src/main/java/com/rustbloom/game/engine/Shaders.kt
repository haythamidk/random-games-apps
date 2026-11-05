package com.rustbloom.game.engine

/**
 * GLSL ES 3.00 sources. Colors in vertex data are sRGB and converted to linear in the vertex stage;
 * lighting happens in linear space and is tonemapped + gamma corrected at the end of each fragment shader.
 */
object Shaders {

    private const val HEADER = "#version 300 es\nprecision highp float;\n"
    private const val FHEADER = "#version 300 es\nprecision highp float;\nprecision highp sampler2DShadow;\n"

    /** Shared lighting / fog / shadow code for fragment shaders. */
    private const val LIGHTING = """
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform vec3 u_skyAmb;
uniform vec3 u_groundAmb;
uniform vec3 u_fogColor;
uniform vec3 u_fogSunColor;
uniform float u_fogDensity;
uniform float u_fogHeightFalloff;
uniform vec3 u_camPos;
uniform float u_exposure;
uniform int u_shadowMode;
uniform sampler2DShadow u_shadowMap;
uniform float u_shadowTexel;
uniform vec4 u_lights[4];
uniform vec3 u_lightColors[4];
uniform float u_flash;
uniform float u_viewDist;

float shadowFactor(vec4 sp, float ndl) {
    if (u_shadowMode == 0) return 1.0;
    vec3 p = sp.xyz / sp.w;
    p = p * 0.5 + 0.5;
    if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return 1.0;
    float bias = 0.0008 + 0.0025 * (1.0 - ndl);
    float z = p.z - bias;
    float s;
    if (u_shadowMode == 1) {
        s = texture(u_shadowMap, vec3(p.xy, z));
    } else {
        float t = u_shadowTexel;
        s  = texture(u_shadowMap, vec3(p.xy + vec2(-t, -t) * 0.75, z));
        s += texture(u_shadowMap, vec3(p.xy + vec2( t, -t) * 0.75, z));
        s += texture(u_shadowMap, vec3(p.xy + vec2(-t,  t) * 0.75, z));
        s += texture(u_shadowMap, vec3(p.xy + vec2( t,  t) * 0.75, z));
        s *= 0.25;
    }
    // fade shadows at the edge of the shadow volume
    vec2 e = abs(p.xy - 0.5) * 2.0;
    float fade = smoothstep(0.82, 1.0, max(e.x, e.y));
    return mix(s, 1.0, fade);
}

vec3 pointLights(vec3 wp, vec3 n) {
    vec3 acc = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec4 L = u_lights[i];
        if (L.w <= 0.0) continue;
        vec3 d = L.xyz - wp;
        float dist = length(d);
        float att = clamp(1.0 - dist / L.w, 0.0, 1.0);
        att *= att;
        float ndl = max(dot(n, d / max(dist, 0.001)), 0.0) * 0.8 + 0.2;
        acc += u_lightColors[i] * att * ndl;
    }
    return acc;
}

vec3 applyFog(vec3 col, vec3 wp) {
    vec3 v = wp - u_camPos;
    float dist = length(v);
    vec3 vd = v / max(dist, 0.001);
    float h = max(wp.y, -5.0);
    float heightFactor = exp(-max(h - 5.0, 0.0) * u_fogHeightFalloff);
    float fog = 1.0 - exp(-dist * u_fogDensity * mix(0.35, 1.0, heightFactor));
    // fade out completely before the edge of the streamed world
    fog = max(fog, smoothstep(u_viewDist * 0.55, u_viewDist * 0.97, dist));
    float sunAmt = pow(max(dot(vd, u_sunDir), 0.0), 8.0);
    vec3 fc = mix(u_fogColor, u_fogSunColor, sunAmt);
    return mix(col, fc, clamp(fog, 0.0, 1.0));
}

vec3 tonemap(vec3 c) {
    c *= u_exposure;
    c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14);
    return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
}
"""

    // ---------------- Lit mesh (static structures, creatures, build pieces) ----------------
    val litVS = HEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_color;
uniform mat4 u_model;
uniform mat4 u_vp;
uniform mat4 u_lightVP;
uniform vec4 u_tint;
out vec3 v_world;
out vec3 v_normal;
out vec4 v_color;
out vec4 v_shadowPos;
void main() {
    vec4 wp = u_model * vec4(a_pos, 1.0);
    v_world = wp.xyz;
    v_normal = mat3(u_model) * a_normal;
    vec3 c = pow(a_color.rgb, vec3(2.2)) * mix(vec3(1.0), u_tint.rgb, u_tint.a);
    v_color = vec4(c, a_color.a);
    v_shadowPos = u_lightVP * wp;
    gl_Position = u_vp * wp;
}
"""

    val litFS = FHEADER + LIGHTING + """
in vec3 v_world;
in vec3 v_normal;
in vec4 v_color;
in vec4 v_shadowPos;
uniform vec3 u_emissiveColor;
uniform float u_emissiveScale;
uniform float u_specular;
uniform float u_hitFlash;
uniform float u_alpha;
out vec4 fragColor;
void main() {
    vec3 n = normalize(v_normal);
    if (!gl_FrontFacing) n = -n;
    float ndl = max(dot(n, u_sunDir), 0.0);
    float sh = shadowFactor(v_shadowPos, ndl);
    vec3 albedo = v_color.rgb;
    vec3 amb = mix(u_groundAmb, u_skyAmb, n.y * 0.5 + 0.5);
    vec3 col = albedo * (amb + u_sunColor * ndl * sh + pointLights(v_world, n));
    vec3 vdir = normalize(u_camPos - v_world);
    vec3 hv = normalize(u_sunDir + vdir);
    float spec = pow(max(dot(n, hv), 0.0), 48.0) * u_specular * sh;
    col += u_sunColor * spec;
    float fres = pow(1.0 - max(dot(n, vdir), 0.0), 4.0);
    col += u_skyAmb * fres * u_specular * 0.6;
    col += albedo * v_color.a * u_emissiveColor * u_emissiveScale * 6.0;
    col += vec3(1.0, 0.35, 0.2) * u_hitFlash;
    col += albedo * u_flash * 0.6;
    col = applyFog(col, v_world);
    fragColor = vec4(tonemap(col), u_alpha);
}
"""

    // ---------------- Instanced vegetation / props ----------------
    val instVS = HEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_color;
layout(location=3) in vec4 a_inst0;
layout(location=4) in vec4 a_inst1;
uniform mat4 u_vp;
uniform mat4 u_lightVP;
uniform float u_time;
uniform float u_wind;
out vec3 v_world;
out vec3 v_normal;
out vec4 v_color;
out vec4 v_shadowPos;
void main() {
    float c = cos(a_inst0.w);
    float s = sin(a_inst0.w);
    float sc = a_inst1.x;
    vec3 p = a_pos * sc;
    vec3 rp = vec3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z);
    vec3 wp = rp + a_inst0.xyz;
    float sway = a_inst1.z * max(a_pos.y, 0.0) * sc;
    float ph = a_inst0.x * 0.37 + a_inst0.z * 0.21;
    float w = sin(u_time * 1.7 + ph) * 0.6 + sin(u_time * 3.1 + ph * 1.7) * 0.25;
    wp.x += w * sway * 0.04 * (0.4 + u_wind);
    wp.z += cos(u_time * 1.3 + ph) * sway * 0.025 * (0.4 + u_wind);
    v_world = wp;
    vec3 n = a_normal;
    v_normal = vec3(c * n.x + s * n.z, n.y, -s * n.x + c * n.z);
    vec3 col = pow(a_color.rgb, vec3(2.2));
    float tint = a_inst1.y;
    col *= mix(vec3(0.82, 0.9, 0.75), vec3(1.12, 1.05, 0.92), tint);
    v_color = vec4(col, a_color.a);
    v_shadowPos = u_lightVP * vec4(wp, 1.0);
    gl_Position = u_vp * vec4(wp, 1.0);
}
"""

    val instFS = FHEADER + LIGHTING + """
in vec3 v_world;
in vec3 v_normal;
in vec4 v_color;
in vec4 v_shadowPos;
uniform float u_translucency;
out vec4 fragColor;
void main() {
    vec3 n = normalize(v_normal);
    if (!gl_FrontFacing) n = -n;
    float ndlRaw = dot(n, u_sunDir);
    float ndl = max(ndlRaw, 0.0);
    float sh = shadowFactor(v_shadowPos, ndl);
    float wrap = max(ndlRaw * 0.6 + 0.4, 0.0);
    float diff = mix(ndl, wrap, u_translucency);
    vec3 albedo = v_color.rgb;
    vec3 amb = mix(u_groundAmb, u_skyAmb, n.y * 0.5 + 0.5);
    vec3 col = albedo * (amb + u_sunColor * diff * sh + pointLights(v_world, n));
    vec3 vdir = normalize(u_camPos - v_world);
    float back = pow(max(dot(-vdir, u_sunDir), 0.0), 4.0) * u_translucency * sh;
    col += albedo * u_sunColor * back * 0.6;
    col += albedo * v_color.a * 3.0;
    col += albedo * u_flash * 0.6;
    col = applyFog(col, v_world);
    fragColor = vec4(tonemap(col), 1.0);
}
"""

    // ---------------- Terrain ----------------
    val terrainVS = HEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_color;
uniform mat4 u_vp;
uniform mat4 u_lightVP;
out vec3 v_world;
out vec3 v_normal;
out vec4 v_color;
out vec4 v_shadowPos;
void main() {
    v_world = a_pos;
    v_normal = a_normal;
    v_color = vec4(pow(a_color.rgb, vec3(2.2)), a_color.a);
    v_shadowPos = u_lightVP * vec4(a_pos, 1.0);
    gl_Position = u_vp * vec4(a_pos, 1.0);
}
"""

    val terrainFS = FHEADER + LIGHTING + """
in vec3 v_world;
in vec3 v_normal;
in vec4 v_color;
in vec4 v_shadowPos;
uniform sampler2D u_noise;
uniform int u_detail;
uniform float u_wetness;
uniform float u_snowCover;
out vec4 fragColor;
void main() {
    vec3 n = normalize(v_normal);
    vec3 albedo = v_color.rgb;
    float slope = 1.0 - n.y;
    vec3 rock = vec3(0.16, 0.15, 0.14) + v_color.a * vec3(0.05, 0.02, 0.0);
    if (u_detail > 0) {
        vec4 nz = texture(u_noise, v_world.xz * 0.045);
        vec4 nz2 = texture(u_noise, v_world.xz * 0.31);
        float d = (nz.r - 0.5) * 0.55 + (nz2.g - 0.5) * 0.45;
        albedo *= 1.0 + d * 0.55;
        rock *= 0.8 + nz2.b * 0.5 + (nz.a - 0.5) * 0.3;
        // micro normal perturbation
        n = normalize(n + vec3(nz2.r - 0.5, 0.0, nz2.g - 0.5) * 0.35);
    }
    float rockAmt = smoothstep(0.28, 0.5, slope);
    albedo = mix(albedo, rock, rockAmt);
    // snow on flat high ground or in snowy weather
    float snow = clamp(u_snowCover * (1.0 - smoothstep(0.3, 0.55, slope)), 0.0, 1.0);
    albedo = mix(albedo, vec3(0.85, 0.88, 0.92), snow);
    // wetness darkens
    albedo *= 1.0 - u_wetness * 0.35;
    // underwater sand/darkening
    float under = smoothstep(0.0, -4.0, v_world.y);
    albedo = mix(albedo, albedo * vec3(0.45, 0.6, 0.62), under);

    float ndl = max(dot(n, u_sunDir), 0.0);
    float sh = shadowFactor(v_shadowPos, ndl);
    vec3 amb = mix(u_groundAmb, u_skyAmb, n.y * 0.5 + 0.5);
    vec3 col = albedo * (amb + u_sunColor * ndl * sh + pointLights(v_world, n));
    if (u_wetness > 0.0) {
        vec3 vdir = normalize(u_camPos - v_world);
        vec3 hv = normalize(u_sunDir + vdir);
        col += u_sunColor * pow(max(dot(n, hv), 0.0), 60.0) * u_wetness * 0.5 * sh;
    }
    col += albedo * u_flash * 0.6;
    col = applyFog(col, v_world);
    fragColor = vec4(tonemap(col), 1.0);
}
"""

    // ---------------- Water ----------------
    val waterVS = HEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_color;
uniform mat4 u_vp;
uniform float u_time;
out vec3 v_world;
out float v_depth;
void main() {
    vec3 p = a_pos;
    p.y += sin(p.x * 0.15 + u_time * 1.1) * 0.08 + cos(p.z * 0.12 + u_time * 0.9) * 0.08;
    v_world = p;
    v_depth = a_color.r;
    gl_Position = u_vp * vec4(p, 1.0);
}
"""

    val waterFS = FHEADER + LIGHTING + """
in vec3 v_world;
in float v_depth;
uniform sampler2D u_noise;
uniform float u_time;
uniform vec3 u_skyHorizon;
uniform vec3 u_skyZenith;
uniform int u_waterQuality;
uniform float u_rain;
out vec4 fragColor;
void main() {
    vec2 uv = v_world.xz;
    vec3 n;
    if (u_waterQuality > 0) {
        vec4 a = texture(u_noise, uv * 0.03 + vec2(u_time * 0.011, u_time * 0.007));
        vec4 b = texture(u_noise, uv * 0.07 - vec2(u_time * 0.013, -u_time * 0.009));
        vec4 c = texture(u_noise, uv * 0.21 + vec2(u_time * 0.03, u_time * 0.02));
        vec2 d = (a.rg - 0.5) * 0.9 + (b.ba - 0.5) * 0.7 + (c.rg - 0.5) * (0.35 + u_rain * 0.8);
        n = normalize(vec3(d.x, 1.6, d.y));
    } else {
        n = vec3(0.0, 1.0, 0.0);
    }
    vec3 vdir = normalize(u_camPos - v_world);
    float fres = 0.02 + 0.98 * pow(1.0 - max(dot(n, vdir), 0.0), 5.0);
    vec3 r = reflect(-vdir, n);
    vec3 skyCol = mix(u_skyHorizon, u_skyZenith, clamp(r.y, 0.0, 1.0));
    float depth = clamp(v_depth / 8.0, 0.0, 1.0);
    vec3 shallow = vec3(0.10, 0.32, 0.30);
    vec3 deep = vec3(0.01, 0.06, 0.10);
    vec3 body = mix(shallow, deep, depth) * (u_skyAmb * 1.4 + u_sunColor * 0.35);
    vec3 col = mix(body, skyCol, fres);
    vec3 hv = normalize(u_sunDir + vdir);
    col += u_sunColor * pow(max(dot(n, hv), 0.0), 220.0) * 3.0;
    col += pointLights(v_world, vec3(0.0, 1.0, 0.0)) * 0.25;
    float foam = smoothstep(0.6, 0.0, v_depth);
    col = mix(col, u_skyAmb + u_sunColor * 0.5, foam * 0.35);
    float alpha = clamp(0.55 + depth * 0.4 + fres * 0.3, 0.0, 0.97);
    alpha = mix(alpha, 0.35, foam);
    col += u_flash * 0.4;
    col = applyFog(col, v_world);
    fragColor = vec4(tonemap(col), alpha);
}
"""

    // ---------------- Sky ----------------
    val skyVS = HEADER + """
layout(location=0) in vec3 a_pos;
uniform mat4 u_invViewProj;
out vec3 v_dir;
void main() {
    vec4 p = vec4(a_pos.xy, 1.0, 1.0);
    vec4 w = u_invViewProj * p;
    v_dir = w.xyz / w.w;
    gl_Position = vec4(a_pos.xy, 0.99999, 1.0);
}
"""

    val skyFS = FHEADER + """
in vec3 v_dir;
uniform vec3 u_camPos;
uniform vec3 u_sunDir;
uniform vec3 u_moonDir;
uniform vec3 u_skyZenith;
uniform vec3 u_skyHorizon;
uniform vec3 u_sunColor;
uniform float u_night;
uniform float u_cloudCover;
uniform float u_time;
uniform float u_exposure;
uniform float u_flash;
uniform sampler2D u_noise;
uniform int u_clouds;
out vec4 fragColor;
float hash(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
void main() {
    vec3 d = normalize(v_dir - u_camPos);
    float h = d.y;
    vec3 col = mix(u_skyHorizon, u_skyZenith, pow(clamp(h, 0.0, 1.0), 0.55));
    if (h < 0.0) col = mix(u_skyHorizon, u_skyHorizon * 0.45, clamp(-h * 3.0, 0.0, 1.0));
    float sd = max(dot(d, u_sunDir), 0.0);
    col += u_sunColor * pow(sd, 12.0) * 0.35;
    col += u_sunColor * pow(sd, 900.0) * 30.0 * (1.0 - u_cloudCover * 0.8);
    float md = max(dot(d, u_moonDir), 0.0);
    col += vec3(0.6, 0.7, 0.9) * pow(md, 1600.0) * 6.0 * u_night;
    col += vec3(0.2, 0.25, 0.35) * pow(md, 30.0) * 0.08 * u_night;
    if (u_night > 0.01 && h > 0.0) {
        vec3 sp = floor(d * 380.0);
        float st = hash(sp);
        float star = step(0.9965, st) * (0.5 + 0.5 * sin(u_time * 2.0 + st * 100.0));
        col += vec3(star) * u_night * (1.0 - u_cloudCover) * 1.4;
    }
    if (u_clouds > 0 && h > 0.0) {
        vec2 cuv = d.xz / (h + 0.12) * 0.35 + vec2(u_time * 0.004, u_time * 0.002);
        float c1 = texture(u_noise, cuv).r;
        float c2 = texture(u_noise, cuv * 2.7 + 0.3).g;
        float c = c1 * 0.65 + c2 * 0.35;
        float thr = mix(0.62, 0.28, u_cloudCover);
        float cloud = smoothstep(thr, thr + 0.22, c) * smoothstep(0.0, 0.18, h);
        vec3 cloudLit = mix(u_skyHorizon * 1.05, u_sunColor * 0.8 + u_skyZenith * 0.5, 0.5 + 0.5 * sd);
        cloudLit = mix(cloudLit, vec3(0.32, 0.34, 0.38) * (1.0 - u_night * 0.85), u_cloudCover * 0.7);
        col = mix(col, cloudLit, cloud * 0.9);
    }
    col += vec3(0.6, 0.65, 0.8) * u_flash;
    col *= u_exposure;
    col = (col * (2.51 * col + 0.03)) / (col * (2.43 * col + 0.59) + 0.14);
    fragColor = vec4(pow(clamp(col, 0.0, 1.0), vec3(1.0 / 2.2)), 1.0);
}
"""

    // ---------------- Shadow depth ----------------
    val depthVS = HEADER + """
layout(location=0) in vec3 a_pos;
uniform mat4 u_model;
uniform mat4 u_lightVP;
void main() { gl_Position = u_lightVP * u_model * vec4(a_pos, 1.0); }
"""

    val depthInstVS = HEADER + """
layout(location=0) in vec3 a_pos;
layout(location=3) in vec4 a_inst0;
layout(location=4) in vec4 a_inst1;
uniform mat4 u_lightVP;
void main() {
    float c = cos(a_inst0.w);
    float s = sin(a_inst0.w);
    vec3 p = a_pos * a_inst1.x;
    vec3 wp = vec3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z) + a_inst0.xyz;
    gl_Position = u_lightVP * vec4(wp, 1.0);
}
"""

    val depthFS = "#version 300 es\nprecision mediump float;\nout vec4 fragColor;\nvoid main() { fragColor = vec4(1.0); }\n"

    // ---------------- Particles (rain / snow / sparks) ----------------
    val particleVS = HEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_color;
uniform mat4 u_vp;
uniform vec3 u_camPos;
uniform float u_time;
uniform float u_mode;
uniform vec3 u_windDir;
uniform float u_pointScale;
out float v_alpha;
void main() {
    float box = 36.0;
    float hgt = 24.0;
    vec3 seed = a_pos;
    float speed = mix(14.0, 2.2, u_mode);
    float y = mod(seed.y * hgt - u_time * speed * (0.8 + seed.x * 0.4), hgt);
    vec3 drift = u_windDir * (hgt - y) * mix(0.25, 0.6, u_mode);
    if (u_mode > 0.5) drift += vec3(sin(u_time + seed.z * 20.0), 0.0, cos(u_time * 0.8 + seed.x * 20.0)) * 0.6;
    vec3 base = vec3(seed.x * box, y, seed.z * box);
    base.xz = mod(base.xz - u_camPos.xz + drift.xz, box) - box * 0.5;
    vec3 wp = vec3(base.x + u_camPos.x, u_camPos.y - hgt * 0.4 + y, base.z + u_camPos.z);
    // streak end for rain: second vertex of each line uses a_normal.x = 1
    wp -= (vec3(0.0, -1.0, 0.0) * speed + u_windDir * 3.0) * 0.035 * a_normal.x * (1.0 - u_mode);
    v_alpha = mix(0.35, 0.9, u_mode) * a_color.a;
    gl_Position = u_vp * vec4(wp, 1.0);
    gl_PointSize = u_pointScale * mix(1.0, 3.0, seed.y) / max(gl_Position.w, 1.0);
}
"""

    val particleFS = "#version 300 es\nprecision highp float;\nin float v_alpha;\nuniform vec3 u_color;\nuniform float u_mode;\nout vec4 fragColor;\nvoid main() {\n" +
        " float a = v_alpha;\n if (u_mode > 0.5) { vec2 c = gl_PointCoord - 0.5; a *= smoothstep(0.25, 0.0, dot(c,c)); }\n" +
        " fragColor = vec4(u_color, a);\n}\n"

    // ---------------- Unlit colored (markers, beams, preview ghost) ----------------
    val unlitVS = HEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_color;
uniform mat4 u_model;
uniform mat4 u_vp;
out vec3 v_color;
out float v_y;
void main() {
    v_color = a_color.rgb;
    v_y = a_pos.y;
    gl_Position = u_vp * u_model * vec4(a_pos, 1.0);
}
"""

    val unlitFS = "#version 300 es\nprecision mediump float;\nin vec3 v_color;\nin float v_y;\nuniform vec4 u_color;\nuniform float u_fadeHeight;\nout vec4 fragColor;\nvoid main() {\n" +
        " float a = u_color.a;\n if (u_fadeHeight > 0.0) a *= clamp(1.0 - v_y / u_fadeHeight, 0.0, 1.0);\n" +
        " fragColor = vec4(v_color * u_color.rgb, a);\n}\n"

    /** All shader pairs, for validation tooling. */
    fun all(): List<Triple<String, String, String>> = listOf(
        Triple("lit", litVS, litFS),
        Triple("inst", instVS, instFS),
        Triple("terrain", terrainVS, terrainFS),
        Triple("water", waterVS, waterFS),
        Triple("sky", skyVS, skyFS),
        Triple("depth", depthVS, depthFS),
        Triple("depthInst", depthInstVS, depthFS),
        Triple("particle", particleVS, particleFS),
        Triple("unlit", unlitVS, unlitFS),
    )
}
