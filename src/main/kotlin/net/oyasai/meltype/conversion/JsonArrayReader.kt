package net.oyasai.meltype.conversion

/**
 * Google CGI のレスポンス（文字列と配列だけからなる JSON）を、入れ子の List と String に読み込む小さな読み取り器。
 * 候補に「"」「]」が含まれる場合や、\uXXXX などのエスケープにも対応する。
 * 数値・null など文字列と配列以外の値は読み飛ばし、形式が壊れている場合は null を返す。
 */
internal class JsonArrayReader(private val json: String) {
    private var pos = 0

    /** 最初の '[' から始まる配列を読む（前置きの文字があっても読めるよう、そこまでは読み飛ばす） */
    fun readTopLevel(): List<Any>? = try {
        pos = json.indexOf('[')
        if (pos < 0) null else readArray()
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun readArray(): List<Any> {
        expect('[')
        val items = mutableListOf<Any>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return items
        }
        while (true) {
            skipWhitespace()
            readValue()?.let { items.add(it) }
            skipWhitespace()
            when (next()) {
                ',' -> continue
                ']' -> return items
                else -> throw IllegalArgumentException("配列の区切りが不正")
            }
        }
    }

    private fun readValue(): Any? = when (peek()) {
        '[' -> readArray()
        '"' -> readString()
        else -> {
            // 数値・true/false/null などは読み飛ばす
            val start = pos
            while (pos < json.length && json[pos] != ',' && json[pos] != ']' && !json[pos].isWhitespace()) pos++
            if (pos == start) throw IllegalArgumentException("値がない")
            null
        }
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = next()
            when (c) {
                '"' -> return sb.toString()
                '\\' -> sb.append(readEscape())
                else -> sb.append(c)
            }
        }
    }

    /** 「\」の次の文字から、エスケープが表す1文字を読む */
    private fun readEscape(): Char = when (val e = next()) {
        '"', '\\', '/' -> e
        'b' -> '\b'
        'f' -> '\u000C'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> readUnicodeEscape()
        else -> throw IllegalArgumentException("不明なエスケープ")
    }

    /** `\u` の次の4桁（16進数）を読んで、その文字にする */
    private fun readUnicodeEscape(): Char {
        if (pos + 4 > json.length) throw IllegalArgumentException("\\u エスケープが途中で終わっている")
        val code = json.substring(pos, pos + 4).toIntOrNull(16)
            ?: throw IllegalArgumentException("\\u エスケープが不正")
        pos += 4
        return code.toChar()
    }

    private fun skipWhitespace() {
        while (pos < json.length && json[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = if (pos < json.length) json[pos] else throw IllegalArgumentException("途中で終わっている")

    private fun next(): Char = peek().also { pos++ }

    private fun expect(c: Char) {
        if (next() != c) throw IllegalArgumentException("'$c' が必要")
    }
}
