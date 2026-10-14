package com.titovpower.racegame

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A static vertex buffer of [MeshBuilder] data, drawn as triangles. */
class GlMesh(data: FloatArray) {
    private val vbo: Int
    private val vertexCount = data.size / MeshBuilder.FLOATS_PER_VERTEX

    init {
        val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(data).position(0)
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun draw(shader: Shader) {
        val stride = MeshBuilder.FLOATS_PER_VERTEX * 4
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(shader.aPos)
        GLES20.glVertexAttribPointer(shader.aPos, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(shader.aNormal)
        GLES20.glVertexAttribPointer(shader.aNormal, 3, GLES20.GL_FLOAT, false, stride, 12)
        GLES20.glEnableVertexAttribArray(shader.aColor)
        GLES20.glVertexAttribPointer(shader.aColor, 4, GLES20.GL_FLOAT, false, stride, 24)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}

/** The single scene shader; see [ShaderSource]. */
class Shader {
    val program: Int
    val aPos: Int
    val aNormal: Int
    val aColor: Int
    private val uniforms = HashMap<String, Int>()

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, ShaderSource.VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, ShaderSource.FRAGMENT)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Program link failed: " + GLES20.glGetProgramInfoLog(program))
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
    }

    fun u(name: String): Int = uniforms.getOrPut(name) { GLES20.glGetUniformLocation(program, name) }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Shader compile failed: " + GLES20.glGetShaderInfoLog(s))
        return s
    }
}
