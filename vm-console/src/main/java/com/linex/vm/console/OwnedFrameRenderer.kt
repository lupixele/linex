package com.linex.vm.console

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

internal class OwnedFrameRenderer(
    private val frames: OwnedFrameMailbox,
    private val disposed: AtomicBoolean,
    private val onPresented: (Int, Int) -> Unit,
    private val onFailure: (String) -> Unit
) : GLSurfaceView.Renderer {
        @Volatile var hasPresentedContent: Boolean = false
            private set
        private var current: OwnedArgbFrame? = null
        private var upload: ByteBuffer? = null
        private var program = 0
        private var texture = 0
        private var textureWidth = 0
        private var textureHeight = 0
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var maxTexture = 0
        private var needsUpload = true
        private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
            position(0)
        }

        @Synchronized override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            if (disposed.get()) return
            try { createGlObjects() } catch (error: Exception) { fail(error) }
        }

        private fun createGlObjects() {
            // Previous names belong to the lost context; never delete them in this one.
            program = createProgram()
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texture = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val maximum = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maximum, 0)
            maxTexture = maximum[0]
            textureWidth = 0
            textureHeight = 0
            needsUpload = true
        }

        @Synchronized override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
        }

        @Synchronized override fun onDrawFrame(gl: GL10?) {
            try { drawCompleteFrame() } catch (error: Exception) { fail(error) }
        }

        private fun fail(error: Exception) {
            disposed.set(true)
            frames.close()
            releaseCpuFrames()
            deleteGlObjects()
            onFailure(error.message ?: "Desktop presentation failed")
        }

        private fun drawCompleteFrame() {
            // Release the retained context-recovery snapshot before checking out
            // its replacement, so the renderer never owns both alongside a new
            // concurrently offered pending frame.
            val next = frames.takeReplacing {
                val previous = current
                current = null
                previous?.close()
            }
            if (disposed.get()) { next?.close(); return }
            val newFrame = next != null
            if (next != null) {
                current = next
                needsUpload = true
            }
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val frame = current ?: return
            require(frame.width <= maxTexture && frame.height <= maxTexture) { "Framebuffer exceeds GPU texture limit" }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            if (needsUpload) {
                val bytes = frame.width * frame.height * 4
                val buffer = upload?.takeIf { it.capacity() == bytes } ?: ByteBuffer.allocateDirect(bytes).also { upload = it }
                frame.writeRgba(buffer)
                if (textureWidth != frame.width || textureHeight != frame.height) {
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frame.width, frame.height,
                        0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
                    textureWidth = frame.width
                    textureHeight = frame.height
                } else GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
                needsUpload = false
            }
            val scale = minOf(surfaceWidth.toFloat() / frame.width, surfaceHeight.toFloat() / frame.height)
            val viewportWidth = (frame.width * scale).toInt()
            val viewportHeight = (frame.height * scale).toInt()
            GLES20.glViewport((surfaceWidth - viewportWidth) / 2, (surfaceHeight - viewportHeight) / 2, viewportWidth, viewportHeight)
            GLES20.glUseProgram(program)
            val position = GLES20.glGetAttribLocation(program, "position")
            val coordinate = GLES20.glGetAttribLocation(program, "coordinate")
            vertices.position(0)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
            vertices.position(2)
            GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glEnableVertexAttribArray(coordinate)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Desktop GL presentation failed" }
            if (newFrame) {
                if (!hasPresentedContent) hasPresentedContent = DesktopFrameContent.visible(frame.pixels, frame.width, frame.height)
                onPresented(frame.width, frame.height)
            }
        }

        fun releaseCpuFrames() { current?.close(); current = null; upload = null }
        fun deleteGlObjects() {
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            if (program != 0) GLES20.glDeleteProgram(program)
            texture = 0; program = 0
        }
    private fun createProgram(): Int {
        fun shader(type: Int, source: String): Int {
            val name = GLES20.glCreateShader(type)
            GLES20.glShaderSource(name, source)
            GLES20.glCompileShader(name)
            val status = IntArray(1)
            GLES20.glGetShaderiv(name, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) { GLES20.glDeleteShader(name); error("Desktop shader compilation failed") }
            return name
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER,
            "attribute vec2 position; attribute vec2 coordinate; varying vec2 uv; void main(){uv=coordinate;gl_Position=vec4(position,0.,1.);}")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER,
            "precision mediump float; varying vec2 uv; uniform sampler2D image; void main(){gl_FragColor=texture2D(image,uv);}")
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) { GLES20.glDeleteProgram(program); error("Desktop shader linking failed") }
        return program
    }
}
