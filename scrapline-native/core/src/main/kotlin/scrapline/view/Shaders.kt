package scrapline.view

import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.GdxRuntimeException

private const val PRECISION = "#ifdef GL_ES\nprecision highp float;\n#endif\n"

/** Lit world geometry: sun plus sky/ground ambient, sand ripples, soft shadows and distance haze. */
private const val WORLD_VERT = """
attribute vec3 a_position;
attribute vec3 a_normal;
attribute vec4 a_color;
uniform mat4 u_projView;
uniform mat4 u_shadowMat;
varying vec3 v_normal;
varying vec4 v_color;
varying vec3 v_pos;
varying vec4 v_shadow;
void main() {
  v_normal = a_normal;
  v_color = a_color;
  v_pos = a_position;
  v_shadow = u_shadowMat * vec4(a_position, 1.0);
  gl_Position = u_projView * vec4(a_position, 1.0);
}
"""

private const val WORLD_FRAG = PRECISION + """
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform vec3 u_skyAmb;
uniform vec3 u_groundAmb;
uniform vec3 u_fogColor;
uniform vec3 u_camPos;
uniform vec2 u_fog;
uniform float u_shadowOn;
uniform float u_alpha;
uniform sampler2D u_shadowMap;
varying vec3 v_normal;
varying vec4 v_color;
varying vec3 v_pos;
varying vec4 v_shadow;

float unpackDepth(vec4 c) {
  return dot(c.rgb, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
}

float shadowAt(vec2 uv, float depth) {
  return depth > unpackDepth(texture2D(u_shadowMap, uv)) ? 0.0 : 1.0;
}

void main() {
  vec3 base = v_color.rgb;
  float dist = distance(v_pos, u_camPos);
  float haze = clamp((dist - u_fog.x) / (u_fog.y - u_fog.x), 0.0, 1.0);
  haze *= haze;

  // Glow material: full brightness, only hazed.
  if (v_color.a > 0.5 && v_color.a < 0.8) {
    gl_FragColor = vec4(mix(base, u_fogColor, haze * u_fog.y / (u_fog.y + 1.0)), u_alpha);
    return;
  }

  vec3 n = normalize(v_normal);
  if (v_color.a < 0.5) {
    // Wind ripples on sand: thin wavy bands that nudge both colour and normal.
    float wave = sin(v_pos.x * 4.6 + v_pos.z * 1.9 + sin(v_pos.z * 0.7 + v_pos.x * 0.23) * 2.2);
    float fade = clamp(1.0 - dist / 45.0, 0.0, 1.0);
    base *= 1.0 + 0.05 * wave * fade;
    n = normalize(n + vec3(0.09, 0.0, 0.04) * wave * fade);
  }

  float ndl = max(dot(n, u_sunDir), 0.0);
  float lit = 1.0;
  if (u_shadowOn > 0.5) {
    vec3 sc = v_shadow.xyz / v_shadow.w * 0.5 + 0.5;
    if (sc.x > 0.0 && sc.x < 1.0 && sc.y > 0.0 && sc.y < 1.0 && sc.z < 1.0) {
      float d = sc.z - 0.0018 - 0.004 * (1.0 - ndl);
      float t = 1.0 / 2048.0;
      lit = 0.25 * (shadowAt(sc.xy + vec2(-t, -t), d) + shadowAt(sc.xy + vec2(t, -t), d)
                  + shadowAt(sc.xy + vec2(-t, t), d) + shadowAt(sc.xy + vec2(t, t), d));
      // Fade the shadow out toward the edge of the shadow map.
      vec2 edge = abs(sc.xy - 0.5) * 2.0;
      lit = mix(lit, 1.0, smoothstep(0.8, 1.0, max(edge.x, edge.y)));
    }
  }
  vec3 ambient = mix(u_groundAmb, u_skyAmb, n.y * 0.5 + 0.5);
  vec3 color = base * (ambient + u_sunColor * ndl * lit);
  gl_FragColor = vec4(mix(color, u_fogColor, haze), u_alpha);
}
"""

/** Shadow pass: writes depth packed into the colour buffer, so no depth-texture support is needed. */
private const val DEPTH_VERT = """
attribute vec3 a_position;
uniform mat4 u_projView;
void main() {
  gl_Position = u_projView * vec4(a_position, 1.0);
}
"""

private const val DEPTH_FRAG = PRECISION + """
void main() {
  float d = gl_FragCoord.z;
  vec3 enc = fract(d * vec3(1.0, 255.0, 65025.0));
  enc.xy -= enc.yz * (1.0 / 255.0);
  gl_FragColor = vec4(enc, 1.0);
}
"""

/** Sky: gradient from horizon to zenith, a sun or moon disc with a halo, and stars at night. */
private const val SKY_VERT = """
attribute vec3 a_position;
uniform mat4 u_projView;
uniform vec3 u_camPos;
varying vec3 v_dir;
void main() {
  v_dir = a_position;
  vec4 p = u_projView * vec4(a_position * 50.0 + u_camPos, 1.0);
  gl_Position = p.xyww;
}
"""

private const val SKY_FRAG = PRECISION + """
uniform vec3 u_zenith;
uniform vec3 u_horizon;
uniform vec3 u_sunDir;
uniform vec3 u_discColor;
uniform float u_night;
varying vec3 v_dir;

float hash(vec3 p) {
  p = fract(p * 0.3183099 + 0.1);
  p *= 17.0;
  return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

void main() {
  vec3 dir = normalize(v_dir);
  float up = clamp(dir.y, 0.0, 1.0);
  vec3 color = mix(u_horizon, u_zenith, pow(up, 0.55));
  float s = max(dot(dir, u_sunDir), 0.0);
  color += u_discColor * (smoothstep(0.9982, 0.9990, s) + 0.35 * pow(s, 90.0) + 0.12 * pow(s, 8.0));
  if (u_night > 0.01 && dir.y > 0.02) {
    vec3 cell = floor(dir * 320.0);
    float star = step(0.9988, hash(cell));
    color += vec3(0.8, 0.85, 1.0) * star * u_night * (0.4 + 0.6 * hash(cell + 7.0));
  }
  gl_FragColor = vec4(color, 1.0);
}
"""

private fun compile(name: String, vert: String, frag: String): ShaderProgram {
    val program = ShaderProgram(vert, frag)
    if (!program.isCompiled) throw GdxRuntimeException("Shader '$name' failed to compile:\n${program.log}")
    return program
}

class Shaders {
    val world = compile("world", WORLD_VERT, WORLD_FRAG)
    val depth = compile("depth", DEPTH_VERT, DEPTH_FRAG)
    val sky = compile("sky", SKY_VERT, SKY_FRAG)

    fun dispose() {
        world.dispose()
        depth.dispose()
        sky.dispose()
    }
}
