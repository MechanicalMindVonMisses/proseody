package dev.aliberke.sesli

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dev.aliberke.sesli.data.AppDatabase
import dev.aliberke.sesli.data.DocumentRepository
import dev.aliberke.sesli.neural.ModelManager
import dev.aliberke.sesli.tts.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ProseodyApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val prefs by lazy { Prefs(this) }
    val repository by lazy { DocumentRepository(this, AppDatabase.build(this).documentDao()) }
    val models by lazy { ModelManager(this, appScope) }
    val engine by lazy { SpeechEngine(this, prefs, models) }

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        engine.onProgress = { id, position ->
            appScope.launch(Dispatchers.IO) { repository.updateProgress(id, position) }
        }
    }
}
