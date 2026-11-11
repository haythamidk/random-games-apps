package com.worldwake.game.engine

/**
 * GLSL ES 3.00 sources. Vertex colors are sRGB and converted to linear in the vertex stage. Scene shaders either
 * output linear HDR (u_hdr == 1, the post pass tonemaps) or tonemap directly (low/medium quality).
 *
 * Vertex color alpha >= 0 is emissive strength; alpha < 0 selects a texture layer: layer = -alpha - 1 (see [Mat]).
 */
object Shaders {

    private const val VHEADER = "#version 300 es\nprecision highp float;\n"
    private const val FHEADER = "#version 300 es\nprecision highp float;\nprecision highp sampler2DShadow;\nprecision highp sampler2DArray;\n"

    /** Uniforms and functions shared by lit scene fragment shaders. */
    private const val COMMON = """
uniform vec3 u_sunDir;
uniform vec3 u_trueSunDir;
uniform vec3 u_sunColor;
uniform vec3 u_skyAmb;
uniform vec3 u_groundAmb;
uniform vec3 u_zenith;
uniform vec3 u_horizon;
uniform vec3 u_sunset;
uniform vec3 u_camPos;
uniform float u_exposure;
uniform int u_hdr;
uniform float u_outScale;
uniform int u_shadowMode;
uniform sampler2DShadow u_shadow0;
uniform sampler2DShadow u_shadow1;
uniform float u_shadowTexel0;
uniform float u_shadowTexel1;
uniform vec4 u_lights[4];
uniform vec3 u_lightColors[4];
uniform float u_flash;
uniform float u_viewDist;
uniform float u_fogDensity;
uniform float u_fogHeightFalloff;
uniform int u_underwater;
uniform float u_time;
uniform sampler2DArray u_albedoArr;
uniform sampler2DArray u_normalArr;
uniform int u_texQuality;
uniform vec3 u_waterFog;
uniform float u_frost;
uniform vec3 u_frostColor;

const float PI = 3.14159265;

vec3 skyColor(vec3 d) {
    float h = max(d.y, 0.0);
    vec3 col = mix(u_horizon, u_zenith, pow(h, 0.45));
    float mu = dot(d, u_trueSunDir);
    col *= 0.9 + 0.15 * mu * mu;
    float s = max(mu, 0.0);
    col = mix(col, u_sunset, clamp(pow(s, 3.0) * pow(1.0 - h, 3.0), 0.0, 1.0));
    col += u_sunColor * (pow(s, 64.0) * 0.22 + pow(s, 8.0) * 0.05);
    if (d.y < 0.0) col = mix(col, u_horizon * 0.55, clamp(-d.y * 2.5, 0.0, 1.0));
    return col;
}

float pcf(sampler2DShadow sm, vec3 p, float t, int taps) {
    if (taps <= 1) return texture(sm, p);
    if (taps <= 4) {
        float s = texture(sm, p + vec3(-0.75 * t, -0.75 * t, 0.0));
        s += texture(sm, p + vec3(0.75 * t, -0.75 * t, 0.0));
        s += texture(sm, p + vec3(-0.75 * t, 0.75 * t, 0.0));
        s += texture(sm, p + vec3(0.75 * t, 0.75 * t, 0.0));
        return s * 0.25;
    }
    float s = 0.0;
    s += texture(sm, p + vec3(-1.33, -0.40, 0.0) * t);
    s += texture(sm, p + vec3(-0.21, -1.42, 0.0) * t);
    s += texture(sm, p + vec3(1.18, -0.85, 0.0) * t);
    s += texture(sm, p + vec3(1.41, 0.38, 0.0) * t);
    s += texture(sm, p + vec3(0.32, 1.38, 0.0) * t);
    s += texture(sm, p + vec3(-1.01, 1.02, 0.0) * t);
    s += texture(sm, p + vec3(-0.35, 0.10, 0.0) * t);
    s += texture(sm, p + vec3(0.45, -0.22, 0.0) * t);
    return s * 0.125;
}

float shadowFactor(vec4 sp0, vec4 sp1, float ndl) {
    if (u_shadowMode == 0) return 1.0;
    float bias = 0.0006 + 0.002 * (1.0 - ndl);
    vec3 p = sp0.xyz / sp0.w * 0.5 + 0.5;
    vec2 e = abs(p.xy - 0.5) * 2.0;
    float edge = max(e.x, e.y);
    if (edge < 0.97 && p.z < 1.0) {
        int taps = u_shadowMode == 1 ? 1 : (u_shadowMode == 2 ? 4 : 8);
        float s = pcf(u_shadow0, vec3(p.xy, p.z - bias), u_shadowTexel0, taps);
        float fade = smoothstep(0.8, 0.97, edge);
        if (u_shadowMode == 3 && fade > 0.0) {
            vec3 q = sp1.xyz / sp1.w * 0.5 + 0.5;
            float s1 = pcf(u_shadow1, vec3(q.xy, q.z - bias * 2.0), u_shadowTexel1, 4);
            return mix(s, s1, fade);
        }
        return mix(s, 1.0, fade);
    }
    if (u_shadowMode == 3) {
        vec3 q = sp1.xyz / sp1.w * 0.5 + 0.5;
        vec2 e1 = abs(q.xy - 0.5) * 2.0;
        float edge1 = max(e1.x, e1.y);
        if (edge1 < 1.0 && q.z < 1.0) {
            float s1 = pcf(u_shadow1, vec3(q.xy, q.z - bias * 2.0), u_shadowTexel1, 4);
            return mix(s1, 1.0, smoothstep(0.8, 1.0, edge1));
        }
    }
    return 1.0;
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

float specGGX(vec3 n, vec3 v, vec3 l, float rough) {
    vec3 h = normalize(v + l);
    float a = rough * rough;
    float a2 = a * a;
    float nh = max(dot(n, h), 0.0);
    float dd = nh * nh * (a2 - 1.0) + 1.0;
    float D = a2 / (PI * dd * dd);
    float nl = max(dot(n, l), 0.0);
    float nv = max(dot(n, v), 0.001);
    float k = (rough + 1.0) * (rough + 1.0) / 8.0;
    float G = nl / (nl * (1.0 - k) + k) * nv / (nv * (1.0 - k) + k);
    float F = 0.04 + 0.96 * pow(1.0 - max(dot(h, v), 0.0), 5.0);
    return min(D * G * F / (4.0 * nv), 12.0);
}

vec3 applyFog(vec3 col, vec3 wp) {
    vec3 v = wp - u_camPos;
    float dist = length(v);
    vec3 vd = v / max(dist, 0.001);
    if (u_underwater == 1) {
        float f = 1.0 - exp(-dist * 0.075);
        return mix(col, u_waterFog * (u_skyAmb * 3.0 + 0.2), f);
    }
    float hf = exp(-max(wp.y - 2.0, 0.0) * u_fogHeightFalloff);
    float fog = 1.0 - exp(-dist * u_fogDensity * mix(0.35, 1.0, hf));
    fog = max(fog, smoothstep(u_viewDist * 0.55, u_viewDist * 0.97, dist));
    vec3 fc = skyColor(normalize(vec3(vd.x, max(vd.y, 0.015), vd.z)));
    return mix(col, fc, clamp(fog, 0.0, 1.0));
}

vec3 tonemap(vec3 c) {
    c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14);
    return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
}

vec4 outColor(vec3 c, float a) {
    c *= u_exposure;
    if (u_hdr == 1) return vec4(c * u_outScale, a);
    return vec4(tonemap(c), a);
}

vec4 triSample(vec3 p, vec3 n, float layer, float scale) {
    vec3 w = abs(n);
    if (u_texQuality < 2) {
        if (w.y >= w.x && w.y >= w.z) return texture(u_albedoArr, vec3(p.xz * scale, layer));
        if (w.x >= w.z) return texture(u_albedoArr, vec3(p.zy * scale, layer));
        return texture(u_albedoArr, vec3(p.xy * scale, layer));
    }
    w = pow(w, vec3(4.0));
    w /= (w.x + w.y + w.z);
    vec4 r = vec4(0.0);
    if (w.x > 0.02) r += texture(u_albedoArr, vec3(p.zy * scale, layer)) * w.x;
    if (w.y > 0.02) r += texture(u_albedoArr, vec3(p.xz * scale, layer)) * w.y;
    if (w.z > 0.02) r += texture(u_albedoArr, vec3(p.xy * scale, layer)) * w.z;
    return r;
}

/** Dominant-axis projection: keeps alpha masks crisp. */
vec4 planarSample(vec3 p, vec3 n, float layer, float scale) {
    vec3 w = abs(n);
    if (w.y >= w.x && w.y >= w.z) return texture(u_albedoArr, vec3(p.xz * scale, layer));
    if (w.x >= w.z) return texture(u_albedoArr, vec3(p.zy * scale, layer));
    return texture(u_albedoArr, vec3(p.xy * scale, layer));
}
"""

    // ------------------------------------------------------------------ lit meshes

    val litVS = VHEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_color;
uniform mat4 u_model;
uniform mat4 u_vp;
uniform mat4 u_lightVP0;
uniform mat4 u_lightVP1;
uniform vec4 u_tint;
out vec3 v_world;
out vec3 v_normal;
out vec4 v_color;
out vec4 v_sp0;
out vec4 v_sp1;
out vec3 v_obj;
out vec3 v_objN;
void main() {
    vec4 wp = u_model * vec4(a_pos, 1.0);
    v_world = wp.xyz;
    v_normal = mat3(u_model) * a_normal;
    vec3 c = pow(max(a_color.rgb, vec3(0.0)), vec3(2.2)) * mix(vec3(1.0), u_tint.rgb, u_tint.a);
    v_color = vec4(c, a_color.a);
    v_obj = a_pos;
    v_objN = a_normal;
    v_sp0 = u_lightVP0 * wp;
    v_sp1 = u_lightVP1 * wp;
    gl_Position = u_vp * wp;
}
"""

    val litFS = FHEADER + COMMON + """
in vec3 v_world;
in vec3 v_normal;
in vec4 v_color;
in vec4 v_sp0;
in vec4 v_sp1;
in vec3 v_obj;
in vec3 v_objN;
uniform vec3 u_emissiveColor;
uniform float u_emissiveScale;
uniform float u_specular;
uniform float u_hitFlash;
uniform float u_alpha;
uniform float u_texScale;
uniform float u_glass;
uniform float u_frostTop;
out vec4 fragColor;
void main() {
    vec3 n = normalize(v_normal);
    if (!gl_FrontFacing) n = -n;
    vec3 albedo = v_color.rgb;
    float emissive = max(v_color.a, 0.0);
    float rough = mix(0.85, 0.35, clamp(u_specular, 0.0, 1.0));
    float metal = 0.0;
    float ao = 1.0;
    if (v_color.a < -0.5) {
        float layer = floor(-v_color.a - 1.0 + 0.5);
        if (u_texQuality > 0) {
            vec4 t = triSample(v_obj, normalize(v_objN), layer, u_texScale);
            albedo *= t.rgb * 2.0;
            ao = mix(0.7, 1.0, t.a);
        }
        if (layer == 12.0) { rough = 0.36; metal = 0.6; }
        else if (layer == 11.0) { rough = 0.48; metal = 0.08; }
        else if (layer == 13.0) { rough = 0.7; metal = 0.2; }
        else if (layer == 14.0) { rough = 0.14; }
        else if (layer == 2.0 || layer == 7.0) { rough = 0.88; }
        else if (layer == 15.0) { rough = 0.85; }
    }
    // frost settles on upward faces while the world is frozen
    if (u_frostTop > 0.0 && emissive < 0.05) {
        float ft = smoothstep(0.45, 0.85, n.y) * u_frost * u_frostTop;
        albedo = mix(albedo, u_frostColor, ft);
        rough = mix(rough, 0.55, ft);
        metal *= 1.0 - ft;
    }
    vec3 l = u_sunDir;
    vec3 vdir = normalize(u_camPos - v_world);
    float ndl = max(dot(n, l), 0.0);
    float sh = shadowFactor(v_sp0, v_sp1, ndl);
    vec3 amb = mix(u_groundAmb, u_skyAmb, n.y * 0.5 + 0.5) * ao;
    vec3 diff = albedo * (1.0 - metal) * (amb + u_sunColor * ndl * sh + pointLights(v_world, n));
    vec3 f0 = mix(vec3(0.04), albedo, metal);
    vec3 spec = u_sunColor * f0 * 25.0 * specGGX(n, vdir, l, rough) * sh;
    float fres = pow(1.0 - max(dot(n, vdir), 0.0), 5.0);
    vec3 env = skyColor(reflect(-vdir, n)) * mix(0.04, 1.0, fres) * (1.0 - rough) * 0.6 * ao;
    vec3 col = diff + spec + env;
    col += albedo * emissive * u_emissiveColor * u_emissiveScale * 6.0;
    col += vec3(1.0, 0.35, 0.2) * u_hitFlash;
    col += albedo * u_flash * 0.6;
    col = applyFog(col, v_world);
    float alpha = u_alpha;
    if (u_glass > 0.5) alpha = clamp(mix(u_alpha, 0.92, fres) + dot(spec, vec3(0.3)), 0.0, 1.0);
    fragColor = outColor(col, alpha);
}
"""

    // ------------------------------------------------------------------ instanced vegetation

    val instVS = VHEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_color;
layout(location=3) in vec4 a_inst0;
layout(location=4) in vec4 a_inst1;
uniform mat4 u_vp;
uniform mat4 u_lightVP0;
uniform mat4 u_lightVP1;
uniform float u_time;
uniform float u_wind;
uniform vec3 u_camPos;
uniform float u_fadeDist;
uniform float u_grow;
uniform vec3 u_tintA;
uniform vec3 u_tintB;
out vec3 v_world;
out vec3 v_normal;
out vec4 v_color;
out vec4 v_sp0;
out vec4 v_sp1;
out vec3 v_obj;
out vec3 v_objN;
void main() {
    float c = cos(a_inst0.w);
    float s = sin(a_inst0.w);
    float grow = smoothstep(a_inst1.w, a_inst1.w + 0.16, u_grow);
    float sc = a_inst1.x * grow;
    vec3 p = a_pos * sc;
    vec3 rp = vec3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z);
    vec3 wp = rp + a_inst0.xyz;
    float sway = a_inst1.z * max(a_pos.y, 0.0) * sc;
    float ph = a_inst0.x * 0.37 + a_inst0.z * 0.21;
    float w = sin(u_time * 1.7 + ph) * 0.6 + sin(u_time * 3.1 + ph * 1.7) * 0.25;
    float gust = 0.4 + u_wind * (0.8 + 0.6 * sin(u_time * 0.4 + a_inst0.x * 0.02));
    wp.x += w * sway * 0.04 * gust;
    wp.z += cos(u_time * 1.3 + ph) * sway * 0.025 * gust;
    // leaves flutter
    if (a_color.a < -9.5 && a_color.a > -11.5) {
        wp += vec3(sin(u_time * 5.0 + a_pos.x * 7.0 + ph), cos(u_time * 4.3 + a_pos.z * 6.0), sin(u_time * 4.7 + a_pos.y * 5.0)) * 0.02 * gust;
    }
    // grass shrinks into the ground at its fade distance instead of popping
    if (u_fadeDist > 0.0) {
        float d = length(a_inst0.xz - u_camPos.xz);
        float k = clamp((u_fadeDist - d) / (u_fadeDist * 0.25), 0.0, 1.0);
        wp.y = mix(a_inst0.y, wp.y, k);
    }
    v_world = wp;
    vec3 n = a_normal;
    v_normal = vec3(c * n.x + s * n.z, n.y, -s * n.x + c * n.z);
    vec3 col = pow(max(a_color.rgb, vec3(0.0)), vec3(2.2));
    float tint = a_inst1.y;
    if (a_color.a < 0.5) col *= mix(u_tintA, u_tintB, tint);
    v_color = vec4(col, a_color.a);
    v_obj = a_pos;
    v_objN = a_normal;
    v_sp0 = u_lightVP0 * vec4(wp, 1.0);
    v_sp1 = u_lightVP1 * vec4(wp, 1.0);
    gl_Position = grow < 0.002 ? vec4(0.0, 0.0, -10.0, 1.0) : u_vp * vec4(wp, 1.0);
}
"""

    val instFS = FHEADER + COMMON + """
in vec3 v_world;
in vec3 v_normal;
in vec4 v_color;
in vec4 v_sp0;
in vec4 v_sp1;
in vec3 v_obj;
in vec3 v_objN;
uniform float u_translucency;
uniform int u_foliage;
uniform float u_frostTop;
uniform float u_glow;
out vec4 fragColor;
void main() {
    vec3 n = normalize(v_normal);
    if (!gl_FrontFacing) n = -n;
    vec3 albedo = v_color.rgb;
    float emissive = max(v_color.a, 0.0);
    float trans = u_translucency;
    float rough = 0.8;
    float ao = 1.0;
    float dist = length(v_world - u_camPos);
    if (v_color.a < -0.5) {
        float layer = floor(-v_color.a - 1.0 + 0.5);
        if (layer >= 18.5) {
            // grass blade: dark roots, light tips
            float t = clamp(v_obj.y / 0.7, 0.0, 1.0);
            albedo *= mix(0.35, 1.05, t);
            ao = mix(0.55, 1.0, t);
            trans = 0.9;
        } else if (layer == 9.0 || layer == 10.0) {
            if (u_foliage > 0) {
                float sc = layer == 9.0 ? 0.62 : 0.85;
                vec4 t = planarSample(v_obj + vec3(0.37, 0.11, 0.53), normalize(v_objN), layer, sc);
                float thr = mix(0.5, 0.04, smoothstep(14.0, 70.0, dist));
                if (t.a < thr) discard;
                albedo *= t.rgb * 2.0;
            }
            // deeper leaves are darker
            ao = mix(0.6, 1.0, clamp(length(v_obj.xz) * 0.35 + (v_obj.y - 3.0) * 0.15, 0.0, 1.0));
            trans = 0.75;
        } else if (u_texQuality > 0) {
            vec4 t;
            if (layer == 8.0) {
                float u = atan(v_obj.z, v_obj.x) / (2.0 * PI) * 3.0;
                t = texture(u_albedoArr, vec3(u, v_obj.y * 0.6, layer));
            } else {
                t = triSample(v_obj, normalize(v_objN), layer, 0.8);
            }
            albedo *= t.rgb * 2.0;
            ao = mix(0.7, 1.0, t.a);
            rough = layer == 14.0 ? 0.14 : 0.85;
            if (layer == 14.0) trans = 0.6;
        }
    }
    if (u_frostTop > 0.0 && emissive < 0.05) {
        float ft = smoothstep(0.4, 0.85, n.y) * u_frost * u_frostTop;
        albedo = mix(albedo, u_frostColor, ft);
        rough = mix(rough, 0.55, ft);
    }
    float ndlRaw = dot(n, u_sunDir);
    float ndl = max(ndlRaw, 0.0);
    float sh = shadowFactor(v_sp0, v_sp1, max(ndl, 0.3));
    float wrap = max(ndlRaw * 0.5 + 0.5, 0.0);
    float diff = mix(ndl, wrap, trans);
    vec3 amb = mix(u_groundAmb, u_skyAmb, n.y * 0.5 + 0.5) * ao;
    vec3 col = albedo * (amb + u_sunColor * diff * sh + pointLights(v_world, n));
    vec3 vdir = normalize(u_camPos - v_world);
    float back = pow(max(dot(-vdir, u_sunDir), 0.0), 4.0) * trans * sh;
    col += albedo * u_sunColor * back * 0.7;
    col += u_sunColor * 6.0 * specGGX(n, vdir, u_sunDir, rough) * sh * (rough < 0.3 ? 0.5 : 0.04);
    col += albedo * emissive * 3.0 * u_glow;
    col += albedo * u_flash * 0.6;
    col = applyFog(col, v_world);
    fragColor = outColor(col, 1.0);
}
"""

    // ------------------------------------------------------------------ terrain

    val terrainVS = VHEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_splatA;
layout(location=5) in vec4 a_splatB;
uniform mat4 u_vp;
uniform mat4 u_lightVP0;
uniform mat4 u_lightVP1;
out vec3 v_world;
out vec3 v_normal;
out vec4 v_wa;
out vec4 v_wb;
out vec4 v_sp0;
out vec4 v_sp1;
void main() {
    vec3 sp = a_pos + vec3(0.0, a_splatB.w, 0.0);
    v_world = sp;
    v_normal = a_normal;
    v_wa = a_splatA;
    v_wb = a_splatB;
    v_sp0 = u_lightVP0 * vec4(sp, 1.0);
    v_sp1 = u_lightVP1 * vec4(sp, 1.0);
    gl_Position = u_vp * vec4(a_pos, 1.0);
}
"""

    val terrainFS = FHEADER + COMMON + """
in vec3 v_world;
in vec3 v_normal;
in vec4 v_wa;
in vec4 v_wb;
in vec4 v_sp0;
in vec4 v_sp1;
uniform sampler2D u_noise;
uniform float u_wetness;
uniform float u_moss;
uniform float u_grass;
uniform float u_waterLevel;
uniform vec3 u_waterTint;
out vec4 fragColor;

// base ground slots: soil, silt, sulfur crust, gravel
const float LAYER[4] = float[4](0.0, 3.0, 6.0, 7.0);
// average linear colors for the untextured path
const vec3 C_SOIL = vec3(0.035, 0.04, 0.055);
const vec3 C_SILT = vec3(0.2, 0.18, 0.23);
const vec3 C_SULF = vec3(0.3, 0.26, 0.035);
const vec3 C_GRAV = vec3(0.05, 0.05, 0.065);
const vec3 C_ROCK = vec3(0.035, 0.05, 0.07);
const vec3 C_MOSS = vec3(0.14, 0.012, 0.03);
const vec3 C_GRASS = vec3(0.4, 0.27, 0.11);

void main() {
    vec3 gn = normalize(v_normal);
    float slope = 1.0 - gn.y;
    vec4 macro = texture(u_noise, v_world.xz * 0.006);
    vec4 meso = texture(u_noise, v_world.xz * 0.031);
    float rockW = smoothstep(0.24, 0.44, slope + (macro.r - 0.5) * 0.12);
    rockW = max(rockW, v_wb.z * smoothstep(0.25, 0.6, meso.a + v_wb.z * 0.5));
    float fert = v_wb.y;
    float flatness = 1.0 - smoothstep(0.2, 0.42, slope);
    float sub = smoothstep(u_waterLevel + 0.2, u_waterLevel - 0.5, v_world.y);
    float mossC = smoothstep(0.0, 0.14, u_moss * 1.05 - (meso.g * 0.6 + macro.b * 0.4) * 1.0 - (1.0 - fert) * 0.9) * (1.0 - rockW * 0.65) * (1.0 - sub);
    float grassC = smoothstep(0.0, 0.2, u_grass * 1.2 - (meso.b * 0.5 + macro.g * 0.5) * 0.95 - (1.0 - fert) * 1.1) * flatness * (1.0 - rockW) * (1.0 - sub);
    float fr = u_frost * 1.6 + v_wb.x - (meso.r * 0.6 + macro.a * 0.4) * 0.8 - slope * 0.55;
    float frostC = smoothstep(0.2, 0.45, fr);
    vec3 albedo;
    vec3 n = gn;
    float rough = 0.85;
    float ao = 1.0;
    if (u_texQuality == 0) {
        albedo = C_SOIL * v_wa.x + C_SILT * v_wa.y + C_SULF * v_wa.z + C_GRAV * v_wa.w;
        albedo = mix(albedo, C_ROCK, rockW);
        albedo = mix(albedo, C_MOSS, mossC);
        albedo = mix(albedo, C_GRASS, grassC);
        albedo = mix(albedo, pow(u_frostColor, vec3(1.0)), frostC);
        albedo *= 0.8 + macro.g * 0.4;
        rough = mix(0.85, 0.5, frostC);
    } else {
        vec2 uv = v_world.xz * 0.24;
        vec2 uv2 = vec2(v_world.x * 0.071 - v_world.z * 0.023, v_world.z * 0.071 + v_world.x * 0.023);
        float w[4];
        w[0] = v_wa.x; w[1] = v_wa.y; w[2] = v_wa.z; w[3] = v_wa.w;
        vec3 acc = vec3(0.0);
        vec4 nacc = vec4(0.0);
        float wsum = 0.0;
        for (int i = 0; i < 4; i++) {
            float wi = w[i] * (1.0 - rockW);
            if (wi < 0.02) continue;
            float layer = LAYER[i];
            vec4 t = texture(u_albedoArr, vec3(uv, layer));
            if (u_texQuality > 1) {
                vec4 t2 = texture(u_albedoArr, vec3(uv2, layer));
                t = mix(t, t2, 0.35 + (macro.b - 0.5) * 0.4);
            }
            float hw = wi * (0.2 + t.a);
            hw = hw * hw;
            acc += t.rgb * hw;
            if (u_texQuality > 1) nacc += texture(u_normalArr, vec3(uv, layer)) * hw;
            wsum += hw;
        }
        if (rockW > 0.02) {
            vec4 rx = texture(u_albedoArr, vec3(v_world.zy * 0.16, 2.0));
            vec4 rz = texture(u_albedoArr, vec3(v_world.xy * 0.16, 2.0));
            vec4 ry = texture(u_albedoArr, vec3(uv * 0.7, 2.0));
            vec3 bw = pow(abs(gn), vec3(4.0)); bw /= (bw.x + bw.y + bw.z);
            vec4 rock = rx * bw.x + ry * bw.y + rz * bw.z;
            float hw = rockW * (0.2 + rock.a); hw = hw * hw * 1.4;
            acc += rock.rgb * hw;
            if (u_texQuality > 1) nacc += texture(u_normalArr, vec3(uv * 0.7, 2.0)) * hw;
            wsum += hw;
        }
        vec3 alb = acc / max(wsum, 1e-4);
        vec4 nm = u_texQuality > 1 ? nacc / max(wsum, 1e-4) : vec4(0.5, 0.5, 0.85, 1.0);
        if (mossC > 0.01) {
            vec4 t = texture(u_albedoArr, vec3(uv * 0.8, 4.0));
            float c = clamp((mossC - (1.0 - t.a) * 0.35) / 0.65, 0.0, 1.0);
            alb = mix(alb, t.rgb, c);
            if (u_texQuality > 1) nm = mix(nm, texture(u_normalArr, vec3(uv * 0.8, 4.0)), c);
        }
        if (grassC > 0.01) {
            vec4 t = texture(u_albedoArr, vec3(uv, 5.0));
            if (u_texQuality > 1) t = mix(t, texture(u_albedoArr, vec3(uv2, 5.0)), 0.4);
            float c = clamp((grassC - (1.0 - t.a) * 0.3) / 0.7, 0.0, 1.0);
            alb = mix(alb, t.rgb, c);
            if (u_texQuality > 1) nm = mix(nm, texture(u_normalArr, vec3(uv, 5.0)), c);
        }
        if (frostC > 0.01) {
            vec4 t = texture(u_albedoArr, vec3(uv * 0.6, 1.0));
            if (u_texQuality > 1) t = mix(t, texture(u_albedoArr, vec3(uv2 * 1.3, 1.0)), 0.4);
            float c = clamp((frostC - (1.0 - t.a) * 0.3) / 0.7, 0.0, 1.0);
            alb = mix(alb, t.rgb, c);
            if (u_texQuality > 1) nm = mix(nm, texture(u_normalArr, vec3(uv * 0.6, 1.0)), c);
        }
        albedo = pow(alb, vec3(2.2));
        albedo *= 0.82 + macro.g * 0.36;
        if (u_texQuality > 1) {
            vec2 tn = nm.xy * 2.0 - 1.0;
            n = normalize(gn + vec3(tn.x, 0.0, tn.y) * 0.9);
            rough = nm.z;
            ao = mix(0.55, 1.0, nm.w);
        }
    }
    // rain wetness and the damp shore band darken the ground and add sheen
    float shore = smoothstep(u_waterLevel + 0.6, u_waterLevel + 0.05, v_world.y) * step(-50.0, u_waterLevel);
    float wet = clamp(u_wetness * (1.0 - frostC) + shore * 0.8, 0.0, 1.0);
    albedo *= 1.0 - wet * 0.35;
    rough = mix(rough, 0.25, wet * 0.7);
    float under = smoothstep(u_waterLevel, u_waterLevel - 4.0, v_world.y) * step(-50.0, u_waterLevel);
    albedo = mix(albedo, albedo * u_waterTint, under);

    float ndl = max(dot(n, u_sunDir), 0.0);
    float sh = shadowFactor(v_sp0, v_sp1, max(dot(gn, u_sunDir), 0.0));
    vec3 amb = mix(u_groundAmb, u_skyAmb, n.y * 0.5 + 0.5) * ao;
    vec3 col = albedo * (amb + u_sunColor * ndl * sh + pointLights(v_world, n));
    vec3 vdir = normalize(u_camPos - v_world);
    col += u_sunColor * 0.04 * specGGX(n, vdir, u_sunDir, rough) * sh * (1.0 + wet * 6.0 + frostC * 3.0);
    // frost glitter
    if (frostC > 0.3 && u_texQuality > 0) {
        float g = texture(u_noise, v_world.xz * 0.9 + vdir.xz * 0.35).r;
        col += u_sunColor * smoothstep(0.86, 0.95, g) * frostC * ndl * sh * 0.5;
    }
    col += albedo * u_flash * 0.6;
    col = applyFog(col, v_world);
    fragColor = outColor(col, 1.0);
}
"""

    // ------------------------------------------------------------------ water

    val waterVS = VHEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_color;
uniform mat4 u_vp;
uniform float u_time;
uniform float u_waveScale;
uniform float u_waterLevel;
out vec3 v_world;
out float v_depth;
out vec3 v_normal;
out vec4 v_clip;
void main() {
    vec3 p = a_pos;
    p.y = u_waterLevel;
    float dep = a_color.r + u_waterLevel;
    float depthAmp = clamp(dep / 5.0, 0.0, 1.0) * u_waveScale;
    vec3 n = vec3(0.0, 1.0, 0.0);
    // three gerstner waves
    vec2 dirs[3] = vec2[3](vec2(0.8, 0.6), vec2(-0.5, 0.86), vec2(0.97, -0.24));
    float amps[3] = float[3](0.09, 0.06, 0.035);
    float lens[3] = float[3](19.0, 9.0, 4.5);
    for (int i = 0; i < 3; i++) {
        float k = 6.2831 / lens[i];
        float c = sqrt(9.8 / k);
        float a = amps[i] * depthAmp;
        float f = k * (dot(dirs[i], a_pos.xz) - c * u_time);
        p.x += dirs[i].x * a * 0.6 * cos(f);
        p.z += dirs[i].y * a * 0.6 * cos(f);
        p.y += a * sin(f);
        n.x -= dirs[i].x * k * a * cos(f);
        n.z -= dirs[i].y * k * a * cos(f);
    }
    v_world = p;
    v_depth = dep;
    v_normal = normalize(n);
    v_clip = u_vp * vec4(p, 1.0);
    gl_Position = v_clip;
}
"""

    val waterFS = FHEADER + COMMON + """
in vec3 v_world;
in float v_depth;
in vec3 v_normal;
in vec4 v_clip;
uniform sampler2D u_noise;
uniform sampler2D u_scene;
uniform int u_waterQuality;
uniform int u_refract;
uniform float u_rain;
uniform vec3 u_waterAbsorb;
uniform vec3 u_waterScatter;
uniform float u_waterGlow;
out vec4 fragColor;
void main() {
    if (v_depth < -0.03) discard;
    vec2 uv = v_world.xz;
    vec3 n = v_normal;
    if (u_waterQuality > 0) {
        vec4 a = texture(u_noise, uv * 0.035 + vec2(u_time * 0.012, u_time * 0.008));
        vec4 b = texture(u_noise, uv * 0.08 - vec2(u_time * 0.015, -u_time * 0.01));
        vec4 c = texture(u_noise, uv * 0.23 + vec2(u_time * 0.035, u_time * 0.022));
        vec2 d = (a.rg - 0.5) * 0.6 + (b.ba - 0.5) * 0.5 + (c.rg - 0.5) * (0.3 + u_rain * 0.9);
        n = normalize(n + vec3(d.x, 0.0, d.y));
    }
    vec3 vdir = normalize(u_camPos - v_world);
    float nv = max(dot(n, vdir), 0.0);
    float fres = 0.02 + 0.98 * pow(1.0 - nv, 5.0);
    vec3 refl = skyColor(reflect(-vdir, n));
    float depth = max(v_depth, 0.0) / max(nv, 0.25);
    vec3 absorb = exp(-depth * u_waterAbsorb);
    vec3 scatterCol = u_waterScatter * (u_skyAmb * 2.5 + u_sunColor * 0.25) + u_waterScatter * u_waterGlow;
    vec3 body;
    float alpha = 1.0;
    if (u_refract == 1) {
        vec2 suv = v_clip.xy / v_clip.w * 0.5 + 0.5;
        suv += n.xz * 0.035 * clamp(v_depth, 0.0, 1.5);
        vec3 under = texture(u_scene, suv).rgb / max(u_outScale * u_exposure, 1e-4);
        body = under * absorb + scatterCol * (1.0 - absorb.g);
    } else {
        body = scatterCol * 1.3 + u_waterScatter * 1.2 * (u_skyAmb + u_sunColor * 0.2) * absorb.g;
        alpha = clamp(0.6 + (1.0 - absorb.g) * 0.4, 0.0, 0.96);
    }
    vec3 col = mix(body, refl, fres);
    col += u_sunColor * 30.0 * specGGX(n, vdir, u_sunDir, 0.08);
    col += pointLights(v_world, vec3(0.0, 1.0, 0.0)) * 0.2;
    // shoreline foam
    float fn = texture(u_noise, uv * 0.3 + vec2(u_time * 0.02, 0.0)).r;
    float foam = smoothstep(0.45, 0.0, v_depth) * smoothstep(0.4, 0.8, fn + 0.25 * sin(u_time * 1.5 + v_depth * 6.0));
    col = mix(col, (u_skyAmb + u_sunColor * 0.6) * 0.9, foam * 0.6);
    if (u_refract == 0) alpha = mix(alpha, 0.85, foam);
    col += u_flash * 0.4;
    if (u_underwater == 0) col = applyFog(col, v_world);
    fragColor = outColor(col, alpha);
}
"""

    // ------------------------------------------------------------------ sky

    val skyVS = VHEADER + """
layout(location=0) in vec3 a_pos;
uniform mat4 u_invViewProj;
out vec3 v_dir;
void main() {
    vec4 w = u_invViewProj * vec4(a_pos.xy, 1.0, 1.0);
    v_dir = w.xyz / w.w;
    gl_Position = vec4(a_pos.xy, 0.99999, 1.0);
}
"""

    val skyFS = FHEADER + COMMON + """
in vec3 v_dir;
uniform vec3 u_moonDir;
uniform float u_night;
uniform float u_starVis;
uniform float u_giantVis;
uniform float u_aurora;
uniform float u_cloudCover;
uniform sampler2D u_noise;
uniform int u_clouds;
out vec4 fragColor;
float hash(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
float cloudDensity(vec2 p) {
    vec2 w = vec2(u_time * 0.006, u_time * 0.0025);
    float d = texture(u_noise, p + w).r * 0.55;
    d += texture(u_noise, p * 2.3 + w * 1.7 + d * 0.15).g * 0.3;
    d += texture(u_noise, p * 5.1 + w * 2.9).b * 0.15;
    float thr = mix(0.66, 0.28, u_cloudCover);
    return smoothstep(thr, thr + 0.32, d) * smoothstep(0.02, 0.3, u_cloudCover);
}
void main() {
    vec3 d = normalize(v_dir - u_camPos);
    if (u_underwater == 1) { fragColor = outColor(u_waterFog * (u_skyAmb * 3.0 + 0.2), 1.0); return; }
    vec3 col = skyColor(d);
    float sd = max(dot(d, u_trueSunDir), 0.0);
    float sunDisc = smoothstep(0.99975, 0.99992, sd);
    col += u_sunColor * sunDisc * 60.0 * (1.0 - u_cloudCover * 0.85);
    col += u_sunColor * pow(sd, 900.0) * 1.5 * (1.0 - u_cloudCover * 0.7);

    // the ringed giant: a lit sphere with banded clouds, phases follow the sun
    vec3 G = u_moonDir;
    float discMask = 0.0;
    float gd = dot(d, G);
    if (gd > 0.7) {
        const float R = 0.2;
        vec3 right = normalize(cross(vec3(0.0, 1.0, 0.0), G));
        vec3 up = cross(G, right);
        vec2 p = vec2(dot(d, right), dot(d, up)) / R;
        const float ca = 0.9394; const float sa = 0.3429;
        vec2 q = vec2(ca * p.x + sa * p.y, -sa * p.x + ca * p.y);
        float r2 = dot(p, p);
        vec3 gcol = vec3(0.0);
        float galpha = 0.0;
        if (r2 < 1.0) {
            float z = sqrt(1.0 - r2);
            vec3 nrm = normalize(p.x * right + p.y * up - z * G);
            float lit = max(dot(nrm, u_trueSunDir), 0.0);
            lit = pow(lit, 0.75);
            float lat = q.y + (texture(u_noise, vec2(q.x * 0.11 + u_time * 0.0004, q.y * 0.7)).r - 0.5) * 0.14;
            float b = 0.5 + 0.5 * sin(lat * 12.0 + 1.4 * sin(lat * 4.3));
            vec3 dc = mix(vec3(0.95, 0.82, 0.68), vec3(0.82, 0.5, 0.54), smoothstep(0.3, 0.75, b));
            dc = mix(dc, vec3(0.5, 0.42, 0.74), smoothstep(0.5, 1.0, abs(lat)));
            dc = mix(dc, vec3(0.34, 0.22, 0.36), smoothstep(0.82, 1.0, 0.5 + 0.5 * sin(lat * 29.0 + 2.0)) * 0.5);
            dc *= 0.5 + 0.5 * z;
            float edge = 1.0 - smoothstep(0.93, 1.0, r2);
            discMask = edge;
            gcol = dc * (lit * 1.25 + 0.02 + 0.02 * u_night);
            galpha = edge * smoothstep(0.0, 0.2, lit + 0.02 + u_night * 0.3);
        }
        vec2 e = vec2(q.x, q.y / 0.26);
        float rr = length(e);
        float ringMask = smoothstep(1.32, 1.4, rr) * (1.0 - smoothstep(2.2, 2.32, rr));
        if (ringMask > 0.0 && !(r2 < 1.0 && q.y > 0.0)) {
            float pat = texture(u_noise, vec2(rr * 1.7, 0.37)).r;
            float gap = smoothstep(0.03, 0.06, abs(rr - 1.86));
            float ra = ringMask * gap * (0.35 + 0.65 * smoothstep(0.25, 0.7, pat));
            vec3 rc = mix(vec3(0.86, 0.76, 0.8), vec3(0.62, 0.56, 0.78), pat) * (0.45 + 0.4 * max(u_trueSunDir.y + 0.4, 0.0) + 0.25);
            gcol = mix(gcol, rc, ra);
            galpha = max(galpha, ra * 0.9);
            discMask = max(discMask, ra);
        }
        col = mix(col, col * 0.12 + gcol, galpha * u_giantVis);
    }

    // stars are visible in daylight while the air is thin
    if (u_starVis > 0.01 && d.y > 0.0) {
        vec3 sp = floor(d * 420.0);
        float st = hash(sp);
        float star = step(0.9962, st) * (0.6 + 0.4 * sin(u_time * 2.0 + st * 100.0));
        vec3 tint = mix(vec3(0.8, 0.85, 1.0), vec3(1.0, 0.85, 0.75), fract(st * 37.0));
        float band = exp(-pow(dot(d, normalize(vec3(0.3, 0.5, 0.8))), 2.0) * 18.0) * texture(u_noise, d.xz * 2.0).r;
        col += (tint * star * 1.6 + vec3(0.07, 0.05, 0.11) * band) * u_starVis * (1.0 - u_cloudCover) * (1.0 - discMask);
    }

    // aurora curtains
    if (u_aurora > 0.01 && d.y > 0.05) {
        vec2 p = d.xz / (d.y + 0.25);
        float n1 = texture(u_noise, vec2(p.x * 0.11 + u_time * 0.004, p.y * 0.035)).r;
        float n2 = texture(u_noise, vec2(p.x * 0.27 - u_time * 0.006, p.y * 0.06 + 0.3)).g;
        float curtain = smoothstep(0.5, 0.78, n1 * 0.65 + n2 * 0.35 + 0.1);
        float rays = 0.55 + 0.45 * texture(u_noise, vec2(p.x * 1.9 + u_time * 0.01, 0.5)).b;
        float hfade = smoothstep(0.05, 0.3, d.y) * (1.0 - smoothstep(0.75, 1.0, d.y));
        vec3 ac = mix(vec3(0.1, 0.95, 0.62), vec3(0.85, 0.2, 0.9), smoothstep(0.25, 0.9, d.y + n2 * 0.3));
        col += ac * curtain * rays * hfade * u_aurora * 0.55 * (1.0 - u_cloudCover);
    }

    if (u_clouds > 0 && d.y > 0.0 && u_cloudCover > 0.005) {
        vec2 p = d.xz / (d.y + 0.12) * 0.2;
        float dens = cloudDensity(p);
        if (dens > 0.001) {
            float toward = cloudDensity(p + u_trueSunDir.xz * 0.04);
            float light = clamp(1.0 - (toward - dens) * 1.5 - toward * 0.45, 0.15, 1.0);
            vec3 lit = u_sunColor * 0.55 * light + u_skyAmb * 1.1 + u_sunset * 0.3 * light;
            vec3 shadowCol = u_skyAmb * 0.7 + u_horizon * 0.25;
            vec3 cc = mix(shadowCol, lit, light);
            cc += u_sunColor * pow(sd, 10.0) * (1.0 - dens) * 0.9;
            cc = mix(cc, vec3(0.3, 0.3, 0.36) * (u_skyAmb * 2.0 + 0.05), u_cloudCover * 0.6);
            float fade = smoothstep(0.0, 0.2, d.y);
            col = mix(col, cc, dens * fade * 0.95);
        }
        if (u_clouds > 1) {
            vec2 q = d.xz / (d.y + 0.2) * 0.12;
            float ci = texture(u_noise, vec2(q.x * 3.0, q.y * 0.6) + u_time * 0.001).a;
            ci = smoothstep(0.5, 1.0, ci) * (1.0 - u_cloudCover) * smoothstep(0.05, 0.4, d.y) * smoothstep(0.0, 0.15, u_cloudCover);
            col += (u_sunColor * 0.12 + u_skyAmb * 0.3) * ci * 0.18;
        }
    }
    col += vec3(0.6, 0.65, 0.8) * u_flash;
    fragColor = outColor(col, 1.0);
}
"""

    // ------------------------------------------------------------------ shadow depth

    val depthVS = VHEADER + """
layout(location=0) in vec3 a_pos;
uniform mat4 u_model;
uniform mat4 u_lightVP;
void main() { gl_Position = u_lightVP * u_model * vec4(a_pos, 1.0); }
"""

    val depthInstVS = VHEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_color;
layout(location=3) in vec4 a_inst0;
layout(location=4) in vec4 a_inst1;
uniform mat4 u_lightVP;
uniform float u_grow;
out vec3 v_obj;
out vec3 v_objN;
out float v_mat;
void main() {
    float c = cos(a_inst0.w);
    float s = sin(a_inst0.w);
    float grow = smoothstep(a_inst1.w, a_inst1.w + 0.16, u_grow);
    vec3 p = a_pos * a_inst1.x * grow;
    vec3 wp = vec3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z) + a_inst0.xyz;
    v_obj = a_pos;
    v_objN = a_normal;
    v_mat = a_color.a;
    gl_Position = grow < 0.002 ? vec4(0.0, 0.0, -10.0, 1.0) : u_lightVP * vec4(wp, 1.0);
}
"""

    val depthFS = "#version 300 es\nprecision mediump float;\nout vec4 fragColor;\nvoid main() { fragColor = vec4(1.0); }\n"

    val depthInstFS = "#version 300 es\nprecision highp float;\nprecision highp sampler2DArray;\n" + """
in vec3 v_obj;
in vec3 v_objN;
in float v_mat;
uniform sampler2DArray u_albedoArr;
uniform int u_alphaTest;
out vec4 fragColor;
void main() {
    if (u_alphaTest == 1 && v_mat < -9.5 && v_mat > -11.5) {
        float layer = floor(-v_mat - 1.0 + 0.5);
        float sc = layer == 9.0 ? 0.62 : 0.85;
        vec3 p = v_obj + vec3(0.37, 0.11, 0.53);
        vec3 w = abs(v_objN);
        vec4 t;
        if (w.y >= w.x && w.y >= w.z) t = texture(u_albedoArr, vec3(p.xz * sc, layer));
        else if (w.x >= w.z) t = texture(u_albedoArr, vec3(p.zy * sc, layer));
        else t = texture(u_albedoArr, vec3(p.xy * sc, layer));
        if (t.a < 0.5) discard;
    }
    fragColor = vec4(1.0);
}
"""

    // ------------------------------------------------------------------ particles

    val particleVS = VHEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_color;
uniform mat4 u_vp;
uniform vec3 u_camPos;
uniform float u_time;
uniform float u_mode;
uniform float u_fall;
uniform float u_wander;
uniform vec3 u_windDir;
uniform float u_pointScale;
out float v_alpha;
void main() {
    float box = 36.0;
    float hgt = 24.0;
    vec3 seed = a_pos;
    float y = mod(seed.y * hgt - u_time * u_fall * (0.8 + seed.x * 0.4), hgt);
    vec3 drift = u_windDir * (hgt - y) * mix(0.25, 0.6, u_mode);
    if (u_mode > 0.5) drift += vec3(sin(u_time * 0.7 + seed.z * 20.0), 0.0, cos(u_time * 0.55 + seed.x * 20.0)) * u_wander;
    vec3 base = vec3(seed.x * box, y, seed.z * box);
    base.xz = mod(base.xz - u_camPos.xz + drift.xz, box) - box * 0.5;
    vec3 wp = vec3(base.x + u_camPos.x, u_camPos.y - hgt * 0.4 + y, base.z + u_camPos.z);
    if (u_mode > 0.5) wp.y += sin(u_time * 0.9 + seed.x * 40.0) * u_wander * 0.5;
    wp -= (vec3(0.0, -1.0, 0.0) * u_fall + u_windDir * 3.0) * 0.035 * a_normal.x * (1.0 - u_mode);
    float tw = u_mode > 1.5 ? 0.55 + 0.45 * sin(u_time * 2.3 + seed.y * 90.0) : 1.0;
    v_alpha = mix(0.3, 0.9, min(u_mode, 1.0)) * a_color.a * tw;
    gl_Position = u_vp * vec4(wp, 1.0);
    gl_PointSize = u_pointScale * mix(1.0, 3.0, seed.y) / max(gl_Position.w, 1.0);
}
"""

    val particleFS = "#version 300 es\nprecision highp float;\nin float v_alpha;\nuniform vec3 u_color;\nuniform float u_mode;\nuniform float u_outMul;\nout vec4 fragColor;\nvoid main() {\n" +
        " float a = v_alpha;\n if (u_mode > 0.5) { vec2 c = gl_PointCoord - 0.5; a *= smoothstep(0.25, 0.0, dot(c,c)); }\n" +
        " fragColor = vec4(u_color * u_outMul, a);\n}\n"

    /** CPU driven soft sprites (steam, dust, sparks): position, (size, alpha) in the normal slot, color. */
    val fxVS = VHEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_color;
uniform mat4 u_vp;
uniform float u_pointScale;
out vec4 v_col;
out float v_add;
void main() {
    v_col = vec4(a_color.rgb, a_normal.y);
    v_add = a_normal.z;
    gl_Position = u_vp * vec4(a_pos, 1.0);
    gl_PointSize = clamp(u_pointScale * a_normal.x / max(gl_Position.w, 0.3), 1.0, 512.0);
}
"""

    /** Premultiplied output: v_add = 1 makes the sprite purely additive (sparks, glow), 0 makes it smoke. */
    val fxFS = "#version 300 es\nprecision highp float;\nin vec4 v_col;\nin float v_add;\nuniform float u_outMul;\nout vec4 fragColor;\nvoid main() {\n" +
        " vec2 c = gl_PointCoord - 0.5;\n float a = smoothstep(0.25, 0.0, dot(c, c)) * v_col.a;\n fragColor = vec4(v_col.rgb * u_outMul * a, a * (1.0 - v_add));\n}\n"

    // ------------------------------------------------------------------ unlit

    val unlitVS = VHEADER + """
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

    val unlitFS = "#version 300 es\nprecision highp float;\nin vec3 v_color;\nin float v_y;\nuniform vec4 u_color;\nuniform float u_fadeHeight;\nuniform float u_outMul;\nout vec4 fragColor;\nvoid main() {\n" +
        " float a = u_color.a;\n if (u_fadeHeight > 0.0) a *= clamp(1.0 - v_y / u_fadeHeight, 0.0, 1.0);\n" +
        " fragColor = vec4(v_color * u_color.rgb * u_outMul, a);\n}\n"

    // ------------------------------------------------------------------ post processing

    val postVS = VHEADER + """
layout(location=0) in vec3 a_pos;
out vec2 v_uv;
void main() {
    v_uv = a_pos.xy * 0.5 + 0.5;
    gl_Position = vec4(a_pos.xy, 0.0, 1.0);
}
"""

    /** Downsample with optional soft-threshold bright pass. */
    val downFS = "#version 300 es\nprecision highp float;\n" + """
in vec2 v_uv;
uniform sampler2D u_tex;
uniform vec2 u_texel;
uniform float u_threshold;
out vec4 fragColor;
void main() {
    vec3 c = texture(u_tex, v_uv + u_texel * vec2(-1.0, -1.0)).rgb;
    c += texture(u_tex, v_uv + u_texel * vec2(1.0, -1.0)).rgb;
    c += texture(u_tex, v_uv + u_texel * vec2(-1.0, 1.0)).rgb;
    c += texture(u_tex, v_uv + u_texel * vec2(1.0, 1.0)).rgb;
    c *= 0.25;
    if (u_threshold > 0.0) {
        float br = max(c.r, max(c.g, c.b));
        float soft = clamp(br - u_threshold * 0.5, 0.0, u_threshold);
        soft = soft * soft / (4.0 * u_threshold + 1e-4);
        float contrib = max(soft, br - u_threshold) / max(br, 1e-4);
        c *= contrib;
    }
    fragColor = vec4(c, 1.0);
}
"""

    val upFS = "#version 300 es\nprecision highp float;\n" + """
in vec2 v_uv;
uniform sampler2D u_tex;
uniform vec2 u_texel;
out vec4 fragColor;
void main() {
    vec3 c = texture(u_tex, v_uv).rgb * 4.0;
    c += texture(u_tex, v_uv + u_texel * vec2(-1.0, 0.0)).rgb * 2.0;
    c += texture(u_tex, v_uv + u_texel * vec2(1.0, 0.0)).rgb * 2.0;
    c += texture(u_tex, v_uv + u_texel * vec2(0.0, -1.0)).rgb * 2.0;
    c += texture(u_tex, v_uv + u_texel * vec2(0.0, 1.0)).rgb * 2.0;
    c += texture(u_tex, v_uv + u_texel * vec2(-1.0, -1.0)).rgb;
    c += texture(u_tex, v_uv + u_texel * vec2(1.0, -1.0)).rgb;
    c += texture(u_tex, v_uv + u_texel * vec2(-1.0, 1.0)).rgb;
    c += texture(u_tex, v_uv + u_texel * vec2(1.0, 1.0)).rgb;
    fragColor = vec4(c / 16.0, 1.0);
}
"""

    val compositeFS = "#version 300 es\nprecision highp float;\n" + """
in vec2 v_uv;
uniform sampler2D u_scene;
uniform sampler2D u_bloom;
uniform sampler2D u_noise;
uniform float u_inScale;
uniform float u_bloomStrength;
uniform vec3 u_grade;
uniform float u_saturation;
uniform float u_contrast;
uniform float u_vignette;
uniform int u_underwater;
uniform vec3 u_waterFog;
uniform vec3 u_visor;
uniform vec2 u_noiseOffset;
out vec4 fragColor;
void main() {
    vec3 c = texture(u_scene, v_uv).rgb * u_inScale;
    vec3 b = texture(u_bloom, v_uv).rgb * u_inScale;
    c += b * u_bloomStrength;
    if (u_underwater == 1) {
        c = mix(c, vec3(dot(c, vec3(0.2, 0.6, 0.2))) * (u_waterFog * 6.0 + 0.3), 0.45);
    }
    c *= u_grade;
    // ACES filmic
    c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14);
    c = clamp(c, 0.0, 1.0);
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, u_saturation);
    c = mix(c, c * c * (3.0 - 2.0 * c), u_contrast);
    vec2 q = v_uv - 0.5;
    float vg = dot(q, q);
    c *= 1.0 - vg * u_vignette;
    c = mix(c, u_visor, clamp(vg * vg * 2.2, 0.0, 1.0) * 0.5 * step(0.001, dot(u_visor, vec3(1.0))));
    c = pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
    // dither to hide banding in the sky
    float n = texture(u_noise, gl_FragCoord.xy / 256.0 + u_noiseOffset).r - 0.5;
    c += n / 255.0;
    fragColor = vec4(c, l);
}
"""

    /** FXAA (lightweight variant of the classic algorithm). Expects luma in alpha. */
    val fxaaFS = "#version 300 es\nprecision highp float;\n" + """
in vec2 v_uv;
uniform sampler2D u_tex;
uniform vec2 u_texel;
out vec4 fragColor;
void main() {
    vec3 rgbM = texture(u_tex, v_uv).rgb;
    float lM = texture(u_tex, v_uv).a;
    float lNW = texture(u_tex, v_uv + vec2(-1.0, -1.0) * u_texel).a;
    float lNE = texture(u_tex, v_uv + vec2(1.0, -1.0) * u_texel).a;
    float lSW = texture(u_tex, v_uv + vec2(-1.0, 1.0) * u_texel).a;
    float lSE = texture(u_tex, v_uv + vec2(1.0, 1.0) * u_texel).a;
    float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE)));
    float lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));
    if (lMax - lMin < max(0.0312, lMax * 0.125)) { fragColor = vec4(rgbM, 1.0); return; }
    vec2 dir = vec2(-((lNW + lNE) - (lSW + lSE)), ((lNW + lSW) - (lNE + lSE)));
    float reduce = max((lNW + lNE + lSW + lSE) * 0.03125, 0.0078125);
    float rcp = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
    dir = clamp(dir * rcp, vec2(-8.0), vec2(8.0)) * u_texel;
    vec3 a = 0.5 * (texture(u_tex, v_uv + dir * (1.0 / 3.0 - 0.5)).rgb + texture(u_tex, v_uv + dir * (2.0 / 3.0 - 0.5)).rgb);
    vec3 b = a * 0.5 + 0.25 * (texture(u_tex, v_uv - dir * 0.5).rgb + texture(u_tex, v_uv + dir * 0.5).rgb);
    float lB = dot(b, vec3(0.2126, 0.7152, 0.0722));
    fragColor = vec4((lB < lMin || lB > lMax) ? a : b, 1.0);
}
"""

    /** Simple copy (used for refraction source and plain upscales). */
    val copyFS = "#version 300 es\nprecision highp float;\nin vec2 v_uv;\nuniform sampler2D u_tex;\nout vec4 fragColor;\nvoid main() { fragColor = texture(u_tex, v_uv); }\n"

    /** All shader pairs, for validation tooling. */
    fun all(): List<Triple<String, String, String>> = listOf(
        Triple("lit", litVS, litFS),
        Triple("inst", instVS, instFS),
        Triple("terrain", terrainVS, terrainFS),
        Triple("water", waterVS, waterFS),
        Triple("sky", skyVS, skyFS),
        Triple("depth", depthVS, depthFS),
        Triple("depthInst", depthInstVS, depthInstFS),
        Triple("particle", particleVS, particleFS),
        Triple("fx", fxVS, fxFS),
        Triple("unlit", unlitVS, unlitFS),
        Triple("down", postVS, downFS),
        Triple("up", postVS, upFS),
        Triple("composite", postVS, compositeFS),
        Triple("fxaa", postVS, fxaaFS),
        Triple("copy", postVS, copyFS),
    )
}
