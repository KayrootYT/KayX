package com.kayx.manager.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

enum class SyntaxLang { PLAIN, XML, JSON, SMALI, CODE, HASH;
    companion object {
        fun fromFileName(name: String): SyntaxLang = when (name.substringAfterLast('.', "").lowercase()) {
            "xml", "html", "htm", "xhtml", "svg", "xsl", "plist" -> XML
            "json", "map" -> JSON
            "smali" -> SMALI
            "kt", "kts", "java", "js", "mjs", "ts", "c", "cc", "cpp", "h", "hpp", "cs", "go", "rs", "swift",
            "gradle", "css", "scss", "dart", "php", "aidl" -> CODE
            "py", "sh", "bash", "yml", "yaml", "properties", "conf", "ini", "toml", "cfg", "gitignore", "pro", "rb" -> HASH
            else -> PLAIN
        }
    }
}

/** Colours indexed by style: 0 comment, 1 string, 2 keyword, 3 number, 4 tag/directive, 5 attribute/register, 6 type. */
class SyntaxColors(val colors: List<Color>) {
    companion object {
        private val dark = SyntaxColors(listOf(
            Color(0xFF7F8FA9), Color(0xFFA5D6A7), Color(0xFFC792EA), Color(0xFFF78C6C),
            Color(0xFF82AAFF), Color(0xFFFFCB6B), Color(0xFF89DDFF)))
        private val light = SyntaxColors(listOf(
            Color(0xFF6A737D), Color(0xFF22863A), Color(0xFF8250DF), Color(0xFFB35900),
            Color(0xFF0550AE), Color(0xFF953800), Color(0xFF0A7E8C)))
        fun of(isDark: Boolean) = if (isDark) dark else light
    }
}

private const val STR = "\"(?:[^\"\\\\\\n]|\\\\.)*\""
private const val SQ = "'(?:[^'\\\\\\n]|\\\\.)*'"
private const val NUM = "\\b0x[0-9a-fA-F]+\\b|-?\\b\\d+(?:\\.\\d+)?[fFLlDd]?\\b"
private const val KEYWORDS = "abstract|as|async|await|break|case|catch|class|companion|const|continue|data|def|default|defer|do|elif|else|enum|except|export|extends|false|final|finally|fn|for|from|fun|func|function|go|if|implements|import|in|instanceof|interface|internal|is|lambda|lateinit|let|mod|mut|new|null|None|object|open|override|package|pass|private|protected|pub|public|raise|return|sealed|self|static|struct|super|suspend|switch|this|throw|trait|true|True|False|try|typealias|typeof|use|val|var|void|when|where|while|with|yield"

/** Pairs of (regex without capturing groups, style index). Earlier rules win at the same position. */
private fun rulesFor(lang: SyntaxLang): List<Pair<String, Int>> = when (lang) {
    SyntaxLang.XML -> listOf(
        "<!--[\\s\\S]*?-->" to 0,
        "\"[^\"\\n]*\"|'[^'\\n]*'" to 1,
        "<\\?[\\s\\S]*?\\?>|<![A-Za-z]+|</?[A-Za-z_][\\w:.-]*|/?>" to 4,
        "[A-Za-z_:][\\w:.-]*(?=\\s*=)" to 5
    )
    SyntaxLang.JSON -> listOf(
        "$STR(?=\\s*:)" to 5,
        STR to 1,
        "-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b" to 3,
        "\\b(?:true|false|null)\\b" to 2
    )
    SyntaxLang.SMALI -> listOf(
        "#[^\\n]*" to 0,
        STR to 1,
        "^[ \\t]*\\.[a-z][\\w-]*" to 4,
        ":[A-Za-z_]\\w*" to 4,
        "L[\\w/$]+;" to 6,
        "\\b[vp]\\d+\\b" to 5,
        "\\b0x[0-9a-fA-F]+\\b|-?\\b\\d+\\b" to 3,
        "^[ \\t]*[a-z][a-z0-9/_-]*" to 2
    )
    SyntaxLang.CODE -> listOf(
        "//[^\\n]*|/\\*[\\s\\S]*?\\*/" to 0,
        "$STR|$SQ" to 1,
        "@[A-Za-z_]\\w*" to 5,
        "\\b(?:$KEYWORDS)\\b" to 2,
        "\\b[A-Z][A-Za-z0-9_]*\\b" to 6,
        NUM to 3
    )
    SyntaxLang.HASH -> listOf(
        "#[^\\n]*" to 0,
        "$STR|$SQ" to 1,
        "\\b(?:$KEYWORDS)\\b" to 2,
        NUM to 3
    )
    SyntaxLang.PLAIN -> emptyList()
}

private val cache = HashMap<SyntaxLang, Pair<Regex, List<Int>>?>()

private fun compiled(lang: SyntaxLang): Pair<Regex, List<Int>>? = cache.getOrPut(lang) {
    val rules = rulesFor(lang)
    if (rules.isEmpty()) null
    else runCatching {
        Regex(rules.joinToString("|") { "(${it.first})" }, RegexOption.MULTILINE) to rules.map { it.second }
    }.getOrNull()
}

fun highlight(src: String, lang: SyntaxLang, colors: SyntaxColors): AnnotatedString {
    val (regex, styles) = compiled(lang) ?: return AnnotatedString(src)
    return buildAnnotatedString {
        append(src)
        for (m in regex.findAll(src)) {
            var g = 1
            while (g < m.groups.size && m.groups[g] == null) g++
            if (g >= m.groups.size) continue
            addStyle(SpanStyle(color = colors.colors[styles[g - 1]]), m.range.first, m.range.last + 1)
        }
    }
}

class SyntaxTransformation(private val lang: SyntaxLang, private val colors: SyntaxColors) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlight(text.text, lang, colors), OffsetMapping.Identity)
}
