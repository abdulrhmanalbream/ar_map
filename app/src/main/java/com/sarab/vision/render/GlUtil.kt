package com.sarab.vision.render

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val TAG = "SarabGl"

/** Compiles a shader and throws with the driver log if it fails. */
fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)

    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    if (status[0] == 0) {
        val log = GLES20.glGetShaderInfoLog(shader)
        GLES20.glDeleteShader(shader)
        // Failing loudly here saves hours: a silently broken shader shows up
        // as an invisible object with no other symptom.
        throw RuntimeException("Shader compile failed: $log")
    }
    return shader
}

/** Links a vertex + fragment shader pair into a program. */
fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
    val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
    val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vs)
    GLES20.glAttachShader(program, fs)
    GLES20.glLinkProgram(program)

    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    if (status[0] == 0) {
        val log = GLES20.glGetProgramInfoLog(program)
        GLES20.glDeleteProgram(program)
        throw RuntimeException("Program link failed: $log")
    }

    // The shaders are owned by the program once linked.
    GLES20.glDeleteShader(vs)
    GLES20.glDeleteShader(fs)
    return program
}

/** Allocates a direct native float buffer, as required by GLES. */
fun floatBuffer(capacity: Int): FloatBuffer =
    ByteBuffer.allocateDirect(capacity * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

fun floatBufferOf(data: FloatArray): FloatBuffer =
    floatBuffer(data.size).apply {
        put(data)
        position(0)
    }

/** Logs any pending GL error. Called sparingly -- glGetError forces a sync. */
fun checkGlError(label: String) {
    val error = GLES20.glGetError()
    if (error != GLES20.GL_NO_ERROR) {
        Log.e(TAG, "GL error after $label: $error")
    }
}
