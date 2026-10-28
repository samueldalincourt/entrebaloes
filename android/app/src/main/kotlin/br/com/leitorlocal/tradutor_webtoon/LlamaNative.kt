package br.com.leitorlocal.tradutor_webtoon

/** llama.cpp is compiled into every APK; capability checks call the real JNI library. */
object LlamaNative {
    val info: Result<String> by lazy { runCatching { System.loadLibrary("webtoon_llama"); buildInfo() } }
    val available get() = info.isSuccess
    external fun buildInfo(): String
    external fun load(file: String, threads: Int, epoch: Long)
    external fun prime(system: ByteArray, epoch: Long)
    external fun generate(system: ByteArray, input: ByteArray, maxTokens: Int, json: Boolean, epoch: Long): ByteArray
    external fun cancel(epoch: Long)
    external fun unload()
}
