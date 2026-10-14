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
        GLES20.glVertexAttribPointer(shader.aColor, 3, GLES20.GL_FLOAT, false, stride, 24)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}

/** Vertex-colored, directionally lit shader with distance fog. */
class Shader {
    val program: Int
    val aPos: Int
    val aNormal: Int
    val aColor: Int
    val uMvp: Int
    val uModel: Int
    val uCamPos: Int
    val uLightDir: Int
    val uFogColor: Int
    val uFogRange: Int
    val uAlpha: Int
    val uUnlit: Int

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
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
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uCamPos = GLES20.glGetUniformLocation(program, "uCamPos")
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir")
        uFogColor = GLES20.glGetUniformLocation(program, "uFogColor")
        uFogRange = GLES20.glGetUniformLocation(program, "uFogRange")
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
        uUnlit = GLES20.glGetUniformLocation(program, "uUnlit")
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Shader compile failed: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    companion object {
        private const val VERTEX = """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            uniform vec3 uCamPos;
            uniform vec3 uLightDir;
            uniform vec2 uFogRange;
            uniform float uUnlit;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            varying vec3 vColor;
            varying float vFog;
            void main() {
                vec4 world = uModel * vec4(aPos, 1.0);
                vec3 n = normalize((uModel * vec4(aNormal, 0.0)).xyz);
                float diffuse = max(dot(n, uLightDir), 0.0);
                float light = mix(0.5 + 0.62 * diffuse, 1.0, uUnlit);
                vColor = aColor * light;
                float d = distance(world.xyz, uCamPos);
                vFog = clamp((d - uFogRange.x) / (uFogRange.y - uFogRange.x), 0.0, 1.0) * (1.0 - uUnlit);
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        private const val FRAGMENT = """
            precision mediump float;
            uniform vec3 uFogColor;
            uniform float uAlpha;
            varying vec3 vColor;
            varying float vFog;
            void main() {
                gl_FragColor = vec4(mix(vColor, uFogColor, vFog), uAlpha);
            }
        """
    }
}
