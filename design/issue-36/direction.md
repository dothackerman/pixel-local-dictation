# #36 direction: Crate B2 (approved)

Preview: `direction.html`. It has font 1×/2× and still-frame switches, a glyphs-only marker test, the icon review and a live contrast table.

One fixed **light** scheme for every surface. No DayNight, no dynamic colour. The light/dark jump against the dark keyboard is shown deliberately.

## Tokens (`Theme.Dictation`, Material3 Light)

| Role | Hex | Role | Hex |
|---|---|---|---|
| surface | #F3E9D4 | primary | #A52E07 |
| surfaceContainer (panel) | #E9DCC1 | onPrimary | #FFFFFF |
| surfaceContainerHigh | #DDCEAF | primaryContainer/on | #FFD8C6/#3A0E00 |
| onSurface | #1C1A12 | inversePrimary (Snackbar Undo) | #FFB59A |
| onSurfaceVariant | #4E4838 | secondary/onSecondary (switch on) | #2E4A21/#FFFFFF |
| outline | #6D6550 | error/onError | #8A1145/#FFFFFF |
| outlineVariant | #C7B999 | errorContainer/on | #F8D7E3/#3D0019 |

WCAG contrast: every pair passes. The page lists all of them.

| What | Ratio |
|---|---|
| Panel status | 12.8 |
| Preview | 6.7 |
| Glyph on the disk | 7.0 |
| Arc, disk, ring and panel Undo | 5.17 |
| Snackbar Undo | ≥4.5 |
| Row markers | 14.4 |

## Panel

- **Mic disk:** the same red-orange disk in every state; mic becomes Stop. Back is labelled by state: "Pause and choose" while recording, "Continue recording" while paused.
- **Paused:** three 64×56dp icon buttons (the status says only "Paused"; while preparing it says "Still preparing text"):
  - Continue: filled and preselected.
  - Stop and insert: outlined.
  - Discard take: dashed, set 40dp apart.
  - Tooltips and TalkBack labels carry the words.
- **Recording ring:** a concentric ribbon of even thickness; loudness grows its radius and thickness uniformly, never its shape.
- **Preparing text…:** an animated arc. With animations off it becomes a static segmented ring.
- **Unable to insert text:** the status plus its marker. Back exits and the mic stays.
- **Preview:** always 3 lines, newest last.
- **PROPOSED, needs approval: Take discarded · Undo.** After Discard, Undo stays available for 8 s with a timer rule, then the panel returns to the keyboard.
  - **Rule:** while Undo is offered, the panel shows only Undo (a filled primary button) and Back. There is no mic, so nothing ends the window by accident. The mic returns with the keyboard when the window ends.
  - Today a confirmed Discard is final, so this changes the service's discard path: delay the delete and keep the take until the window closes.

## Wording

Use one word for finishing: **"Preparing text"**. Change two strings:
- `status_finishing` "Finishing…" → "Preparing text…"
- `history_finishing` "Finishing" → "Preparing text"

DESIGN.md uses "Finishing…" in its landscape section, so it needs the same update.

## Markers

The family is Material Symbols Sharp, FILL 0, wght 400, opsz 24 (Apache 2.0), plus two custom glyphs (cut short, uncertain) on the same 24dp grid. Each marker has one sentence, used for the detail line, TalkBack and the sheet.

| State | Glyph | Sentence |
|---|---|---|
| recording | `radio_button_checked` (red-orange) | Still recording. The text so far is saved. |
| preparing | `hourglass_top` | Still preparing text. It appears when ready; open it again in a moment. |
| cut short | custom: text lines stopped by a solid vertical cut bar (survives 24dp) | Cut short. Words at the end may be missing. |
| not inserted / not attempted | `edit_off` | Unable to insert text. Copy it to use it. |
| uncertain | custom: outlined, unboxed question mark at 3dp (the one stroke-weight exception) | Insertion uncertain. Check the app before you paste. |

Rules:
- Pause, play and stop never appear in a marker slot.
- On the panel, a marker shows only when nothing else on the panel shows the state.
- No two glyphs in the app share a silhouette. The menu's boxed `info` stays boxed, and the uncertain mark is unboxed.
- Actions use `play_arrow`, `stop`, `delete`, `content_copy`, `history`, `lock`, `info`, `arrow_back`, `more_vert`.

## History

- The header is only "Audio is not stored."
- The overflow menu has two items:
  - **"Marks explained"** (approved; first proposed as "What the marks mean", which truncated at 2×), which opens a bottom sheet listing every mark with its sentence.
  - "Clear History…" (built name; proposed as "Delete all…"), which keeps its confirmation: one plain sentence, e.g. "Deletes 4 saved transcripts. Clipboard copies and benchmark recordings stay." (in-progress takes are named when there are any).
- In the detail, Copy and Delete sit at the end of the top bar, both 48dp, with a 16dp gap. Undo after delete is the protection.
- The detail's marker-plus-sentence line is a 48dp button with a chevron. It opens the marks sheet with that mark highlighted.
- Deleting from a row or from the detail gives an 8 s Undo Snackbar. The row disappears without leaving a gap.

## Type and shape

- **Big Shoulders Display 800:** titles, status, headers, times and every count.
- **Public Sans 400:** body text.
- Corners are 0dp except the mic disk and ring. 2dp rules, 1dp hairlines. Targets are at least 48dp, 56dp on the panel.

## Android translation notes

- **Continue's ring** is a fixed state drawable (a `LayerDrawable` stroke), not focus. Real keyboard focus still uses the system focus highlight.
- **Dashed Discard border:** `GradientDrawable` with `setStroke(width, color, dashWidth, dashGap)`.
- **Square switch:** `MaterialSwitch` with custom track and thumb drawables (0dp rectangles), a track tint list for on/off, and a thumb that moves. Sizes sit on `<layer-list>` items (track 52×32dp, thumb cell 32dp holding a 20dp on / 16dp off square): a bare selector has no size until its state is set, and the switch then measured about 29×16dp on the device.
- **Underlined text button** (Delete benchmark data): an `UnderlineSpan` on a `MaterialButton.TextButton`.
- **Panel Undo:** a filled `MaterialButton` (primary, 0dp corners, at least 56dp tall), not a span.
- **Line breaking:** `breakStrategy="high_quality"` and `hyphenationFrequency="normal"` on transcripts. For German compounds at 2×, check the panel status with `lineBreakWordStyle="phrase"`.
- **Preview clipped to the newest 3 lines:** `maxLines=3` with `ellipsize=start`, plus `gravity=bottom`. This keeps the current 240-character whole-word tail.
- **Markers:** `ic_marker_*.xml` vector drawables. Each row puts one `ImageView` in its slot, with `importantForAccessibility=no`; the words go into the row's content description.
- **Sheet:** `BottomSheetDialog` with a ShapeAppearance at 0dp, on the plain surface (`elevationOverlayEnabled=false`; Material's elevation tint otherwise turns it primary-warm). The highlighted mark gets a 4dp ink start bar.
- **Destructive dialogs** (Clear History, Delete benchmark data): `ThemeOverlay.Dictation.Dialog.Destructive`, the confirm in error colour, Cancel in onSurface.
- **Dictionary in landscape:** the explanation, search and count scroll away (`AppBarLayout`, `scroll|enterAlways`); Add term is a top-bar action, not a FAB over the rows. The add/edit sheet is one header row (field with a placeholder, Add, Close) with the error directly under the field. The search field has no Material box mode, so its text stays on the magnifier's line at font 1.3× and 2×.
- **Preview measure:** at most 28em (about 70 characters) in the wide landscape column (`PreviewTextView`).

## Files

- Theme: `themes.xml`, `colors.xml`. Delete `values-night/settings_colors.xml`.
- Type and fonts: `type.xml`, `res/font/*`.
- Icons: `drawable/ic_*.xml`.
- Manifest.
- `DictationImeService.kt`: themed context, paused trio, marker slot, proposed Undo.
- Remove DynamicColors.
- Panel: both `ime.xml`, `RecordingRingView.kt`.
- History: `history*.xml`, `menu/history.xml`, the new marks sheet, `HistoryActivity.kt`.
- Settings: `settings.xml`.
- `strings.xml`, DESIGN.md.

## To fetch

| Asset | Licence | What | Size (estimate) |
|---|---|---|---|
| Big Shoulders Display | OFL 1.1 | weights 700 and 800 | ≈0.15–0.25 MB total with Public Sans |
| Public Sans | OFL 1.1 | weights 400 and 600 | (included above) |
| Material Symbols Sharp | Apache 2.0 | ~18 glyph XMLs | ≈1 KB each |

## Risks

- **The light/dark jump.**
- **Icon-only paused buttons** on first use.
- **Markers are a new convention**, mitigated by the sheet.
- **The Undo window is unapproved behaviour.**
- **Not verified:** the page's glyphs are hand drawings; the device; TalkBack.
