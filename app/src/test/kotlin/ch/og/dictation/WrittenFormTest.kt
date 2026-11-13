package ch.og.dictation

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spoken numbers that Parakeet leaves as words become digits only where the context is clear. */
class WrittenFormTest {
    private fun same(vararg texts: String) = texts.forEach { assertEquals(it, WrittenForm.of(it)) }
    private fun check(vararg pairs: Pair<String, String>) = pairs.forEach { (said, written) -> assertEquals(said, written, WrittenForm.of(said)) }

    @Test fun germanCardinalsFromThirteenBecomeDigits() = check(
        "Es kamen dreiundzwanzig Leute." to "Es kamen 23 Leute.",
        "Wir brauchen zweihundertfünfzig Stühle und siebzehn Tische." to "Wir brauchen 250 Stühle und 17 Tische.",
        "Das kostet tausendzweihundert Franken." to "Das kostet 1200 Franken.",
        "Es waren fünfundvierzigtausend Fans." to "Es waren 45 000 Fans.",
        "Achtzehn sind genug." to "18 sind genug.",
    )

    @Test fun germanSmallNumbersStayWordsWithoutAUnit() = same(
        "Ich habe zwölf alte Bücher und drei Kinder.",
        "Beim dritten Versuch klappte es.",
        "Wir treffen uns um halb drei.",
        "Ich habe einen Plan, und eins ist klar: ein Hund reicht.",
        "Das ist gleich null.",
        "Tausend Dank und hundert Grüsse.",
    )

    @Test fun germanSmallNumbersBeforeAUnitOrAfterACounterNoun() = check(
        "Im Keller hat es elf Grad, dazu sechs Gramm Zucker." to "Im Keller hat es 11 Grad, dazu 6 Gramm Zucker.",
        "Lies Seite vier, der Zug steht auf Gleis drei." to "Lies Seite 4, der Zug steht auf Gleis 3.",
        "Es sind zwanzig Prozent." to "Es sind 20 Prozent.",
        "Mein Laptop hat acht GB Speicher." to "Mein Laptop hat 8 GB Speicher.",
    )

    @Test fun germanTimesUseTheSwissPoint() = check(
        "Der Bus fährt um elf Uhr dreiundzwanzig." to "Der Bus fährt um 11.23 Uhr.",
        "Wir essen um neun Uhr." to "Wir essen um 9 Uhr.",
        "Die Sitzung beginnt um 8:45 Uhr." to "Die Sitzung beginnt um 8.45 Uhr.",
        "Um vierzehn Uhr fünf beginnt es." to "Um 14.05 Uhr beginnt es.",
        "Um 14 Uhr 35 beginnt es." to "Um 14.35 Uhr beginnt es.",
    )

    @Test fun germanDatesNeedAMonth() = check(
        "Das Fest ist am fünften Juni." to "Das Fest ist am 5. Juni.",
        "Ferien vom dritten August bis zum einundzwanzigsten März." to "Ferien vom 3. August bis zum 21. März.",
        "Der zweite Versuch am ersten Tag." to "Der zweite Versuch am ersten Tag.",
    )

    @Test fun germanMoneyAndDecimals() = check(
        "Das Brot kostet sechs Franken vierzig." to "Das Brot kostet 6.40 Franken.",
        "Ein Kaffee kostet 5,20 Franken." to "Ein Kaffee kostet 5.20 Franken.",
        "Der Eintritt kostet Fr. 12,50 pro Person." to "Der Eintritt kostet Fr. 12.50 pro Person.",
        "Der Wert liegt bei zwei Komma sieben Volt." to "Der Wert liegt bei 2,7 Volt.",
        "Der See ist 14,2 Kilometer lang." to "Der See ist 14,2 Kilometer lang.",
        "Ein Komma fehlt hier." to "Ein Komma fehlt hier.",
    )

    @Test fun germanSpacedCompoundsCombineWhole() = check(
        "zweitausend dreihundert fünfundvierzig Franken" to "2345 Franken",
        "dreitausend zweihundert dreissig" to "3230",
        "dreissigtausend fünfhundert zwanzig" to "30 520",
        "tausend zweihundert dreissig Komma fünf Volt" to "1230,5 Volt",
        "zweitausend dreihundert Franken achtzig" to "2300.80 Franken",
        "dreihundert fünfundvierzig Franken achtzig" to "345.80 Franken",
        "tausend zwanzig Komma fünf" to "1020,5",
    )

    @Test fun germanAdjacentNumbersStayWords() = same(
        "zwanzig drei Leute", "zwanzig zwanzig", "dreitausend dreitausend", "sechzehn zweihundert Franken",
        "zweihundert zweihundert", "dreizehn vierzehn fünfzehn",
        "null neunundsiebzig dreihundertvierzig zwölf vierunddreissig",
        "zwei millionen dreihundert tausend Leute", "neunzehnhundert vierundachtzig",
        "fünfzehn Franken zwanzig dreissig", "zwanzig Uhr dreissig vierzig", "dreizehn Komma fünf zwei",
    )

    @Test fun bareHundertTausendNeverBecomeAWrongAmount() = same(
        "drei hundert Franken", "ein tausend zwei hundert Franken",
    )

    @Test fun englishCardinalsFromThirteenBecomeDigits() = check(
        "Pour in two hundred and fifty milliliters of milk." to "Pour in 250 milliliters of milk.",
        "We counted forty five birds and forty-six bees." to "We counted 45 birds and 46 bees.",
        "Sixteen people came." to "16 people came.",
        "It took twelve thousand five hundred steps." to "It took 12,500 steps.",
        "The river rose from fourteen to eleven feet, then nine miles flooded." to "The river rose from 14 to 11 feet, then 9 miles flooded.",
    )

    @Test fun englishSmallNumbersAndAmbiguousWordsStay() = same(
        "I have two dogs and one idea, for now.",
        "One of them won a prize at two.",
        "Wait a second, the first try was fine.",
        "A hundred thanks and a thousand apologies.",
        "The elf ate ten cookies.",
    )

    @Test fun englishSmallNumbersBeforeAUnitOrAfterACounterNoun() = check(
        "The oven was eleven degrees too cold, so add six grams of yeast." to "The oven was 11 degrees too cold, so add 6 grams of yeast.",
        "Meet at platform four, room nine." to "Meet at platform 4, room 9.",
        "The server has sixteen gigabytes." to "The server has 16 gigabytes.",
    )

    @Test fun englishTimes() = check(
        "Call me at ten fifteen AM tomorrow." to "Call me at 10:15 a.m. tomorrow.",
        "Lunch ends at twelve forty five PM." to "Lunch ends at 12:45 p.m.",
        "We start at nine a.m. sharp." to "We start at 9 a.m. sharp.",
        "By three twenty PM. she had left." to "By 3:20 p.m. she had left.",
        "It starts at seven oh five PM." to "It starts at 7:05 p.m.",
        "The class starts at 8.40 a.m." to "The class starts at 8:40 a.m.",
    )

    @Test fun englishDatesNeedAMonth() = check(
        "The party is on july fourth." to "The party is on July 4.",
        "Send the invoice from the third of October." to "Send the invoice from the 3rd of October.",
        "It moved to the twenty-second of May." to "It moved to the 22nd of May.",
        "On March thirteenth, 2025, it opened." to "On March 13, 2025, it opened.",
    )

    @Test fun englishMoneyAndDecimals() = check(
        "A sandwich costs three pounds twenty." to "A sandwich costs £3.20.",
        "The repair was sixty five pounds." to "The repair was £65.",
        "It costs eighteen dollars." to "It costs $18.",
        "The score rose by two point seven percent." to "The score rose by 2.7 percent.",
        "Make your point five times." to "Make your point five times.",
    )

    @Test fun spokenYearsAndCodesStayWhole() = same(
        "It happened in twenty twenty-six.",
        "Call nineteen eighty four now.",
    )

    @Test fun applyingTwiceChangesNothing() {
        for (said in listOf("Abfahrt um zwölf Uhr sechsundvierzig, vier Franken achtzig.", "At ten fifteen AM it cost four pounds eighty.")) {
            val once = WrittenForm.of(said)
            assertEquals(once, WrittenForm.of(once))
        }
    }

    @Test fun textWithoutNumbersIsUntouched() = same(
        "", "Grüezi mitenand.", "Hello, world!", "Code A-12 und 3091 bleiben.", "Wir brauchen 400 g Mehl für 3 Brote.",
    )
}
