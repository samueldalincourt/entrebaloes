package br.com.leitorlocal.tradutor_webtoon

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Pinned revision + trusted per-file checksums; partial downloads are resumable. */
class ModelStore(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manifest = JSONObject(context.assets.open("model-manifest.json").bufferedReader().use { it.readText() })
    val directory = File(context.filesDir, "models/${manifest.getString("revision")}")
    val modelId get() = manifest.getString("modelId")
    private val stagedImport get() = context.getExternalFilesDir(null)?.let {
        File(it, "model-import/${manifest.getString("filename")}")
    }
    val hasStagedImport get() = stagedImport?.isFile == true
    val modelFile get() = File(directory, manifest.getString("filename"))
    private val marker get() = File(directory, ".verified")
    val ready get() = marker.exists() && marker.readText() == manifest.getString("revision")
    @Volatile var downloading = false
        private set
    @Volatile var progress = 0.0
        private set
    @Volatile var downloadedBytes = 0L
        private set
    val totalBytes: Long = (0 until manifest.getJSONArray("files").length()).sumOf {
        manifest.getJSONArray("files").getJSONObject(it).getLong("size")
    }
    private val canceled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { canceled.set(true); connection?.disconnect() }
    fun download() {
        synchronized(this) { if (downloading) return; downloading = true }
        canceled.set(false)
        scope.launch {
            try {
                directory.mkdirs()
                marker.delete()
                var completed = 0L
                val files = manifest.getJSONArray("files")
                for (i in 0 until files.length()) {
                    ensureActiveDownload()
                    val spec = files.getJSONObject(i)
                    val file = File(directory, spec.getString("path"))
                    require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                    if (!verify(file, spec)) {
                        file.delete()
                        val staged = stagedImport
                        if (staged != null && verify(staged, spec)) {
                            val partial = File(file.path + ".import")
                            try {
                                staged.inputStream().use { input -> partial.outputStream().use { output ->
                                    val buffer = ByteArray(1024 * 1024)
                                    var copied = 0L
                                    while (true) {
                                        ensureActiveDownload()
                                        val count = input.read(buffer); if (count < 0) break
                                        output.write(buffer, 0, count); copied += count; update(completed + copied)
                                    }
                                } }
                                check(verify(partial, spec) && partial.renameTo(file)) { "Falha ao importar o modelo verificado." }
                                staged.delete()
                            } finally { partial.delete() }
                        } else downloadFile(spec, file, completed)
                    }
                    check(verify(file, spec)) { "Integridade inválida: ${file.name}. Tente retomar o download." }
                    completed += spec.getLong("size")
                    update(completed)
                }
                marker.writeText(manifest.getString("revision"))
                AppState.lastError = null
                AppState.emit("modelReady")
            } catch (error: Exception) {
                if (!canceled.get()) AppState.error("Download do modelo: ${error.message}")
            } finally {
                connection?.disconnect(); connection = null; downloading = false
                AppState.emit("status")
            }
        }
    }
    private fun ensureActiveDownload() { check(!canceled.get()) { "Download pausado" } }
    private fun update(bytes: Long) {
        downloadedBytes = bytes; progress = bytes.toDouble() / totalBytes
        AppState.emit("download", mapOf("progress" to progress, "bytes" to bytes, "total" to totalBytes))
    }
    private fun downloadFile(spec: JSONObject, destination: File, completed: Long) {
        destination.parentFile!!.mkdirs()
        val partial = File(destination.path + ".part")
        val size = spec.getLong("size")
        if (partial.length() > size) partial.delete()
        // A complete partial file may remain after the app was killed before rename.
        if (verify(partial, spec)) { check(partial.renameTo(destination)); return }
        if (partial.length() == size) partial.delete()
        var offset = partial.length()
        val remote = "https://huggingface.co/${manifest.getString("repository")}/resolve/" +
            "${manifest.getString("revision")}/${spec.getString("path")}?download=true"
        val conn = URL(remote).openConnection() as HttpURLConnection
        connection = conn
        conn.connectTimeout = 30000; conn.readTimeout = 30000
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        val code = conn.responseCode
        check(code == 200 || code == 206) { "HTTP $code ao baixar ${destination.name}" }
        if (code == 206) {
            check(conn.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true) {
                "Resposta de retomada inválida"
            }
        } else offset = 0
        try {
            conn.inputStream.use { input ->
                FileOutputStream(partial, offset > 0).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var lastUpdate = 0L
                    while (true) {
                        ensureActiveDownload()
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); offset += count
                        check(offset <= size) { "Tamanho inesperado: ${destination.name}" }
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastUpdate > 200) { update(completed + offset); lastUpdate = now }
                    }
                    output.fd.sync()
                }
            }
        } finally { conn.disconnect() }
        check(verify(partial, spec)) {
            partial.delete()
            "Integridade inválida: ${destination.name}. Retome o download para tentar novamente."
        }
        check(partial.renameTo(destination)) { "Não foi possível finalizar ${destination.name}" }
    }
    private fun verify(file: File, spec: JSONObject): Boolean {
        if (!file.exists() || file.length() != spec.getLong("size")) return false
        val git = spec.getString("algorithm") == "git-sha1"
        val digest = MessageDigest.getInstance(if (git) "SHA-1" else "SHA-256")
        if (git) digest.update("blob ${file.length()}\u0000".toByteArray(Charsets.UTF_8))
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                ensureActiveDownload()
                val count = input.read(buffer); if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == spec.getString("hash")
    }
}
