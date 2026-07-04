package com.example.antiwispr.cloud

/** Sarvam saaras:v3 output modes. [wire] is the API value — persisted in prefs too. */
enum class CloudSttMode(val wire: String, val label: String, val description: String) {
    Transliterate("translit", "Romanised", "Original language in English letters — Hinglish style."),
    Transcribe("transcribe", "Native script", "The language's own script, e.g. Devanagari for Hindi."),
    Translate("translate", "Translate to English", "Everything rendered as English text."),
    Verbatim("verbatim", "Word-for-word", "Keeps fillers and repetitions exactly as spoken."),
    CodeMix("codemix", "Code-mixed", "Mixed-language notes keep each language's script.");

    companion object {
        val DEFAULT = Transliterate
        fun fromWire(v: String?): CloudSttMode = entries.firstOrNull { it.wire == v } ?: DEFAULT
    }
}

/** Sarvam language codes. Auto-detect first, then alphabetical by label. */
enum class CloudSttLanguage(val wire: String, val label: String) {
    Auto("unknown", "Auto-detect"),
    Assamese("as-IN", "Assamese"),
    Bengali("bn-IN", "Bengali"),
    Bodo("brx-IN", "Bodo"),
    Dogri("doi-IN", "Dogri"),
    English("en-IN", "English (India)"),
    Gujarati("gu-IN", "Gujarati"),
    Hindi("hi-IN", "Hindi"),
    Kannada("kn-IN", "Kannada"),
    Kashmiri("ks-IN", "Kashmiri"),
    Konkani("kok-IN", "Konkani"),
    Maithili("mai-IN", "Maithili"),
    Malayalam("ml-IN", "Malayalam"),
    Manipuri("mni-IN", "Manipuri (Meitei)"),
    Marathi("mr-IN", "Marathi"),
    Nepali("ne-IN", "Nepali"),
    Odia("od-IN", "Odia"),
    Punjabi("pa-IN", "Punjabi"),
    Sanskrit("sa-IN", "Sanskrit"),
    Santali("sat-IN", "Santali"),
    Sindhi("sd-IN", "Sindhi"),
    Tamil("ta-IN", "Tamil"),
    Telugu("te-IN", "Telugu"),
    Urdu("ur-IN", "Urdu");

    companion object {
        val DEFAULT = Auto
        fun fromWire(v: String?): CloudSttLanguage = entries.firstOrNull { it.wire == v } ?: DEFAULT
    }
}
