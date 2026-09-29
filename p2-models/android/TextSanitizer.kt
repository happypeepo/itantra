package com.itantra.speech // change to your app's package

import java.io.File
import java.text.Normalizer

/**
 * Cleans text right before it goes to TTS. Same logic as sanitize() in
 * scripts/common.py, so what was tested on the laptop is what runs on the phone.
 *
 * 1. Removes sentence-ending marks at the END ( . ? ! । ॥ ).
 *    sherpa-onnx otherwise can make a tiny empty extra sentence that plays as noise.
 * 2. For character-based voices (rasa, MMS) only: drops any character the voice
 *    doesn't know. If a character is unknown but its decomposed parts are known
 *    (e.g. a letter + nukta), the known parts are kept.
 *
 * Make one per TTS engine, from the same tokens.txt that engine loads.
 */
class TextSanitizer(tokensFile: File?, private val characterFrontend: Boolean) {

    private val vocab: Set<Int> =
        if (characterFrontend && tokensFile != null) loadVocab(tokensFile) else emptySet()

    fun clean(input: String): String {
        var t = input.trim().trimEnd { it in END_MARKS || it.isWhitespace() }
        if (characterFrontend && vocab.isNotEmpty()) {
            val sb = StringBuilder()
            var i = 0
            while (i < t.length) {
                val cp = t.codePointAt(i)
                keep(cp, sb)
                i += Character.charCount(cp)
            }
            t = sb.toString()
        }
        return t.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun keep(cp: Int, sb: StringBuilder) {
        if (Character.isWhitespace(cp) || cp in vocab) {
            sb.appendCodePoint(cp)
            return
        }
        val single = String(Character.toChars(cp))
        val parts = Normalizer.normalize(single, Normalizer.Form.NFD)
        if (parts != single) {
            var j = 0
            while (j < parts.length) {
                val p = parts.codePointAt(j)
                if (p in vocab) sb.appendCodePoint(p)
                j += Character.charCount(p)
            }
        }
    }

    companion object {
        private val END_MARKS = setOf('.', '?', '!', '\u0964', '\u0965')
        private val WHITESPACE = Regex("\\s+")

        private fun loadVocab(f: File): Set<Int> {
            val out = HashSet<Int>()
            f.forEachLine(Charsets.UTF_8) { line ->
                val idx = line.lastIndexOf(' ')
                if (idx > 0) {
                    val tok = line.substring(0, idx)
                    if (tok.codePointCount(0, tok.length) == 1) out.add(tok.codePointAt(0))
                }
            }
            return out
        }
    }
}
