package dev.aliberke.sesli.neural

import java.util.Locale

enum class ModelKind { KOKORO, PIPER }

/** A downloadable sherpa-onnx TTS model; [id] is both the release asset stem and the install directory. */
data class NeuralModel(
    val id: String,
    val title: String,
    val description: String,
    val sizeMb: Int,
    val kind: ModelKind,
    val language: String,
) {
    val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$id.tar.bz2"
}

/** One speaker of a neural model. [id] is what the engine and prefs store as the voice name. */
data class NeuralVoice(
    val model: NeuralModel,
    val speakerId: Int,
    val label: String,
    val locale: Locale,
    val isDefault: Boolean = false,
) {
    val id get() = "neural:${model.id}:$speakerId"
}

object NeuralCatalog {
    // Full-precision Kokoro: on a Galaxy S23 it synthesized faster than the int8 build (~0.64 vs ~0.75 of
    // real time) and sounds slightly cleaner; int8 is 126 MB if size ever matters more.
    val KOKORO = NeuralModel(
        id = "kokoro-multi-lang-v1_0",
        title = "Kokoro",
        description = "Natural English voices",
        sizeMb = 334,
        kind = ModelKind.KOKORO,
        language = "en",
    )
    val FAHRETTIN = NeuralModel(
        id = "vits-piper-tr_TR-fahrettin-medium",
        title = "Fahrettin",
        description = "Doğal Türkçe erkek sesi",
        sizeMb = 64,
        kind = ModelKind.PIPER,
        language = "tr",
    )

    val models = listOf(KOKORO, FAHRETTIN)

    private val US = Locale.US
    private val GB = Locale.UK

    // Speaker ids from the model's own "id2speaker" ONNX metadata (af_heart = 3, am_michael = 16, ...).
    val voices = listOf(
        NeuralVoice(KOKORO, 3, "Heart · US female", US, isDefault = true),
        NeuralVoice(KOKORO, 2, "Bella · US female", US),
        NeuralVoice(KOKORO, 6, "Nicole · US female", US),
        NeuralVoice(KOKORO, 9, "Sarah · US female", US),
        NeuralVoice(KOKORO, 16, "Michael · US male", US),
        NeuralVoice(KOKORO, 14, "Fenrir · US male", US),
        NeuralVoice(KOKORO, 18, "Puck · US male", US),
        NeuralVoice(KOKORO, 21, "Emma · UK female", GB),
        NeuralVoice(KOKORO, 22, "Isabella · UK female", GB),
        NeuralVoice(KOKORO, 26, "George · UK male", GB),
        NeuralVoice(KOKORO, 25, "Fable · UK male", GB),
        NeuralVoice(FAHRETTIN, 0, "Fahrettin", Locale.forLanguageTag("tr-TR"), isDefault = true),
    )

    fun voice(id: String): NeuralVoice? = voices.firstOrNull { it.id == id }

    fun defaultFor(language: String): NeuralVoice? = voices.firstOrNull { it.isDefault && it.locale.language == language }
}
