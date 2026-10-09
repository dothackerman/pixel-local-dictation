# FUTO baseline (Multilingual-244, as installed)

Measured by Oriol on 2026-10-09, hand-timed from tapping stop until the complete text was in the field. FUTO Keyboard 0.1.30 Play Store, GrapheneOS 2026100601, Android 17, toggle "Disable built-in voice input" off. Sentences read at normal speed, Standard German. Reference sentences are below the results.

## Results

| # | Audio | Wait after stop | Output deviations from reference |
|---|---|---|---|
| D1 (cold) | one sentence | 4.31 s | none |
| D2 | one sentence | 4.09 s | "3. Oktober" (digits for "dritten"; formatting only) |
| D3 | one sentence | 3.17 s | "12. November" (digits; formatting only) |
| D4 | one sentence | 3.49 s | "16 Gigabyte" (digits; formatting only) |
| D5 | one sentence | 3.99 s | **Wintertour** for Winterthur; "Gleis 4", "vor 9" (digits) |
| E1 | one sentence | 4.28 s | none |
| E2 | one sentence | 3.63 s | "3rd of October" (digits; formatting only) |
| D6 | D1–D5 in one recording | **35.85 s** | **"halb drei Uhr"** (inserted word); "16 GB"; **Tenzor Chip**; **"Bono Vintage Tour"** for Bahnhof Winterthur; **"klasse vier"** for Gleis vier |
| E6 | E1–E5 in one recording | **34.75 s** | **Miss Keller** for Mrs Keller; "16 GB", "Platform 4", "before 9" (digits) |

Not measured: E3–E5 as single sentences (stopped once the length effect was clear), memory, heat, battery.

## Reading

- Wait grows roughly with recording length: about 3 to 4 s for a 4 to 5 s sentence, about 35 s for about 35 s of speech. FUTO decodes after stop at close to one times real time on this phone. The speed target in [requirements](requirements.md) therefore means a much lower real-time factor, not a faster start.
- Short single sentences are nearly error-free in both languages. The long German recording degraded badly on names and one common word (Gleis), the long English one only on a title.
- Digit rendering of spoken numbers and dates is a formatting convention, not a recognition error; the milestone 3 scoring must normalise it before counting word errors, and report it separately.

## Reference sentences

German: D1 Ich bin morgen um halb drei in Zürich und bringe die Unterlagen mit. D2 Kannst du mir bitte die Rechnung vom dritten Oktober nochmals schicken? D3 Das Meeting mit Frau Keller wurde auf den zwölften November verschoben. D4 Der neue Server hat sechzehn Gigabyte Arbeitsspeicher und einen Tensor-Chip. D5 Wir treffen uns am Bahnhof Winterthur, Gleis vier, kurz vor neun Uhr.

English: E1 I'll be in Zurich tomorrow at half past two and bring the documents. E2 Could you send me the invoice from the third of October again, please? E3 The meeting with Mrs Keller has been moved to the twelfth of November. E4 The new server has sixteen gigabytes of memory and a Tensor chip. E5 Let's meet at Winterthur station, platform four, shortly before nine.

## Raw output as dictated

D1–D5 singly: "Ich bin morgen um halb drei in Zürich und bringe die Unterlagen mit. Kannst du mir bitte die Rechnung vom 3. Oktober nochmals schicken? Das Meeting mit Frau Keller wurde auf den 12. November verschoben. Der neue Server hat 16 Gigabyte Arbeitsspeicher und einen Tensor-Chip. Wir treffen uns am Bahnhof Wintertour, Gleis 4 kurz vor 9."

E1–E2 singly: "I'll be in Zurich tomorrow at half past two and bring the documents. Could you send me the invoice from the 3rd of October again please?"

D6: "Ich bin morgen um halb drei Uhr in Zürich und bringe die Unterlagen mit. Kannst du mir bitte die Rechnung vom 3. Oktober nochmals schicken? Das Meeting mit Frau Keller wurde auf den 12. November verschoben. Der neue Server hat 16 GB Arbeitsspeicher und einen Tenzor Chip. Wir treffen uns am Bono Vintage Tour, klasse vier, kurz vor neun."

E6: "I'll be in Zurich tomorrow at half past two and bring the documents. Could you send me the invoice from the 3rd of October again please? The meeting with Miss Keller has been moved to the 12th of November. The new server has 16 GB of memory and a tensor chip. Let's meet at Winterthur Station Platform 4 shortly before 9."
