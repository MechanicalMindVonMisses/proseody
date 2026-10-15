package dev.aliberke.sesli.neural

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

sealed interface ModelStatus {
    data object NotInstalled : ModelStatus
    data class Downloading(val progress: Float) : ModelStatus
    data object Installed : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

/**
 * Downloads sherpa-onnx model archives (tar.bz2) and unpacks them into app-specific storage,
 * models/<model id>/. A model counts as installed once its ".installed" marker exists.
 */
class ModelManager(private val context: Context, private val scope: CoroutineScope) {
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    private val _status = MutableStateFlow(NeuralCatalog.models.associate { it.id to initialStatus(it) })
    val status: StateFlow<Map<String, ModelStatus>> = _status.asStateFlow()

    private val jobs = HashMap<String, Job>()

    fun dir(model: NeuralModel) = File(root, model.id)

    fun isInstalled(model: NeuralModel) = File(dir(model), ".installed").exists()

    private fun initialStatus(model: NeuralModel) = if (isInstalled(model)) ModelStatus.Installed else ModelStatus.NotInstalled

    private fun set(model: NeuralModel, status: ModelStatus) = _status.update { it + (model.id to status) }

    /** Re-reads the disk, e.g. after models were copied in over adb. */
    fun refresh() = NeuralCatalog.models.forEach { if (jobs[it.id]?.isActive != true) set(it, initialStatus(it)) }

    fun download(model: NeuralModel) {
        if (jobs[model.id]?.isActive == true || isInstalled(model)) return
        set(model, ModelStatus.Downloading(0f))
        jobs[model.id] = scope.launch(Dispatchers.IO) {
            try {
                install(model)
                set(model, ModelStatus.Installed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                File(root, "${model.id}.partial").deleteRecursively()
                set(model, ModelStatus.NotInstalled)
                throw e
            } catch (e: Exception) {
                File(root, "${model.id}.partial").deleteRecursively()
                set(model, ModelStatus.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun cancel(model: NeuralModel) {
        jobs.remove(model.id)?.cancel()
    }

    fun delete(model: NeuralModel) {
        cancel(model)
        scope.launch(Dispatchers.IO) {
            dir(model).deleteRecursively()
            set(model, ModelStatus.NotInstalled)
        }
    }

    private suspend fun install(model: NeuralModel) {
        val partial = File(root, "${model.id}.partial").apply {
            deleteRecursively()
            mkdirs()
        }
        val partialPath = partial.canonicalPath + File.separator
        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("Download failed (HTTP ${connection.responseCode})")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: (model.sizeMb * 1_048_576L)
            val counting = CountingInputStream(connection.inputStream.buffered(1 shl 16))
            TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                var lastReported = -1
                var entry = tar.nextEntry
                while (entry != null) {
                    coroutineContext.ensureActive()
                    // Archives hold a single top-level directory named after the model; strip it.
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isNotEmpty()) {
                        val out = File(partial, relative)
                        if (!out.canonicalPath.startsWith(partialPath)) throw IllegalStateException("Unsafe path in archive: ${entry.name}")
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().buffered(1 shl 16).use { sink ->
                                val buffer = ByteArray(1 shl 16)
                                while (true) {
                                    val n = tar.read(buffer)
                                    if (n < 0) break
                                    sink.write(buffer, 0, n)
                                    val percent = (counting.count * 100 / total).toInt()
                                    if (percent != lastReported) {
                                        lastReported = percent
                                        set(model, ModelStatus.Downloading((percent / 100f).coerceIn(0f, 1f)))
                                        coroutineContext.ensureActive()
                                    }
                                }
                            }
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        } finally {
            connection.disconnect()
        }
        File(partial, ".installed").createNewFile()
        val target = dir(model)
        target.deleteRecursively()
        if (!partial.renameTo(target)) throw IllegalStateException("Couldn't move the model into place.")
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }
}
