package br.com.leitorlocal.tradutor_webtoon

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import java.io.File

object DeviceDiagnostics {
    fun collect(context: Context): Map<String, Any?> {
        val result = AppState.status().toMutableMap()
        result["hardware"] = Build.HARDWARE
        result["soc"] = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "Consultar hardware"
        result["openClLibraries"] = listOf("/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so")
            .filter { File(it).exists() }
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        var glContext = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val attributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, IntArray(1), 0))
            glContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, glContext))
            result["gpu"] = GLES20.glGetString(GLES20.GL_RENDERER)
            result["gpuVendor"] = GLES20.glGetString(GLES20.GL_VENDOR)
            result["gpuDriver"] = GLES20.glGetString(GLES20.GL_VERSION)
        } catch (error: Exception) { result["gpuError"] = error.message ?: "EGL indisponível" }
        finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (glContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, glContext)
            EGL14.eglTerminate(display)
        }
        result["freeStorageBytes"] = context.filesDir.usableSpace
        return result
    }
}
