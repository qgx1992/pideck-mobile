package dev.pideck.nativeapp.ui.chat

/**
 * 极简语法着色。
 *
 * 为什么不上高亮库：桌面端用的是 shiki + 一整套 TextMate 语法，搬过来要带几十个语法定义和
 * WASM；这里只需要「关键词/字符串/注释/数字/类型名」四类颜色就足以看清结构，而且零依赖、
 * 不需要在手机上跑正则引擎之外的东西。
 *
 * 分词与上色分开：[tokenize] 是纯函数（能在 JVM 单测里断言），Compose 那边只负责把
 * token 变成 span。
 */
enum class TokenKind { Keyword, Type, String, Number, Comment, Plain }

data class Token(val start: Int, val end: Int, val kind: TokenKind)

object CodeHighlight {

    private val keywordsByLang: Map<String, Set<String>> = mapOf(
        "kotlin" to setOf(
            "package", "import", "class", "object", "interface", "fun", "val", "var", "if", "else",
            "when", "for", "while", "do", "return", "break", "continue", "try", "catch", "finally",
            "throw", "is", "as", "in", "out", "null", "true", "false", "this", "super", "data",
            "sealed", "enum", "private", "public", "internal", "protected", "override", "open",
            "abstract", "companion", "suspend", "lateinit", "by", "init", "constructor", "typealias",
            "inline", "operator", "const", "crossinline", "reified", "where", "annotation",
        ),
        "java" to setOf(
            "package", "import", "class", "interface", "enum", "extends", "implements", "public",
            "private", "protected", "static", "final", "void", "int", "long", "double", "float",
            "boolean", "char", "byte", "short", "new", "return", "if", "else", "for", "while",
            "do", "switch", "case", "break", "continue", "try", "catch", "finally", "throw",
            "throws", "this", "super", "null", "true", "false", "abstract", "synchronized",
        ),
        "javascript" to setOf(
            "const", "let", "var", "function", "return", "if", "else", "for", "while", "do",
            "switch", "case", "break", "continue", "try", "catch", "finally", "throw", "new",
            "class", "extends", "super", "this", "null", "undefined", "true", "false", "await",
            "async", "import", "export", "from", "default", "typeof", "instanceof", "delete", "in",
            "of", "yield", "static", "get", "set",
        ),
        "typescript" to setOf(
            "const", "let", "var", "function", "return", "if", "else", "for", "while", "do",
            "switch", "case", "break", "continue", "try", "catch", "finally", "throw", "new",
            "class", "extends", "super", "this", "null", "undefined", "true", "false", "await",
            "async", "import", "export", "from", "default", "typeof", "instanceof", "interface",
            "type", "enum", "implements", "public", "private", "protected", "readonly", "as",
            "satisfies", "namespace", "declare", "abstract", "yield",
        ),
        "python" to setOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "break", "continue",
            "try", "except", "finally", "raise", "with", "as", "import", "from", "pass", "lambda",
            "global", "nonlocal", "assert", "yield", "async", "await", "None", "True", "False",
            "and", "or", "not", "in", "is", "del", "self",
        ),
        "rust" to setOf(
            "fn", "let", "mut", "struct", "enum", "impl", "trait", "use", "mod", "pub", "return",
            "if", "else", "match", "for", "while", "loop", "break", "continue", "unsafe", "where",
            "as", "in", "ref", "move", "async", "await", "dyn", "const", "static", "type", "self",
            "Self", "true", "false",
        ),
        "go" to setOf(
            "package", "import", "func", "type", "struct", "interface", "var", "const", "return",
            "if", "else", "for", "range", "switch", "case", "default", "break", "continue", "go",
            "defer", "chan", "select", "map", "nil", "true", "false", "make", "new",
        ),
        "bash" to setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
            "function", "return", "exit", "export", "local", "in", "echo", "cd", "source",
        ),
        "sql" to setOf(
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
            "create", "table", "alter", "drop", "join", "left", "right", "inner", "outer", "on",
            "group", "by", "order", "having", "limit", "offset", "as", "and", "or", "not", "null",
        ),
        "json" to setOf("true", "false", "null"),
        "yaml" to setOf("true", "false", "null"),
        "css" to setOf("important", "media", "import", "supports", "keyframes"),
        "html" to emptySet(),
        "xml" to emptySet(),
    )

    private fun keywordsFor(lang: String?): Set<String> {
        val key = lang?.lowercase()?.trim()?.removePrefix("language-") ?: return emptySet()
        val alias = when (key) {
            "kt", "kts" -> "kotlin"
            "js", "jsx", "mjs", "cjs" -> "javascript"
            "ts", "tsx" -> "typescript"
            "py" -> "python"
            "rs" -> "rust"
            "sh", "shell", "zsh", "console" -> "bash"
            "yml" -> "yaml"
            "htm" -> "html"
            else -> key
        }
        return keywordsByLang[alias] ?: emptySet()
    }

    private val tokenPattern = Regex(
        // 注释 | 字符串 | 数字 | 标识符
        """(//[^\n]*|#[^\n]*|/\*[\s\S]*?\*/)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|`(?:[^`\\]|\\.)*`)|(\b\d[\d_]*(?:\.\d+)?\b)|([A-Za-z_][A-Za-z0-9_]*)""",
        RegexOption.MULTILINE,
    )

    /**
     * 切出有色的片段；没有命中的字符不返回（渲染时按 Plain 处理）。
     *
     * `#` 注释只在 python/bash/yaml 之类语言里认 —— 否则 C 系语言里的 `#include`、
     * CSS 里的 `#fff` 会被整行染成注释色。
     */
    fun tokenize(code: String, lang: String?): List<Token> {
        val keywords = keywordsFor(lang)
        val hashComments = lang?.lowercase()?.let {
            it.startsWith("py") || it in setOf("bash", "sh", "shell", "zsh", "yaml", "yml", "ruby", "rb", "toml", "conf", "ini", "dockerfile")
        } ?: false
        val tokens = mutableListOf<Token>()
        for (match in tokenPattern.findAll(code)) {
            val comment = match.groups[1]
            val string = match.groups[2]
            val number = match.groups[3]
            val word = match.groups[4]
            when {
                comment != null -> {
                    val text = comment.value
                    if (text.startsWith("#") && !hashComments) continue
                    tokens += Token(match.range.first, match.range.last + 1, TokenKind.Comment)
                }
                string != null -> tokens += Token(match.range.first, match.range.last + 1, TokenKind.String)
                number != null -> tokens += Token(match.range.first, match.range.last + 1, TokenKind.Number)
                word != null -> {
                    val text = word.value
                    when {
                        keywords.isNotEmpty() && text.lowercase() in keywords ->
                            tokens += Token(match.range.first, match.range.last + 1, TokenKind.Keyword)
                        // 首字母大写：在多数语言里这是一个类型/类名，颜色区分一下足够用了
                        text.first().isUpperCase() && text.length > 1 ->
                            tokens += Token(match.range.first, match.range.last + 1, TokenKind.Type)
                    }
                }
            }
        }
        return tokens
    }

    /** diff / patch 用另一种渲染（整行底色），不走 tokenizer。 */
    fun isDiff(lang: String?): Boolean =
        lang?.lowercase()?.trim() in setOf("diff", "patch", "udiff")
}
