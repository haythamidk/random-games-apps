package com.hadal.game.engine

/**
 * GLSL ES 3.00 sources for the underwater renderer.
 *
 * Light model: sunlight enters from the surface and is absorbed per color channel with depth (red first, blue last),
 * caustics dance on up-facing surfaces in the shallows, the sub's floodlamp is a shadowed spot light whose light is
 * absorbed on the way out and back, and everything between the camera and a surface is lost to Beer-Lambert
 * extinction plus in-scattered ambient water color. Volumetric beams are added by a separate ray-marched pass.
 *
 * Vertex color alpha >= 0 is emissive strength; alpha < 0 selects a texture layer: layer = -alpha - 1 (see [Mat]).
 */
object Shaders {
    private const val VHEADER = "#version 300 es\nprecision highp float;\n"
    private const val FHEADER = "#version 300 es\nprecision highp float;\nprecision highp sampler2DShadow;\nprecision highp sampler2DArray;\n"

    private const val COMMON = """
uniform vec3 u_camPos;
uniform float u_time;
uniform vec3 u_sunAmb;
uniform vec3 u_ext;
uniform vec3 u_scatter;
uniform vec3 u_abyss;
uniform float u_causticStr;
uniform sampler2D u_caustics;
uniform vec3 u_lampPos;
uniform vec3 u_lampDir;
uniform vec3 u_lampColor;
uniform vec2 u_lampCone;
uniform float u_lampRange;
uniform int u_shadowMode;
uniform sampler2DShadow u_lampShadow;
uniform mat4 u_lampVP;
uniform float u_shadowTexel;
uniform vec4 u_lights[8];
uniform vec3 u_lightColors[8];
uniform sampler2DArray u_albedoArr;
uniform sampler2DArray u_normalArr;
uniform int u_texQuality;
uniform int u_hdr;
uniform float u_exposure;
uniform float u_outScale;

const float PI = 3.14159265;

vec3 sunAt(float y) { return u_sunAmb * exp(-u_ext * max(-y, 0.0)); }

float causticAt(vec3 p) {
    vec2 uv = p.xz * 0.045 + vec2(p.y * 0.012);
    float a = texture(u_caustics, uv + vec2(u_time * 0.011, u_time * 0.007)).r;
    float b = texture(u_caustics, uv * 1.31 + vec2(-u_time * 0.009, u_time * 0.012)).r;
    return min(a, b) * 2.6;
}

float lampShadow(vec3 wp, float ndl) {
    if (u_shadowMode == 0) return 1.0;
    vec4 sp = u_lampVP * vec4(wp, 1.0);
    if (sp.w <= 0.0) return 1.0;
    vec3 p = sp.xyz / sp.w * 0.5 + 0.5;
    if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0 || p.z > 1.0) return 1.0;
    float bias = 0.0004 + 0.0012 * (1.0 - ndl);
    float z = p.z - bias;
    if (u_shadowMode == 1) return texture(u_lampShadow, vec3(p.xy, z));
    float t = u_shadowTexel;
    float s = texture(u_lampShadow, vec3(p.xy + vec2(-0.7, -0.7) * t, z));
    s += texture(u_lampShadow, vec3(p.xy + vec2(0.7, -0.7) * t, z));
    s += texture(u_lampShadow, vec3(p.xy + vec2(-0.7, 0.7) * t, z));
    s += texture(u_lampShadow, vec3(p.xy + vec2(0.7, 0.7) * t, z));
    return s * 0.25;
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
    return min(D * G * F / (4.0 * nv), 10.0);
}

/** Full underwater shading of a surface point. */
vec3 shade(vec3 wp, vec3 n, vec3 albedo, float rough, float ao, float spec) {
    vec3 v = normalize(u_camPos - wp);
    vec3 sun = sunAt(wp.y);
    // light from the surface: a bright sky above, dim scattered light from the sides
    float up = n.y * 0.5 + 0.5;
    vec3 amb = sun * (0.22 + 0.78 * up * up) * ao + u_abyss * ao;
    vec3 direct = vec3(0.0);
    if (u_causticStr > 0.0 && n.y > 0.0) {
        float c = causticAt(wp);
        direct = sun * n.y * (0.35 + c * u_causticStr) * ao;
    } else direct = sun * max(n.y, 0.0) * 0.45 * ao;
    vec3 col = albedo * (amb * 0.6 + direct);
    // floodlamp
    vec3 L = u_lampPos - wp;
    float d = length(L);
    if (d < u_lampRange * 1.3 && u_lampColor.r + u_lampColor.g > 0.0) {
        vec3 l = L / d;
        float cone = smoothstep(u_lampCone.x, u_lampCone.y, dot(-l, u_lampDir));
        if (cone > 0.0) {
            float ndl = max(dot(n, l), 0.0);
            float att = 1.0 / (1.0 + d * d * 0.012) * smoothstep(u_lampRange * 1.3, u_lampRange * 0.5, d);
            vec3 trans = exp(-u_ext * d * 0.55);
            float sh = lampShadow(wp, ndl);
            vec3 lc = u_lampColor * cone * att * trans * sh;
            col += albedo * lc * (ndl * 0.92 + 0.08);
            col += lc * specGGX(n, v, l, rough) * ndl * spec;
        }
    }
    // point lights: flares, station lamps, bioluminescence
    for (int i = 0; i < 8; i++) {
        vec4 P = u_lights[i];
        if (P.w <= 0.0) continue;
        vec3 Lp = P.xyz - wp;
        float dp = length(Lp);
        if (dp > P.w) continue;
        vec3 lp = Lp / max(dp, 0.001);
        float att = 1.0 - dp / P.w;
        att *= att;
        float ndl = max(dot(n, lp), 0.0) * 0.85 + 0.15;
        vec3 lc = u_lightColors[i] * att * exp(-u_ext * dp * 0.6);
        col += albedo * lc * ndl + lc * specGGX(n, v, lp, rough) * ndl * spec * 0.6;
    }
    return col;
}

/** Light lost and gained between the surface point and the camera. */
vec3 water(vec3 col, vec3 wp) {
    float dist = length(wp - u_camPos);
    vec3 T = exp(-u_ext * dist);
    // in-scattered light grows with the distance and fades with depth along the way
    vec3 ins = u_scatter * (1.0 - T);
    return col * T + ins;
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

/** Normal map from the dominant projection axis, applied with a cheap tangent frame. */
vec3 triNormal(vec3 p, vec3 n, float layer, float scale, float strength, out float rough, out float ao) {
    vec3 w = abs(n);
    vec4 t;
    vec3 T; vec3 B;
    if (w.y >= w.x && w.y >= w.z) { t = texture(u_normalArr, vec3(p.xz * scale, layer)); T = vec3(1.0, 0.0, 0.0); B = vec3(0.0, 0.0, 1.0); }
    else if (w.x >= w.z) { t = texture(u_normalArr, vec3(p.zy * scale, layer)); T = vec3(0.0, 0.0, 1.0); B = vec3(0.0, 1.0, 0.0); }
    else { t = texture(u_normalArr, vec3(p.xy * scale, layer)); T = vec3(1.0, 0.0, 0.0); B = vec3(0.0, 1.0, 0.0); }
    rough = t.b; ao = t.a;
    vec2 d = (t.rg * 2.0 - 1.0) * strength;
    T = normalize(T - n * dot(n, T)); B = normalize(B - n * dot(n, B));
    return normalize(n + T * d.x + B * d.y);
}
"""

    // ------------------------------------------------------------------ meshes with a model matrix

    val litVS = VHEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_color;
uniform mat4 u_vp;
uniform mat4 u_model;
uniform vec4 u_swim;
uniform vec2 u_flap;
out vec3 v_wp;
out vec3 v_n;
out vec4 v_color;
void main() {
    vec3 lp = a_pos;
    // swimming: a travelling wave that grows toward the tail (-z)
    if (u_swim.x != 0.0) {
        float w = clamp(-lp.z * 0.5 + 0.3, 0.0, 2.0);
        float s = sin(lp.z * u_swim.y + u_swim.z) * u_swim.x * w;
        if (u_swim.w > 0.5) lp.y += s; else lp.x += s;
    }
    // wing flapping for rays
    if (u_flap.x != 0.0) lp.y += sin(u_flap.y - abs(lp.x) * 0.6) * u_flap.x * abs(lp.x) * 0.35;
    vec4 wp = u_model * vec4(lp, 1.0);
    v_wp = wp.xyz;
    v_n = normalize(mat3(u_model) * a_normal);
    v_color = vec4(pow(a_color.rgb, vec3(2.2)), a_color.a);
    gl_Position = u_vp * wp;
}
"""

    val litFS = FHEADER + COMMON + """
in vec3 v_wp;
in vec3 v_n;
in vec4 v_color;
uniform vec3 u_tint;
uniform float u_glow;
uniform float u_alpha;
uniform float u_wet;
out vec4 fragColor;
void main() {
    vec3 n = normalize(v_n);
    if (!gl_FrontFacing) n = -n;
    vec3 albedo = v_color.rgb * u_tint;
    float emissive = max(v_color.a, 0.0);
    float rough = 0.6;
    float ao = 1.0;
    if (v_color.a < -0.5 && u_texQuality > 0) {
        float layer = -v_color.a - 1.0;
        vec4 t = triSample(v_wp, n, layer, 0.35);
        albedo *= t.rgb * 2.0;
        if (u_texQuality > 1) n = triNormal(v_wp, n, layer, 0.35, 1.0, rough, ao);
    }
    vec3 col = shade(v_wp, n, albedo, rough * (1.0 - u_wet * 0.5), ao, 0.5 + u_wet);
    col += v_color.rgb * u_tint * emissive * u_glow * 2.2;
    fragColor = outColor(water(col, v_wp), u_alpha);
}
"""

    // ------------------------------------------------------------------ instanced flora

    val instVS = VHEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_color;
layout(location=3) in vec4 a_inst0;
layout(location=4) in vec4 a_inst1;
uniform mat4 u_vp;
uniform float u_time;
uniform vec3 u_current;
out vec3 v_wp;
out vec3 v_n;
out vec4 v_color;
out float v_tint;
void main() {
    float c = cos(a_inst0.w), s = sin(a_inst0.w);
    float sc = a_inst1.x;
    vec3 p = a_pos * sc;
    vec3 rp = vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c);
    vec3 n = vec3(a_normal.x * c + a_normal.z * s, a_normal.y, -a_normal.x * s + a_normal.z * c);
    float sway = a_inst1.z;
    if (sway > 0.0) {
        float h = max(a_pos.y, 0.0);
        float k = sway * pow(h * 0.12, 1.4) * 0.9;
        float ph = a_inst1.w * 0.37 + a_inst0.x * 0.05 + a_inst0.z * 0.04;
        rp.x += (sin(u_time * 0.7 + ph) * 0.8 + sin(u_time * 1.9 + ph * 2.3 + h * 0.6) * 0.2) * k + u_current.x * k;
        rp.z += (cos(u_time * 0.55 + ph * 1.3) * 0.6 + sin(u_time * 1.6 + h * 0.5) * 0.2) * k + u_current.z * k;
    }
    vec3 wp = rp + a_inst0.xyz;
    v_wp = wp;
    v_n = n;
    v_color = vec4(pow(a_color.rgb, vec3(2.2)), a_color.a);
    v_tint = a_inst1.y;
    gl_Position = u_vp * vec4(wp, 1.0);
}
"""

    val instFS = FHEADER + COMMON + """
in vec3 v_wp;
in vec3 v_n;
in vec4 v_color;
in float v_tint;
uniform float u_glowPulse;
out vec4 fragColor;
void main() {
    vec3 n = normalize(v_n);
    if (!gl_FrontFacing) n = -n;
    // per instance color variation
    vec3 tint = mix(vec3(0.82, 0.92, 1.08), vec3(1.15, 1.02, 0.86), v_tint);
    vec3 albedo = v_color.rgb * tint;
    float emissive = max(v_color.a, 0.0);
    float rough = 0.7;
    float ao = 1.0;
    if (v_color.a < -0.5 && u_texQuality > 0) {
        float layer = -v_color.a - 1.0;
        vec4 t = triSample(v_wp, n, layer, 0.6);
        albedo *= t.rgb * 2.0;
        if (u_texQuality > 1) n = triNormal(v_wp, n, layer, 0.6, 1.0, rough, ao);
    }
    // thin kelp blades let light through from behind
    vec3 col = shade(v_wp, n, albedo, rough, ao, 0.3);
    vec3 back = shade(v_wp, -n, albedo, rough, 1.0, 0.0);
    col += back * 0.25;
    float pulse = 0.75 + 0.25 * sin(u_time * 1.3 + v_wp.x * 0.3 + v_wp.z * 0.2);
    col += v_color.rgb * tint * emissive * pulse * u_glowPulse * 2.0;
    fragColor = outColor(water(col, v_wp), 1.0);
}
"""

    // ------------------------------------------------------------------ sea floor

    val terrainVS = VHEADER + """
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec4 a_splat;
layout(location=5) in vec4 a_extra;
uniform mat4 u_vp;
out vec3 v_wp;
out vec3 v_n;
out vec4 v_splat;
out float v_cav;
void main() {
    v_wp = a_pos;
    v_n = a_normal;
    v_splat = a_splat;
    v_cav = a_extra.x;
    gl_Position = u_vp * vec4(a_pos, 1.0);
}
"""

    val terrainFS = FHEADER + COMMON + """
in vec3 v_wp;
in vec3 v_n;
in vec4 v_splat;
in float v_cav;
out vec4 fragColor;

vec3 flatColor(int i) {
    if (i == 0) return vec3(0.62, 0.58, 0.48);
    if (i == 1) return vec3(0.3, 0.28, 0.25);
    if (i == 2) return vec3(0.12, 0.115, 0.12);
    if (i == 3) return vec3(0.55, 0.4, 0.38);
    return vec3(0.33, 0.33, 0.34);
}

void main() {
    vec3 n = normalize(v_n);
    float rockW = smoothstep(0.86, 0.62, n.y);
    vec4 w = v_splat;
    vec3 albedo;
    float rough = 0.9;
    float ao = 1.0;
    vec3 nn = n;
    if (u_texQuality == 0) {
        albedo = flatColor(0) * w.x + flatColor(1) * w.y + flatColor(2) * w.z + flatColor(3) * w.w;
        albedo = mix(albedo, flatColor(4), rockW);
    } else {
        // height aware blending between the floor layers
        vec4 sand = texture(u_albedoArr, vec3(v_wp.xz * 0.22, 0.0));
        vec4 silt = texture(u_albedoArr, vec3(v_wp.xz * 0.16, 1.0));
        vec4 bas = triSample(v_wp, n, 3.0, 0.11);
        vec4 cor = texture(u_albedoArr, vec3(v_wp.xz * 0.2, 4.0));
        vec4 hw = w * (vec4(sand.a, silt.a, bas.a, cor.a) * 0.6 + 0.4);
        float m = max(max(hw.x, hw.y), max(hw.z, hw.w)) - 0.18;
        hw = max(hw - m, 0.0);
        hw /= max(hw.x + hw.y + hw.z + hw.w, 1e-4);
        albedo = sand.rgb * hw.x + silt.rgb * hw.y + bas.rgb * hw.z + cor.rgb * hw.w;
        vec4 rock = triSample(v_wp, n, 2.0, 0.09);
        float rw = clamp(rockW * (0.6 + rock.a * 0.8), 0.0, 1.0);
        albedo = mix(albedo, rock.rgb * (w.z > 0.5 ? 0.55 : 1.0), rw);
        // detail: large scale tone variation breaks up tiling
        float big = texture(u_albedoArr, vec3(v_wp.xz * 0.013, 1.0)).r;
        albedo *= 0.8 + big * 0.45;
        if (u_texQuality > 1) {
            float layer = rw > 0.5 ? 2.0 : (hw.x >= max(max(hw.y, hw.z), hw.w) ? 0.0 : (hw.y >= max(hw.z, hw.w) ? 1.0 : (hw.z >= hw.w ? 3.0 : 4.0)));
            float sc = layer == 2.0 ? 0.09 : (layer == 0.0 ? 0.22 : (layer == 1.0 ? 0.16 : (layer == 3.0 ? 0.11 : 0.2)));
            nn = triNormal(v_wp, n, layer, sc, 1.2, rough, ao);
        }
    }
    ao *= 1.0 - v_cav * 0.55;
    vec3 col = shade(v_wp, nn, albedo, rough, ao, 0.15);
    fragColor = outColor(water(col, v_wp), 1.0);
}
"""

    // ------------------------------------------------------------------ open water background

    val skyVS = VHEADER + """
layout(location=0) in vec3 a_pos;
uniform mat4 u_invVP;
out vec3 v_dir;
void main() {
    vec4 far = u_invVP * vec4(a_pos.xy, 1.0, 1.0);
    vec4 near = u_invVP * vec4(a_pos.xy, -1.0, 1.0);
    v_dir = far.xyz / far.w - near.xyz / near.w;
    gl_Position = vec4(a_pos.xy, 0.9999, 1.0);
}
"""

    val skyFS = FHEADER + COMMON + """
in vec3 v_dir;
out vec4 fragColor;
void main() {
    vec3 d = normalize(v_dir);
    // ambient water seen to infinity: brighter looking up, black looking down
    float up = d.y * 0.5 + 0.5;
    vec3 col = u_scatter * (0.35 + 1.1 * pow(up, 2.2)) + u_abyss * 0.3;
    float camDepth = max(-u_camPos.y, 0.0);
    if (d.y > 0.0 && camDepth < 160.0) {
        // the surface seen from below: Snell's window and total internal reflection outside it
        float t = camDepth / d.y;
        vec3 T = exp(-u_ext * t);
        vec2 hit = u_camPos.xz + d.xz * t;
        float ripple = texture(u_caustics, hit * 0.03 + vec2(u_time * 0.01, u_time * 0.013)).r;
        float ripple2 = texture(u_caustics, hit * 0.021 - vec2(u_time * 0.008, 0.0)).r;
        float window = smoothstep(0.62, 0.7, d.y + (ripple - ripple2) * 0.05);
        vec3 sky = u_sunAmb * (1.6 + 2.5 * pow(max(d.y, 0.0), 24.0)) * (0.8 + 0.4 * ripple);
        vec3 tir = u_scatter * 1.4 * (0.7 + 0.6 * ripple2);
        vec3 surf = mix(tir, sky, window);
        col = mix(col, surf, T);
    }
    fragColor = outColor(col, 1.0);
}
"""

    // ------------------------------------------------------------------ shadow depth

    val depthVS = VHEADER + """
layout(location=0) in vec3 a_pos;
uniform mat4 u_vp;
uniform mat4 u_model;
void main() { gl_Position = u_vp * u_model * vec4(a_pos, 1.0); }
"""

    val depthFS = "#version 300 es\nprecision mediump float;\nvoid main() {}\n"

    val depthInstVS = VHEADER + """
layout(location=0) in vec3 a_pos;
layout(location=3) in vec4 a_inst0;
layout(location=4) in vec4 a_inst1;
uniform mat4 u_vp;
uniform float u_time;
void main() {
    float c = cos(a_inst0.w), s = sin(a_inst0.w);
    vec3 p = a_pos * a_inst1.x;
    vec3 rp = vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c);
    float sway = a_inst1.z;
    if (sway > 0.0) {
        float h = max(a_pos.y, 0.0);
        float k = sway * pow(h * 0.12, 1.4) * 0.9;
        float ph = a_inst1.w * 0.37 + a_inst0.x * 0.05 + a_inst0.z * 0.04;
        rp.x += (sin(u_time * 0.7 + ph) * 0.8 + sin(u_time * 1.9 + ph * 2.3 + h * 0.6) * 0.2) * k;
        rp.z += (cos(u_time * 0.55 + ph * 1.3) * 0.6 + sin(u_time * 1.6 + h * 0.5) * 0.2) * k;
    }
    gl_Position = u_vp * vec4(rp + a_inst0.xyz, 1.0);
}
"""

    // ------------------------------------------------------------------ marine snow and glows

    val snowVS = VHEADER + """
layout(location=0) in vec3 a_pos;
uniform mat4 u_vp;
uniform vec3 u_camPos;
uniform float u_time;
uniform float u_box;
uniform float u_pxScale;
uniform vec3 u_lampPos;
uniform vec3 u_lampDir;
uniform vec3 u_lampColor;
uniform vec2 u_lampCone;
uniform float u_lampRange;
uniform vec3 u_sunAmb;
uniform vec3 u_ext;
out vec3 v_col;
out float v_a;
void main() {
    vec3 drift = vec3(sin(u_time * 0.1 + a_pos.z * 6.0) * 0.6, -u_time * 0.12, cos(u_time * 0.08 + a_pos.x * 5.0) * 0.5);
    vec3 p = a_pos * u_box + drift;
    vec3 wp = u_camPos + mod(p - u_camPos, u_box) - u_box * 0.5;
    float d = length(wp - u_camPos);
    vec3 L = wp - u_lampPos;
    float dl = length(L);
    float cone = smoothstep(u_lampCone.x, u_lampCone.y, dot(L / max(dl, 0.001), u_lampDir));
    vec3 lit = u_lampColor * cone * smoothstep(u_lampRange, 0.0, dl) * exp(-u_ext * (dl + d)) * 1.6;
    lit += u_sunAmb * exp(-u_ext * max(-wp.y, 0.0)) * 0.35 * exp(-u_ext * d);
    v_col = lit;
    v_a = smoothstep(u_box * 0.5, u_box * 0.3, d) * smoothstep(0.3, 1.5, d);
    vec4 cp = u_vp * vec4(wp, 1.0);
    gl_Position = cp;
    gl_PointSize = clamp(u_pxScale * (0.035 + fract(a_pos.y * 37.0) * 0.03) / max(cp.w, 0.1), 1.0, 14.0);
}
"""

    val snowFS = "#version 300 es\nprecision mediump float;\n" + """
in vec3 v_col;
in float v_a;
uniform int u_hdr;
uniform float u_exposure;
uniform float u_outScale;
out vec4 fragColor;
void main() {
    vec2 q = gl_PointCoord * 2.0 - 1.0;
    float a = max(0.0, 1.0 - dot(q, q));
    a = a * a * v_a;
    vec3 c = v_col * u_exposure;
    if (u_hdr == 1) c *= u_outScale; else c = pow(c / (1.0 + c), vec3(1.0 / 2.2));
    fragColor = vec4(c * a, a);
}
"""

    /** Camera facing glow sprites (lamp lenses, flares, lures, eyes). Additive. */
    val glowVS = VHEADER + """
layout(location=0) in vec3 a_pos;
uniform mat4 u_vp;
uniform vec3 u_center;
uniform vec3 u_right;
uniform vec3 u_up;
uniform float u_size;
out vec2 v_uv;
void main() {
    v_uv = a_pos.xy;
    vec3 wp = u_center + (u_right * a_pos.x + u_up * a_pos.y) * u_size;
    gl_Position = u_vp * vec4(wp, 1.0);
}
"""

    val glowFS = "#version 300 es\nprecision mediump float;\n" + """
in vec2 v_uv;
uniform vec3 u_color;
uniform float u_streak;
uniform int u_hdr;
uniform float u_outScale;
out vec4 fragColor;
void main() {
    float r = length(v_uv);
    float core = exp(-r * r * 9.0);
    float halo = exp(-r * 3.2) * 0.35;
    float streak = exp(-abs(v_uv.y) * 40.0) * exp(-abs(v_uv.x) * 2.0) * u_streak;
    vec3 c = u_color * (core + halo + streak);
    if (u_hdr == 1) c *= u_outScale; else c = c / (1.0 + c);
    fragColor = vec4(c, 1.0);
}
"""

    // ------------------------------------------------------------------ volumetric light (half resolution)

    val postVS = VHEADER + """
layout(location=0) in vec3 a_pos;
out vec2 v_uv;
void main() {
    v_uv = a_pos.xy * 0.5 + 0.5;
    gl_Position = vec4(a_pos.xy, 0.0, 1.0);
}
"""

    val volumeFS = FHEADER + """
in vec2 v_uv;
uniform sampler2D u_depth;
uniform sampler2D u_noise;
uniform sampler2D u_caustics;
uniform sampler2DShadow u_lampShadow;
uniform mat4 u_lampVP;
uniform int u_shadowMode;
uniform mat4 u_invVP;
uniform vec3 u_camPos;
uniform vec3 u_lampPos;
uniform vec3 u_lampDir;
uniform vec3 u_lampColor;
uniform vec2 u_lampCone;
uniform float u_lampRange;
uniform vec3 u_sunAmb;
uniform vec3 u_ext;
uniform float u_time;
uniform int u_steps;
uniform float u_maxDist;
uniform float u_density;
uniform vec4 u_lights[4];
uniform vec3 u_lightColors[4];
uniform vec2 u_noiseOffset;
out vec4 fragColor;

float hg(float c, float g) { float g2 = g * g; return (1.0 - g2) / (4.0 * 3.14159 * pow(1.0 + g2 - 2.0 * g * c, 1.5)); }
float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }

void main() {
    float z = texture(u_depth, v_uv).r;
    vec4 wp4 = u_invVP * vec4(v_uv * 2.0 - 1.0, z * 2.0 - 1.0, 1.0);
    vec3 wp = wp4.xyz / wp4.w;
    vec3 rd = wp - u_camPos;
    float sceneDist = length(rd);
    rd /= max(sceneDist, 1e-4);
    float maxD = min(sceneDist, u_maxDist);
    float jitter = ign(gl_FragCoord.xy + u_noiseOffset * 97.0);
    float stepLen = maxD / float(u_steps);
    vec3 acc = vec3(0.0);
    float phaseSun = hg(rd.y, 0.6) * 4.0;
    for (int i = 0; i < 24; i++) {
        if (i >= u_steps) break;
        float t = (float(i) + jitter) * stepLen;
        vec3 x = u_camPos + rd * t;
        vec3 camT = exp(-u_ext * t);
        // floodlamp beam
        vec3 L = x - u_lampPos;
        float dl = length(L);
        vec3 ld = L / max(dl, 1e-3);
        float cone = smoothstep(u_lampCone.x, u_lampCone.y, dot(ld, u_lampDir));
        if (cone > 0.0 && dl < u_lampRange * 1.2) {
            float sh = 1.0;
            if (u_shadowMode > 0) {
                vec4 sp = u_lampVP * vec4(x, 1.0);
                vec3 p = sp.xyz / sp.w * 0.5 + 0.5;
                if (sp.w > 0.0 && p.x > 0.0 && p.x < 1.0 && p.y > 0.0 && p.y < 1.0 && p.z < 1.0) sh = texture(u_lampShadow, vec3(p.xy, p.z - 0.001));
            }
            float att = 1.0 / (1.0 + dl * dl * 0.02) * smoothstep(u_lampRange * 1.2, u_lampRange * 0.3, dl);
            float ph = hg(dot(rd, -ld), 0.55) * 6.0 + 0.15;
            acc += u_lampColor * cone * att * sh * ph * exp(-u_ext * (dl + t) * 0.55);
        }
        // sun shafts in the shallows: light falling through moving surface ripples
        vec3 sun = u_sunAmb * exp(-u_ext * max(-x.y, 0.0));
        if (sun.g > 0.002) {
            vec2 uv = (x.xz + vec2(x.y * 0.25, x.y * 0.12)) * 0.022;
            float s1 = texture(u_caustics, uv + vec2(u_time * 0.004, u_time * 0.003)).r;
            float s2 = texture(u_caustics, uv * 0.73 - vec2(u_time * 0.003, 0.0)).r;
            float shaft = pow(s1 * s2 * 4.0, 1.6);
            acc += sun * (0.12 + shaft * 1.8) * phaseSun * camT * 0.35;
        }
        // flares and nearby glows
        for (int k = 0; k < 4; k++) {
            vec4 P = u_lights[k];
            if (P.w <= 0.0) continue;
            float dp = length(P.xyz - x);
            float a = 1.0 / (1.0 + dp * dp * 0.25) * smoothstep(P.w, 0.0, dp);
            acc += u_lightColors[k] * a * exp(-u_ext * dp) * camT * 0.5;
        }
    }
    fragColor = vec4(acc * stepLen * u_density, 1.0);
}
"""

    // ------------------------------------------------------------------ bloom and final composite

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
uniform sampler2D u_volume;
uniform sampler2D u_depth;
uniform sampler2D u_noise;
uniform float u_inScale;
uniform float u_bloomStrength;
uniform float u_volumeOn;
uniform float u_vignette;
uniform vec2 u_noiseOffset;
uniform float u_time;
uniform float u_damage;
uniform float u_fear;
uniform float u_pulse;
uniform float u_lowO2;
uniform float u_aberration;
uniform mat4 u_invVP;
uniform vec3 u_camPos;
uniform vec3 u_pingOrigin;
uniform float u_pingRadius;
uniform float u_pingFade;
uniform float u_dark;
out vec4 fragColor;
void main() {
    vec2 q = v_uv - 0.5;
    float r2 = dot(q, q);
    // slight barrel distortion and chromatic fringe from the viewport glass
    vec2 uv = v_uv + q * r2 * 0.06;
    vec2 ca = q * r2 * u_aberration;
    vec3 c;
    c.r = texture(u_scene, uv + ca).r;
    c.g = texture(u_scene, uv).g;
    c.b = texture(u_scene, uv - ca).b;
    c *= u_inScale;
    c += texture(u_bloom, uv).rgb * u_inScale * u_bloomStrength;
    if (u_volumeOn > 0.0) c += texture(u_volume, uv).rgb;
    // sonar wave washing over the geometry
    if (u_pingFade > 0.0) {
        float z = texture(u_depth, uv).r;
        if (z < 0.9999) {
            vec4 wp4 = u_invVP * vec4(uv * 2.0 - 1.0, z * 2.0 - 1.0, 1.0);
            vec3 wp = wp4.xyz / wp4.w;
            float d = length(wp - u_pingOrigin);
            float ring = exp(-abs(d - u_pingRadius) * 0.5);
            float trail = smoothstep(u_pingRadius, u_pingRadius - 40.0, d) * step(d, u_pingRadius) * 0.15;
            float grid = smoothstep(0.92, 1.0, abs(sin(wp.x * 0.5)) ) + smoothstep(0.92, 1.0, abs(sin(wp.z * 0.5)));
            c += vec3(0.15, 0.85, 1.0) * (ring * 0.9 + trail * (0.3 + grid * 0.5)) * u_pingFade;
        }
    }
    c += vec3(0.4, 0.8, 1.0) * u_pulse * 0.6;
    // ACES filmic
    c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14);
    c = clamp(c, 0.0, 1.0);
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    // fear drains the color and adds sensor static
    c = mix(c, vec3(l), u_fear * 0.55);
    vec2 gp = gl_FragCoord.xy + u_noiseOffset * 311.0;
    float n = fract(sin(dot(gp, vec2(12.9898, 78.233))) * 43758.5453);
    float line = step(0.992, fract(v_uv.y * 2.0 + u_time * 0.5)) * u_fear;
    c += (n - 0.5) * (0.014 + u_fear * 0.08) + line * 0.04;
    c = mix(c, vec3(0.5, 0.02, 0.0), u_damage * 0.35 * smoothstep(0.1, 0.5, r2 + 0.1));
    c *= 1.0 - r2 * (u_vignette + u_lowO2 * 2.5);
    c *= 1.0 - u_dark;
    c = pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
    c += (n - 0.5) / 255.0;
    fragColor = vec4(c, dot(c, vec3(0.299, 0.587, 0.114)));
}
"""

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
    float lB = dot(b, vec3(0.299, 0.587, 0.114));
    fragColor = vec4((lB < lMin || lB > lMax) ? a : b, 1.0);
}
"""

    val copyFS = "#version 300 es\nprecision highp float;\nin vec2 v_uv;\nuniform sampler2D u_tex;\nout vec4 fragColor;\nvoid main() { fragColor = texture(u_tex, v_uv); }\n"

    fun all(): List<Triple<String, String, String>> = listOf(
        Triple("lit", litVS, litFS), Triple("inst", instVS, instFS), Triple("terrain", terrainVS, terrainFS),
        Triple("sky", skyVS, skyFS), Triple("depth", depthVS, depthFS), Triple("depthInst", depthInstVS, depthFS),
        Triple("snow", snowVS, snowFS), Triple("glow", glowVS, glowFS), Triple("volume", postVS, volumeFS),
        Triple("down", postVS, downFS), Triple("up", postVS, upFS), Triple("composite", postVS, compositeFS),
        Triple("fxaa", postVS, fxaaFS), Triple("copy", postVS, copyFS),
    )
}
