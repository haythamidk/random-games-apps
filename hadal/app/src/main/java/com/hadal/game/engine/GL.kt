package com.hadal.game.engine

import android.opengl.GLES30.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/** Vertex layout shared by every mesh: position(3) normal(3) color(4: rgb + emissive). */
const val VERTEX_FLOATS = 10
const val VERTEX_STRIDE = VERTEX_FLOATS * 4
const val INSTANCE_FLOATS = 8
const val ATTR_POS = 0
const val ATTR_NORMAL = 1
const val ATTR_COLOR = 2
const val ATTR_INST0 = 3
const val ATTR_INST1 = 4
const val ATTR_EXTRA = 5
/** Terrain layout: position(3) normal(3) splatA(4) splatB(4). */
const val TERRAIN_FLOATS = 14

object GLUtil {
    private const val TAG = "HadalGL"

    fun floatBuffer(data: FloatArray, count: Int = data.size): FloatBuffer {
        val fb = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data, 0, count).position(0)
        return fb
    }

    fun intBuffer(data: IntArray, count: Int = data.size): IntBuffer {
        val ib = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        ib.put(data, 0, count).position(0)
        return ib
    }

    fun genBuffer(): Int { val a = IntArray(1); glGenBuffers(1, a, 0); return a[0] }
    fun genVao(): Int { val a = IntArray(1); glGenVertexArrays(1, a, 0); return a[0] }
    fun genTexture(): Int { val a = IntArray(1); glGenTextures(1, a, 0); return a[0] }
    fun deleteBuffer(id: Int) { if (id != 0) glDeleteBuffers(1, intArrayOf(id), 0) }
    fun deleteVao(id: Int) { if (id != 0) glDeleteVertexArrays(1, intArrayOf(id), 0) }

    fun compile(type: Int, src: String, name: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetShaderInfoLog(s)
            Log.e(TAG, "Shader compile failed ($name): $log")
            glDeleteShader(s)
            throw RuntimeException("Shader $name: $log")
        }
        return s
    }

    fun checkError(where: String) {
        val e = glGetError()
        if (e != GL_NO_ERROR) Log.w(TAG, "GL error 0x${Integer.toHexString(e)} at $where")
    }
}

class Shader(vs: String, fs: String, val name: String) {
    val id: Int
    private val locs = HashMap<String, Int>()

    init {
        val v = GLUtil.compile(GL_VERTEX_SHADER, vs, "$name.vs")
        val f = GLUtil.compile(GL_FRAGMENT_SHADER, fs, "$name.fs")
        id = glCreateProgram()
        glAttachShader(id, v)
        glAttachShader(id, f)
        glLinkProgram(id)
        val ok = IntArray(1)
        glGetProgramiv(id, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetProgramInfoLog(id)
            throw RuntimeException("Link $name: $log")
        }
        glDeleteShader(v)
        glDeleteShader(f)
    }

    fun use() = glUseProgram(id)
    fun loc(n: String): Int = locs.getOrPut(n) { glGetUniformLocation(id, n) }
    fun f1(n: String, v: Float) { val l = loc(n); if (l >= 0) glUniform1f(l, v) }
    fun i1(n: String, v: Int) { val l = loc(n); if (l >= 0) glUniform1i(l, v) }
    fun f2(n: String, a: Float, b: Float) { val l = loc(n); if (l >= 0) glUniform2f(l, a, b) }
    fun f3(n: String, a: Float, b: Float, c: Float) { val l = loc(n); if (l >= 0) glUniform3f(l, a, b, c) }
    fun f3(n: String, v: FloatArray) { val l = loc(n); if (l >= 0) glUniform3f(l, v[0], v[1], v[2]) }
    fun f4(n: String, a: Float, b: Float, c: Float, d: Float) { val l = loc(n); if (l >= 0) glUniform4f(l, a, b, c, d) }
    fun f3v(n: String, arr: FloatArray, count: Int) { val l = loc(n); if (l >= 0) glUniform3fv(l, count, arr, 0) }
    fun f4v(n: String, arr: FloatArray, count: Int) { val l = loc(n); if (l >= 0) glUniform4fv(l, count, arr, 0) }
    fun m4(n: String, m: FloatArray) { val l = loc(n); if (l >= 0) glUniformMatrix4fv(l, 1, false, m, 0) }
}

/** CPU-side mesh data, buildable on any thread. */
class MeshData(val vertices: FloatArray, val vertexCount: Int, val indices: IntArray, val indexCount: Int, val stride: Int = VERTEX_FLOATS) {
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

    init {
        for (i in 0 until vertexCount) {
            val o = i * stride
            val x = vertices[o]; val y = vertices[o + 1]; val z = vertices[o + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
        if (vertexCount == 0) { minX = 0f; minY = 0f; minZ = 0f; maxX = 0f; maxY = 0f; maxZ = 0f }
    }
}

/** GPU mesh. Must be created and drawn on the GL thread. */
class Mesh(data: MeshData, val primitive: Int = GL_TRIANGLES) {
    val vao = GLUtil.genVao()
    private val vbo = GLUtil.genBuffer()
    private val ibo = GLUtil.genBuffer()
    val indexCount = data.indexCount
    val vertexCount = data.vertexCount
    val minX = data.minX; val minY = data.minY; val minZ = data.minZ
    val maxX = data.maxX; val maxY = data.maxY; val maxZ = data.maxZ
    var released = false
        private set

    init {
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        val strideB = data.stride * 4
        glBufferData(GL_ARRAY_BUFFER, data.vertexCount * strideB, GLUtil.floatBuffer(data.vertices, data.vertexCount * data.stride), GL_STATIC_DRAW)
        glEnableVertexAttribArray(ATTR_POS)
        glVertexAttribPointer(ATTR_POS, 3, GL_FLOAT, false, strideB, 0)
        glEnableVertexAttribArray(ATTR_NORMAL)
        glVertexAttribPointer(ATTR_NORMAL, 3, GL_FLOAT, false, strideB, 12)
        glEnableVertexAttribArray(ATTR_COLOR)
        glVertexAttribPointer(ATTR_COLOR, 4, GL_FLOAT, false, strideB, 24)
        if (data.stride >= TERRAIN_FLOATS) {
            glEnableVertexAttribArray(ATTR_EXTRA)
            glVertexAttribPointer(ATTR_EXTRA, 4, GL_FLOAT, false, strideB, 40)
        }
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, data.indexCount * 4, GLUtil.intBuffer(data.indices, data.indexCount), GL_STATIC_DRAW)
        glBindVertexArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
    }

    fun draw() {
        if (indexCount == 0) return
        glBindVertexArray(vao)
        glDrawElements(primitive, indexCount, GL_UNSIGNED_INT, 0)
    }

    /** Draws only the first [count] indices (used to skip terrain skirts in the shadow pass). */
    fun drawIndices(count: Int) {
        if (count <= 0) return
        glBindVertexArray(vao)
        glDrawElements(primitive, minOf(count, indexCount), GL_UNSIGNED_INT, 0)
    }

    fun drawArrays(mode: Int, count: Int = vertexCount) {
        if (count <= 0) return
        glBindVertexArray(vao)
        glDrawArrays(mode, 0, count)
    }

    fun drawInstanced(inst: InstanceBuffer) {
        if (indexCount == 0 || inst.count == 0) return
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, inst.vbo)
        glEnableVertexAttribArray(ATTR_INST0)
        glVertexAttribPointer(ATTR_INST0, 4, GL_FLOAT, false, INSTANCE_FLOATS * 4, 0)
        glVertexAttribDivisor(ATTR_INST0, 1)
        glEnableVertexAttribArray(ATTR_INST1)
        glVertexAttribPointer(ATTR_INST1, 4, GL_FLOAT, false, INSTANCE_FLOATS * 4, 16)
        glVertexAttribDivisor(ATTR_INST1, 1)
        glDrawElementsInstanced(primitive, indexCount, GL_UNSIGNED_INT, 0, inst.count)
        glDisableVertexAttribArray(ATTR_INST0)
        glDisableVertexAttribArray(ATTR_INST1)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
    }

    fun release() {
        if (released) return
        released = true
        GLUtil.deleteBuffer(vbo)
        GLUtil.deleteBuffer(ibo)
        GLUtil.deleteVao(vao)
    }
}

/** Per-instance data: (x, y, z, yaw) (scale, tint, sway, extra). */
class InstanceBuffer {
    val vbo = GLUtil.genBuffer()
    var count = 0
        private set

    fun upload(data: FloatArray, instances: Int) {
        count = instances
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        if (instances > 0) {
            glBufferData(GL_ARRAY_BUFFER, instances * INSTANCE_FLOATS * 4, GLUtil.floatBuffer(data, instances * INSTANCE_FLOATS), GL_STATIC_DRAW)
        } else {
            glBufferData(GL_ARRAY_BUFFER, 4, null, GL_STATIC_DRAW)
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0)
    }

    fun release() = GLUtil.deleteBuffer(vbo)
}

object Textures {
    fun createRGBA(size: Int, pixels: ByteArray): Int {
        val id = GLUtil.genTexture()
        glBindTexture(GL_TEXTURE_2D, id)
        val bb = ByteBuffer.allocateDirect(pixels.size).order(ByteOrder.nativeOrder())
        bb.put(pixels).position(0)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, bb)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glGenerateMipmap(GL_TEXTURE_2D)
        glBindTexture(GL_TEXTURE_2D, 0)
        return id
    }
}

/** Depth-only framebuffer with hardware PCF comparison sampling. */
class ShadowMap(val size: Int) {
    val texture: Int = GLUtil.genTexture()
    val fbo: Int

    init {
        glBindTexture(GL_TEXTURE_2D, texture)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, size, size, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL)
        val ids = IntArray(1)
        glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, texture, 0)
        glDrawBuffers(1, intArrayOf(GL_NONE), 0)
        glReadBuffer(GL_NONE)
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        if (status != GL_FRAMEBUFFER_COMPLETE) Log.e("HadalGL", "Shadow FBO incomplete: $status")
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    fun release() {
        glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        glDeleteTextures(1, intArrayOf(texture), 0)
    }
}


object GLCaps {
    var extensions = ""
        private set
    val halfFloatTargets get() = extensions.contains("GL_EXT_color_buffer_half_float") || extensions.contains("GL_EXT_color_buffer_float")
    val anisotropy get() = extensions.contains("GL_EXT_texture_filter_anisotropic")
    fun init() { extensions = glGetString(GL_EXTENSIONS) ?: "" }
}

/** 2D texture array built from equally sized RGBA layers. */
class TextureArray(val size: Int, layers: List<ByteArray>, anisotropy: Float) {
    val id: Int = GLUtil.genTexture()
    init {
        glBindTexture(GL_TEXTURE_2D_ARRAY, id)
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, size, size, layers.size, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        val bb = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        for ((i, l) in layers.withIndex()) {
            bb.clear(); bb.put(l, 0, size * size * 4); bb.position(0)
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, i, size, size, 1, GL_RGBA, GL_UNSIGNED_BYTE, bb)
        }
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY)
        if (anisotropy > 1f && GLCaps.anisotropy) glTexParameterf(GL_TEXTURE_2D_ARRAY, 0x84FE, anisotropy)
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0)
    }
    fun release() = glDeleteTextures(1, intArrayOf(id), 0)
}

/** Offscreen color target with optional depth renderbuffer. */
class RenderTarget(val width: Int, val height: Int, hdr: Boolean, withDepth: Boolean, depthTexture: Boolean = false) {
    val texture = GLUtil.genTexture()
    val fbo: Int
    private var depthRb = 0
    /** Sampleable depth texture (only when created with depthTexture = true). */
    var depthTex = 0
        private set
    init {
        glBindTexture(GL_TEXTURE_2D, texture)
        if (hdr) glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_HALF_FLOAT, null)
        else glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        val ids = IntArray(1)
        glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        if (withDepth && depthTexture) {
            depthTex = GLUtil.genTexture()
            glBindTexture(GL_TEXTURE_2D, depthTex)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, width, height, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, null)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTex, 0)
        } else if (withDepth) {
            glGenRenderbuffers(1, ids, 0)
            depthRb = ids[0]
            glBindRenderbuffer(GL_RENDERBUFFER, depthRb)
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRb)
        }
        val st = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        if (st != GL_FRAMEBUFFER_COMPLETE) Log.e("HadalGL", "RenderTarget incomplete $st hdr=$hdr")
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glBindTexture(GL_TEXTURE_2D, 0)
    }
    val complete: Boolean get() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        val ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        return ok
    }
    fun bind() { glBindFramebuffer(GL_FRAMEBUFFER, fbo); glViewport(0, 0, width, height) }
    fun release() {
        glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        glDeleteTextures(1, intArrayOf(texture), 0)
        if (depthRb != 0) glDeleteRenderbuffers(1, intArrayOf(depthRb), 0)
        if (depthTex != 0) glDeleteTextures(1, intArrayOf(depthTex), 0)
    }
}
