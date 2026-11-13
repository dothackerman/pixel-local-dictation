package ch.og.dictation

/**
 * Take text in the owner's written form: spoken numbers that the recognizer left as words become
 * digits where the context makes the number clear, in Swiss style for German (research/number-itn.md).
 * A wrong digit is worse than a word, so every rule needs context:
 * - cardinals from 13 always; 0 to 12 only before a unit, currency or %, or after a counter noun
 *   (Gleis, Seite, platform…); ein/eine/einen, lone eins/one and bare hundert/hundred never;
 * - times: German "<h> Uhr [<m>]" → 12.46 Uhr, English "<h> [<m>] AM/PM" → 12:46 p.m.;
 * - dates: an ordinal only next to a month (5. Juni, June 5, the 3rd of October);
 * - money: "vier Franken achtzig" → 4.80 Franken, "four pounds eighty" → £4.80; decimals with Komma/point;
 * - Swiss format for digits the recognizer already wrote: 9:20 Uhr → 9.20 Uhr, 4,80 Franken → 4.80 Franken.
 * Pure and idempotent; runs on the joined take text, so preview, insertion and History agree.
 */
object WrittenForm {
    fun of(text: String): String {
        if (text.isEmpty()) return text
        val items = ITEM.findAll(text).map { Item(it.value, it.range.first, it.range.last + 1) }.toList()
        val german = guessGerman(items)
        val out = StringBuilder()
        var cursor = 0
        val rules = Rules(text, items, german)
        var i = 0
        while (i < items.size) {
            val m = rules.at(i)
            if (m == null) { i++; continue }
            out.append(text, cursor, items[i].start).append(m.text)
            cursor = m.endOffset
            i = m.end
        }
        out.append(text, cursor, text.length)
        return format(out.toString())
    }

    private class Item(val text: String, val start: Int, val end: Int) {
        val key = text.lowercase().replace("ß", "ss").replace("’", "'")
        val digits: Int? = if (text.all(Char::isDigit) && text.length <= 4) text.toInt() else null
    }

    private class Match(val end: Int, val endOffset: Int, val text: String)

    private class Rules(val raw: String, val items: List<Item>, val german: Boolean) {
        fun key(i: Int) = items.getOrNull(i)?.key
        /** Item [i] follows item i-1 within one spoken phrase: a single space or hyphen between. */
        fun joined(i: Int, hyphen: Boolean = false): Boolean {
            if (i <= 0 || i >= items.size) return false
            val gap = raw.substring(items[i - 1].end, items[i].start)
            return gap == " " || (hyphen && gap == "-")
        }
        fun spaced(i: Int) = i > 0 && i < items.size && raw.substring(items[i - 1].end, items[i].start).isNotEmpty() &&
            raw.substring(items[i - 1].end, items[i].start).isBlank()
        fun match(i: Int, end: Int, text: String) = Match(end, items[end - 1].end, text)

        fun at(i: Int): Match? {
            // A run of German number words that does not parse whole stays as said, all of it.
            if (isDeNum(i)) { val r = runEnd(i); if (r - i >= 2 && parseRun(i, r) == null) return unchanged(i, r) }
            return germanTime(i) ?: englishTime(i) ?: germanDate(i) ?: englishDate(i) ?: money(i) ?: decimal(i) ?: cardinal(i)
        }

        private fun unchanged(i: Int, end: Int) = Match(end, items[end - 1].end, raw.substring(items[i].start, items[end - 1].end))
        private fun isDeNum(i: Int): Boolean {
            val k = key(i) ?: return false
            return items[i].digits == null && (de(k) != null || k == "ein" || k == "eine" || k == "million" || k == "millionen" ||
                k.endsWith("hundert") || k.endsWith("tausend"))
        }
        /** End (exclusive) of the chain of single-space-separated German number words starting at item i. */
        private fun runEnd(i: Int): Int { var j = i + 1; while (joined(j) && isDeNum(j)) j++; return j }
        /** Value of the run [i, end) when every word fits below the last place filled; null leaves it as words. */
        private fun parseRun(i: Int, end: Int): Int? {
            var total = 0; var limit = Int.MAX_VALUE
            for (j in i until end) {
                val k = key(j)!!
                val v = de(k) ?: return null
                if (v == 0 && end - i > 1 || v >= limit) return null
                total += v
                limit = if (k.endsWith("tausend")) 1000 else if (k.endsWith("hundert")) 100 else 0
            }
            return total
        }
        private fun deRun(i: Int): Pair<Int, Int>? {
            if (!isDeNum(i)) return null
            val r = runEnd(i)
            return parseRun(i, r)?.let { it to r - i }
        }

        private fun germanTime(i: Int): Match? {
            val h = deHour(i) ?: return null
            if (!spaced(i + 1) || key(i + 1) != "uhr") return null
            if (spaced(i + 2) && isDeNum(i + 2) && runEnd(i + 2) - (i + 2) >= 2) return unchanged(i, runEnd(i + 2))
            val m = if (spaced(i + 2)) items[i + 2].digits ?: de(key(i + 2)) else null
            if (m != null && m in 1..59) {
                val text = "$h.${m.toString().padStart(2, '0')} " + items[i + 1].text
                return Match(i + 3, items[i + 2].end, text)
            }
            if (items[i].digits != null) return null
            return match(i, i + 2, "$h " + items[i + 1].text)
        }
        private fun deHour(i: Int): Int? = (items[i].digits ?: if (key(i) == "ein") 1 else de(key(i)))?.takeIf { it in 0..24 }

        private fun englishTime(i: Int): Match? {
            val h = (items[i].digits ?: EN_SMALL[key(i)])?.takeIf { it in 1..12 } ?: return null
            var j = i + 1
            var minutes: Int? = null
            if (joined(j) && key(j) == "o'clock") return if (items[i].digits == null) match(i, j + 1, "$h ${items[j].text}") else null
            if (joined(j) && key(j) == "oh" && joined(j + 1)) {
                minutes = EN_SMALL[key(j + 1)]?.takeIf { it in 1..9 } ?: return null
                j += 2
            } else if (joined(j)) en(j)?.let { (v, n) -> if (v in 10..59) { minutes = v; j += n } }
            val marker = MERIDIEM.find(raw.substring(items[j - 1].end)) ?: return null
            if (items[i].digits != null && minutes == null) return null
            val time = if (minutes == null) "$h" else "$h:${minutes.toString().padStart(2, '0')}"
            return Match(j, items[j - 1].end, time)
        }

        private fun germanDate(i: Int): Match? {
            val n = deOrdinal(key(i))?.takeIf { it in 1..31 } ?: return null
            if (!spaced(i + 1) || key(i + 1) !in DE_MONTHS) return null
            return match(i, i + 1, "$n.")
        }

        private fun englishDate(i: Int): Match? {
            if (key(i) in EN_MONTHS && spaced(i + 1)) {
                val (n, len) = enOrdinal(i + 1)?.takeIf { it.first in 1..31 } ?: return null
                return match(i, i + 1 + len, "${month(i)} $n")
            }
            if (key(i) == "the" && spaced(i + 1)) {
                val (n, len) = enOrdinal(i + 1)?.takeIf { it.first in 1..31 } ?: return null
                val of = i + 1 + len
                if (!spaced(of) || key(of) != "of" || !spaced(of + 1) || key(of + 1) !in EN_MONTHS) return null
                return match(i, of + 2, "${items[i].text} $n${suffix(n)} of ${month(of + 1)}")
            }
            return null
        }
        private fun month(i: Int) = items[i].text.replaceFirstChar(Char::uppercaseChar)

        private fun money(i: Int): Match? {
            val (n, len) = number(i) ?: return null
            if (len == 1 && key(i) in NEVER_ALONE) return null
            val c = i + len
            if (!spaced(c)) return null
            val cur = key(c) ?: return null
            val symbol = EN_CURRENCY[cur]
            if (symbol == null && cur !in DE_CURRENCY) return null
            var end = c + 1
            var cents: Int? = null
            if (spaced(end)) {
                if (symbol == null && isDeNum(end) && runEnd(end) - end >= 2) return unchanged(i, runEnd(end))
                val tail = if (symbol != null) en(end) else de(key(end))?.let { it to 1 }
                if (tail != null && tail.first in 1..99) { cents = tail.first; end += tail.second }
            }
            if (cents == null && items[i].digits != null) return null
            val amount = if (cents == null) group(n, symbol != null) else "$n.${cents.toString().padStart(2, '0')}"
            val text = if (symbol != null) "$symbol$amount" else "$amount ${items[c].text}"
            return match(i, end, text)
        }

        private fun decimal(i: Int): Match? {
            val (n, len) = number(i) ?: return null
            if (len == 1 && key(i) in setOf("ein", "eine", "einen", "a")) return null
            val p = i + len
            if (!spaced(p)) return null
            return when (key(p)) {
                "komma" -> {
                    if (spaced(p + 1) && isDeNum(p + 1) && runEnd(p + 1) - (p + 1) >= 2) return unchanged(i, runEnd(p + 1))
                    val d = if (spaced(p + 1)) de(key(p + 1)) else null
                    d?.let { match(i, p + 2, "$n,$it") }
                }
                "point" -> {
                    val sb = StringBuilder()
                    var j = p + 1
                    while (spaced(j) && EN_DIGIT[key(j)] != null) { sb.append(EN_DIGIT[key(j)]); j++ }
                    if (sb.isEmpty()) null else match(i, j, "$n.$sb")
                }
                else -> null
            }
        }

        private fun cardinal(i: Int): Match? {
            if (items[i].digits != null) return null
            val english = en(i)
            val (n, len) = english ?: deRun(i) ?: return null
            // Two English numbers in a row (years, codes) are left as said.
            if (english != null && (joined(i + len) && en(i + len) != null || joined(i) && en(i - 1) != null)) return null
            if (len == 1 && (key(i) in NEVER_ALONE || key(i) in BARE)) return null
            if (n < 13) {
                if (key(i) in AMBIGUOUS && (english != null || !german)) return null
                val unitNext = spaced(i + len) && key(i + len) in UNITS || PERCENT.containsMatchIn(raw.substring(items[i + len - 1].end))
                val counterBefore = spaced(i) && key(i - 1) in COUNTERS
                if (!unitNext && !counterBefore) return null
            }
            return match(i, i + len, group(n, english != null))
        }
        private fun number(i: Int): Pair<Int, Int>? = items[i].digits?.let { it to 1 } ?: en(i) ?: deRun(i)

        /** English cardinal starting at item i: value and item count. Hyphens and "and" after hundred/thousand join. */
        fun en(i: Int): Pair<Int, Int>? {
            var total = 0; var seg = 0; var last = 0 // 0 none, 1 unit/teen, 2 tens, 3 tens+unit, 4 hundred, 5 thousand
            var j = i
            while (j < items.size && (j == i || joined(j, hyphen = true))) {
                val k = key(j)!!
                val small = EN_SMALL[k]; val tens = EN_TENS[k]
                when {
                    small != null && (last == 0 || last == 4 || last == 5 || (last == 2 && small < 10)) -> { seg += small; last = if (last == 2) 3 else 1 }
                    tens != null && (last == 0 || last == 4 || last == 5) -> { seg += tens; last = 2 }
                    k == "hundred" && last in 1..3 && seg in 1..99 -> { seg *= 100; last = 4 }
                    k == "thousand" && last in 1..4 && total == 0 -> { total = seg * 1000; seg = 0; last = 5 }
                    k == "and" && (last == 4 || last == 5) && joined(j + 1) &&
                        (EN_SMALL[key(j + 1)] != null || EN_TENS[key(j + 1)] != null) -> {}
                    else -> break
                }
                j++
            }
            return if (last == 0) null else (total + seg) to (j - i)
        }

        fun enOrdinal(i: Int): Pair<Int, Int>? {
            EN_ORDINAL[key(i)]?.let { return it to 1 }
            val tens = EN_TENS[key(i)] ?: return null
            val unit = if (joined(i + 1, hyphen = true)) EN_ORDINAL[key(i + 1)]?.takeIf { it < 10 } else null
            return unit?.let { tens + it to 2 }
        }
    }

    private fun suffix(n: Int) = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

    /** Groups of three from 10 000: non-breaking space in German (Schreibweisungen), comma in English. */
    private fun group(n: Int, english: Boolean): String {
        val s = n.toString()
        if (s.length < 5) return s
        val sep = if (english) "," else " "
        return s.reversed().chunked(3).joinToString(sep).reversed()
    }

    // ------------------------------------------------------------- German number words

    /** German cardinal written as one word (dreiundzwanzig, zweihundertfünfzig, tausendzweihundert). */
    private fun de(word: String?): Int? {
        if (word == null || word == "ein" || word == "eine" || word == "einen") return null
        return deThousands(word)
    }
    private fun deThousands(s: String): Int? {
        val t = s.indexOf("tausend")
        if (t < 0) return deHundreds(s, top = true)
        val left = s.substring(0, t); val right = s.substring(t + 7)
        val a = if (left.isEmpty()) 1 else if (left == "ein") 1 else deHundreds(left, top = false) ?: return null
        val b = if (right.isEmpty()) 0 else deHundreds(right, top = false) ?: return null
        return a * 1000 + b
    }
    private fun deHundreds(s: String, top: Boolean): Int? {
        val h = s.indexOf("hundert")
        if (h < 0) return deBelow100(s, top)
        val left = s.substring(0, h); val right = s.substring(h + 7)
        val a = if (left.isEmpty() || left == "ein") 1 else DE_UNITS[left]?.takeIf { it in 2..9 } ?: return null
        val b = if (right.isEmpty()) 0 else deBelow100(right, top = false) ?: return null
        return a * 100 + b
    }
    private fun deBelow100(s: String, top: Boolean): Int? {
        DE_TEENS[s]?.let { return it }
        DE_TENS[s]?.let { return it }
        DE_UNITS[s]?.let { return if (s == "null" && !top) null else it }
        val und = s.indexOf("und")
        if (und <= 0) return null
        val unit = s.substring(0, und).let { if (it == "ein") 1 else DE_UNITS[it]?.takeIf { v -> v in 2..9 } } ?: return null
        val tens = DE_TENS[s.substring(und + 3)] ?: return null
        return tens + unit
    }
    private fun deOrdinal(word: String?): Int? {
        if (word == null) return null
        for (end in listOf("sten", "stes", "ster", "stem", "ste")) {
            if (word.endsWith(end)) de(word.dropLast(end.length))?.takeIf { it >= 20 }?.let { return it }
        }
        for (end in listOf("ten", "tes", "ter", "tem", "te")) {
            if (!word.endsWith(end)) continue
            val stem = word.dropLast(end.length)
            DE_ORDINAL_STEMS[stem]?.let { return it }
            de(stem)?.takeIf { it in 2..19 }?.let { return it }
        }
        return null
    }

    private fun guessGerman(items: List<Item>): Boolean {
        var de = 0; var en = 0
        for (it in items) { if (it.key in DE_HINTS) de++; if (it.key in EN_HINTS) en++ }
        return de > en
    }

    // ------------------------------------------------------------- format of digits already written

    private val SWISS_TIME = Regex("""\b(\d{1,2}):(\d{2})(\s+Uhr\b)""")
    private val SWISS_MONEY_AFTER = Regex("""\b(\d+),(\d{2})(\s+(?:Franken|Rappen|CHF|Euro|EUR)\b)""")
    private val SWISS_MONEY_BEFORE = Regex("""(\b(?:Fr\.|CHF)\s)(\d+),(\d{2})\b""")
    private val ENGLISH_TIME = Regex("""\b(\d{1,2})(?:[.:](\d{2}))?\s*(AM|PM|[ap]\.m\.?)\.?(?!\p{L})""")

    private fun format(text: String): String = text
        .replace(SWISS_TIME) { "${it.groupValues[1]}.${it.groupValues[2]}${it.groupValues[3]}" }
        .replace(SWISS_MONEY_AFTER) { "${it.groupValues[1]}.${it.groupValues[2]}${it.groupValues[3]}" }
        .replace(SWISS_MONEY_BEFORE) { "${it.groupValues[1]}${it.groupValues[2]}.${it.groupValues[3]}" }
        .replace(ENGLISH_TIME) {
            val (h, m, mark) = it.destructured
            if (h.toInt() !in 1..12) it.value
            else h + (if (m.isEmpty()) "" else ":$m") + if (mark[0].lowercaseChar() == 'a') " a.m." else " p.m."
        }

    // ------------------------------------------------------------- lexicon

    private val ITEM = Regex("""\p{L}+(?:['’]\p{L}+)*|\d+""")
    private val MERIDIEM = Regex("""^\s*(?:AM|PM|[ap]\.m\.?)(?!\p{L})""")
    private val PERCENT = Regex("""^\s?%""")

    private val DE_UNITS = mapOf("null" to 0, "eins" to 1, "zwei" to 2, "drei" to 3, "vier" to 4, "fünf" to 5,
        "sechs" to 6, "sieben" to 7, "acht" to 8, "neun" to 9)
    private val DE_TEENS = mapOf("zehn" to 10, "elf" to 11, "zwölf" to 12, "dreizehn" to 13, "vierzehn" to 14,
        "fünfzehn" to 15, "sechzehn" to 16, "siebzehn" to 17, "achtzehn" to 18, "neunzehn" to 19)
    private val DE_TENS = mapOf("zwanzig" to 20, "dreissig" to 30, "vierzig" to 40, "fünfzig" to 50,
        "sechzig" to 60, "siebzig" to 70, "achtzig" to 80, "neunzig" to 90)
    private val DE_ORDINAL_STEMS = mapOf("ers" to 1, "drit" to 3, "sieb" to 7, "ach" to 8)
    private val DE_MONTHS = setOf("januar", "jänner", "februar", "märz", "april", "mai", "juni", "juli", "august",
        "september", "oktober", "november", "dezember")

    private val EN_SMALL = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19)
    private val EN_TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val EN_DIGIT = mapOf("zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9)
    private val EN_ORDINAL = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
        "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11,
        "twelfth" to 12, "thirteenth" to 13, "fourteenth" to 14, "fifteenth" to 15, "sixteenth" to 16,
        "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19, "twentieth" to 20, "thirtieth" to 30)
    private val EN_MONTHS = setOf("january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december")
    private val EN_CURRENCY = mapOf("pound" to "£", "pounds" to "£", "dollar" to "$", "dollars" to "$",
        "euro" to "€", "euros" to "€")
    private val DE_CURRENCY = setOf("franken", "euro", "rappen", "dollar")

    /** Never a number on their own: articles and words that are mostly not counting. */
    private val NEVER_ALONE = setOf("ein", "eine", "einen", "eins", "one", "null", "zero")
    private val BARE = setOf("hundert", "tausend", "hundred", "thousand")
    /** Number words that are also words of the other language. */
    private val AMBIGUOUS = setOf("elf", "null")

    private val UNITS = setOf(
        "gramm", "kilogramm", "kilo", "kilometer", "meter", "zentimeter", "millimeter", "liter", "milliliter",
        "deziliter", "grad", "prozent", "franken", "rappen", "euro", "dollar", "gigabyte", "megabyte", "terabyte",
        "gb", "mb", "tb", "kg", "km", "cm", "mm", "ml", "dl", "bar", "volt", "watt", "kilowatt", "kwh", "psi", "mph",
        "gram", "grams", "kilograms", "kilos", "kilometre", "kilometres", "kilometers", "metre", "metres", "meters",
        "centimetre", "centimetres", "centimeters", "millimetre", "millimetres", "millimeters", "litre", "litres",
        "liters", "millilitre", "millilitres", "milliliters", "mile", "miles", "degree", "degrees", "percent",
        "dollars", "euros", "franc", "francs", "pence", "cent", "cents", "gigabytes", "megabytes", "terabytes",
        "volts", "watts", "bytes", "foot", "feet", "inch", "inches", "yards",
    )
    private val COUNTERS = setOf("gleis", "perron", "nummer", "nr", "seite", "kapitel", "zimmer",
        "platform", "number", "page", "chapter", "room", "gate", "track")

    private val DE_HINTS = setOf("der", "die", "das", "und", "ist", "ich", "nicht", "mit", "für", "den", "dem",
        "auf", "zu", "es", "sie", "wir", "uns", "hat", "war", "um", "ein", "eine", "einen", "von", "im", "am", "sind")
    private val EN_HINTS = setOf("the", "and", "is", "i", "not", "with", "for", "of", "to", "a", "in", "it", "we",
        "you", "was", "at", "on", "are", "they", "this", "that")
}
