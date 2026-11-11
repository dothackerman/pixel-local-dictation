---
name: Pixel Local Dictation
description: Crate B2 — kraft card printed in two inks, one fixed light scheme for the recording panel and every screen.
colors:
  surface: "#F3E9D4"
  surface-container: "#E9DCC1"
  surface-container-high: "#DDCEAF"
  on-surface: "#1C1A12"
  on-surface-variant: "#4E4838"
  outline: "#6D6550"
  outline-variant: "#C7B999"
  primary: "#A52E07"
  on-primary: "#FFFFFF"
  primary-container: "#FFD8C6"
  on-primary-container: "#3A0E00"
  inverse-primary: "#FFB59A"
  secondary: "#2E4A21"
  on-secondary: "#FFFFFF"
  secondary-container: "#CFE0BC"
  on-secondary-container: "#13240A"
  error: "#8A1145"
  on-error: "#FFFFFF"
  error-container: "#F8D7E3"
  on-error-container: "#3D0019"
typography:
  title-large:
    fontFamily: "Big Shoulders Display"
    fontWeight: 800
    fontSize: "26sp"
    lineHeight: "30sp"
  title-medium:
    fontFamily: "Big Shoulders Display"
    fontWeight: 800
    fontSize: "18sp"
    lineHeight: "22sp"
  title-small:
    fontFamily: "Big Shoulders Display"
    fontWeight: 800
    fontSize: "15sp"
    lineHeight: "20sp"
    letterSpacing: "0.03em"
  label-large:
    fontFamily: "Big Shoulders Display"
    fontWeight: 800
    fontSize: "16sp"
    lineHeight: "20sp"
  label-medium:
    fontFamily: "Big Shoulders Display"
    fontWeight: 700
    fontSize: "14sp"
    lineHeight: "18sp"
  body-large:
    fontFamily: "Public Sans"
    fontWeight: 400
    fontSize: "16sp"
    lineHeight: "25sp"
  body-medium:
    fontFamily: "Public Sans"
    fontWeight: 400
    fontSize: "14sp"
    lineHeight: "20sp"
rounded:
  none: "0dp"
  disk: "35dp"
spacing:
  inset: "24dp"
  panel-inset: "12dp"
  status-indent: "56dp"
  hairline: "1dp"
  rule: "2dp"
components:
  toolbar:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    typography: "{typography.title-large}"
    height: "64dp"
  microphone-disk:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.disk}"
    size: "70dp"
  paused-continue:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.none}"
    width: "64dp"
    height: "56dp"
  paused-stop:
    backgroundColor: "{colors.surface-container}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.none}"
    width: "64dp"
    height: "56dp"
  paused-discard:
    backgroundColor: "{colors.surface-container}"
    textColor: "{colors.error}"
    rounded: "{rounded.none}"
    width: "64dp"
    height: "56dp"
  panel-undo:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.label-large}"
    rounded: "{rounded.none}"
    height: "56dp"
  history-row:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    typography: "{typography.body-large}"
    padding: "12dp 24dp"
  icon-button:
    textColor: "{colors.on-surface-variant}"
    size: "48dp"
---
# Design System: Pixel Local Dictation — Crate B2

## Overview

**Creative North Star: "Kraft card printed in two inks."** Red-orange (primary) and rind green (secondary) on a warm card ground, a condensed display face for everything that is a name, a time or a count, and a plain sans for what the owner reads. One fixed **light** scheme for every surface: the recording panel, inline choice, Undo, Settings, Dictionary (list, add/edit sheet, empty state), History (list, detail, empty state, status slot, dialogs, marks sheet) and Snackbars. Benchmark-only guide screens may stay plain. The light panel next to a dark keyboard is deliberate (owner decision).

Approved direction and frames: [design/issue-36/direction.md](design/issue-36/direction.md), `direction.html` (#36; both proposals in it — Undo after Discard, "What the marks mean" — are approved).

Source of truth: `app/src/main/res/values/{colors,themes,type}.xml`, `res/font/*`, `res/drawable/ic_*.xml`, the layouts named below, `RecordingPanel.kt` (`renderPanel`), `RecordingRingView.kt`, `PreparingArcView.kt`, `HistoryRows.kt` (`HistoryMarker`). Frontmatter keeps Android dp/sp.

## Colors

`Theme.Dictation` (parent `Theme.Material3.Light.NoActionBar`) is the only application theme. No DayNight, no `values-night`, no dynamic colour. The panel inflates from a `ContextThemeWrapper` with the same theme (the input method service's own theme is the platform's). Hex values live only in `values/colors.xml`; layouts, drawables and Kotlin resolve roles (`?attr/colorPrimary`, `MaterialColors.getColor`).

| Role | Used for |
|---|---|
| surface | Screen ground, menus, sheets, dialogs, the field inside the panel ring |
| surfaceContainer | Recording panel ground; highlighted mark in the marks sheet |
| surfaceContainerHigh | Search field, switch track off |
| onSurface | Status, titles, transcripts, markers, Stop outline, Continue's selected ring, timer rule, 2dp rules, Snackbar ground (inverse) |
| onSurfaceVariant | Preview, explanations, times in meta, counts, trash icons |
| primary | Microphone disk (every state), recording ring, Preparing arc, Continue fill, Undo, recording marker |
| onPrimary | Glyphs on the disk and Continue, Undo label |
| inversePrimary | Snackbar Undo |
| secondary / secondaryContainer | Switch on; History warning row |
| outline / outlineVariant | Panel top rule, resting ring hairline, switch off edge / row hairlines, arc track, timer track |
| error / errorContainer | Discard take (dashed), Clear History…, Delete benchmark data / benchmark banner |

Tertiary roles follow secondary so no Material lavender appears. Contrast (WCAG 2.x, `ThemeContrastTest` resolves them from the theme): text pairs ≥ 4.5:1 (panel status 12.8, preview 6.7, glyph on disk 7.0, primary on panel 5.17, Snackbar Undo 10.2), essential indicators ≥ 3:1 (resting ring 4.27, markers 14.4, Discard edge 6.89, switch on 8.22). Error and red-orange are close in lightness (1.33:1), so a destructive control is never told apart by colour alone: Discard is the only dashed button, carries the trash glyph, sits apart, and is followed by Undo.

## Typography

Bundled in `res/font` (SIL OFL 1.1, `licenses/`): **Big Shoulders Display** 700/800 and **Public Sans** 400/600, static instances subset to Latin. Public Sans has no capital ẞ; Android falls back to the system face for that one glyph.

| Role | Where | Face |
|---|---|---|
| titleLarge | Toolbar and sheet titles | Big Shoulders Display 800, 26/30sp |
| titleMedium | Panel status, empty-state title | Big Shoulders Display 800, 18/22sp |
| titleSmall | Day headers, row times, detail meta, every count | Big Shoulders Display 800, 15/20sp, +0.03em, tabular figures |
| labelLarge | Buttons, Undo, Snackbar action | Big Shoulders Display 800, 16/20sp |
| labelMedium | Private-field line, banner, editor feedback | Big Shoulders Display 700, 14/18sp |
| bodyLarge | Rows, transcript, Settings rows, menus | Public Sans 400, 16/25sp |
| bodyMedium | Panel preview, explanations, mark sentences | Public Sans 400, 14/20sp |

**The Display Rule.** Names, times and counts are in the display face; anything the owner reads as prose is Public Sans. Everything is sp and survives font scale 2. Transcripts and the panel status use `breakStrategy="high_quality"` and `hyphenationFrequency="normal"`; the status also `lineBreakWordStyle="phrase"` for German compounds.

## Shape and rules

Corners are 0dp everywhere (`ShapeAppearance.Dictation.Square` on every corner family, buttons, FAB, fields, sheets, dialogs, Snackbars, switch) except the microphone disk and its ring. A 2dp onSurface rule closes every toolbar and the panel's top edge (outline); 1dp hairlines separate rows. Targets are at least 48dp; 56dp on the panel (status row, Undo), 64×56dp for the paused buttons. The switch is square: rind-green track and a white thumb at the end when on, a smaller outline-ink thumb at the start when off (position and colour differ).

## Glyphs and markers

Material Symbols Sharp (FILL 0, wght 400, opsz 24; Apache 2.0) as vector drawables, plus two custom glyphs on the same 24dp grid. Actions: `ic_arrow_back`, `ic_mic`, `ic_stop` and `ic_play_arrow` (transport, FILL 1), `ic_delete`, `ic_content_copy`, `ic_history`, `ic_lock`, `ic_info`, `ic_more_vert`, `ic_chevron_right`, `ic_close`, `ic_add`, `ic_search`. No two glyphs share a silhouette; pause, play and stop never appear in a marker slot.

| Mark (`HistoryMarker`) | Glyph | Sentence (detail line, TalkBack, sheet) |
|---|---|---|
| Still recording | `radio_button_checked`, red-orange | Still recording. The text so far is saved. |
| Preparing text | `hourglass_top` | Still preparing text. It appears when ready; open it again in a moment. |
| Cut short | custom: text lines stopped by a solid cut bar | Cut short. Words at the end may be missing. |
| Unable to insert text (not inserted / not attempted) | `edit_off` | Unable to insert text. Copy it to use it. |
| Insertion uncertain | custom: outlined, unboxed "?" at 3dp | Insertion uncertain. Check the app before you paste. |

**The One Mark Rule.** At most one mark per take, by precedence: in progress, cut short, unable to insert, uncertain. A finished, inserted take carries none. Marks are onSurface except the recording mark. On the panel a mark shows only when nothing else shows the state (today: Unable to insert text).

## Components

### Recording panel (`layout/ime.xml`, `layout-land/ime.xml`, `panel_status.xml`, `panel_stage.xml`, `panel_choice.xml`, `panel_undo.xml`)

Status block (both orientations): Back (48dp, labelled by state), status (titleMedium, polite live region) with its trailing mark slot, the private-field line (lock glyph + labelMedium, plain, no box) and the preview (bodyMedium, always three lines, newest last, `PanelPreview`: the last 240 characters from a whole word; at 2× older lines clip at the top), all indented to the status (56dp). The Undo timer rule (2dp, onSurface on outlineVariant) sits at the top of the preview's place while the preview is hidden, so the panel keeps its height. The panel inflates without AppCompat's view factory, so its layouts use platform `android:tint` and `android:drawableStart`. Portrait: a 200dp stage under it. Landscape: the stage sits under the right thumb (232×148dp, square 148dp under 600dp); the panel is compact so the editor stays visible; extract mode is never used.

| State | Status | Stage | Back label |
|---|---|---|---|
| Idle | Tap the microphone to record | red-orange disk, mic glyph, hairline ring around a pale field | Back to keyboard |
| Recording | Recording… | same disk, Stop square; thick red-orange ribbon following loudness (static thick ring with animations off) | Pause and choose |
| Paused · choose | Paused · choose | three icon buttons in one row at one height: Continue (filled, fixed selected ring, focused), Stop and insert (outlined), Discard take (dashed error, 40dp apart); tooltips and content descriptions carry the words | Continue recording |
| Preparing text… | Preparing text… | no disk: a small red-orange arc on an outline-variant track; with animations off a static segmented ring | Hold text and choose |
| Preparing text · choose | Preparing text · choose | Keep preparing text (filled) and Discard take | Keep preparing text |
| Take discarded | Take discarded + 2dp timer rule | only Undo (filled, ≥56dp); no microphone | Back to keyboard |
| Unable to insert text | Unable to insert text + `edit_off` mark | disk with mic glyph (a new take can start) | Back to keyboard |

The disk is one colour in every state; the glyph and status say what it does. Portrait: the choice and Undo take the stage's place (panel height unchanged). Landscape: they take the preview's place in the status column and the stage stays empty. Rotation rebuilds the panel from the take's state (choice, Undo window, Preparing) and never starts, stops, chooses or discards. The benchmark banner is errorContainer.

**Undo after Discard take.** 8 s (`DISCARD_UNDO_MS`), the History-delete model. Nothing is inserted, the microphone is not recording and the take is not listed in History during the window. Undo restores the same take at its choice; the window ending, Back, a system interruption or hiding the panel makes the discard final and (window end, Back) returns to the keyboard. Lifecycle rules: [ADR-010](adrs/010-ime-lifecycle.md).

### Settings (`layout/settings.xml`)

The shared toolbar structure: MaterialToolbar (64dp, "Dictation") and its 2dp rule outside the scroll; system bars inset the page. Rows on the 24dp inset, ≥64dp, separated by hairlines: Dictionary (term count) and History (saved count, "N saved", spoken "History · N"), counts in titleSmall before a chevron. A "Benchmark" section header over a 2dp rule, then a MaterialSwitch row, its explanation, "N recordings saved", and "Delete benchmark data" as an underlined error-colour text button with the trash glyph, behind a MaterialAlertDialog with a title.

### Dictionary (`layout/dictionary*.xml`)

Same toolbar and rule; explanation, a square filled search field (surfaceContainerHigh, magnifier, clear), count and languages in titleSmall over a 1dp ink rule (no "0 terms" on an empty dictionary: the state line says it), rows on the 24dp inset with hairlines and a 48dp trash, a square extended Add FAB. The add/edit bottom sheet (square, `BottomSheetDialog`) puts the title, the commit button (Add / Done, ≥96dp) and Close in its header row over a 2dp rule, so the commit stays reachable above the keyboard in landscape and at font 2; the field and feedback scroll beneath (`NestedScrollView`); fields set `flagNoExtractUi`. Semantics are unchanged: per-entry save, Undo Snackbar after delete, validation on the field ([PRODUCT.md](PRODUCT.md)).

### History (`layout/history*.xml`, `menu/history.xml`, `HistoryActivity.kt`, `HistoryRows.kt`)

- **List.** Header item "Audio is not stored." (scrolls away), then day headers (Today, Yesterday, "Mon 9 Nov"; with the year in other years) in titleSmall over a 1dp ink rule. Rows lead with the time (titleSmall), one 24dp mark slot under it, the preview (two lines, bodyLarge), a trailing 48dp trash. No text chips. A row is one accessibility node: "time, preview, mark sentence"; long-click is relabelled "Copy transcript", "Delete transcript" is a named action, the trash ("Delete transcript from <date>: <first words>") is the only nested target. Long-press copies the full transcript (no sensitive flag; the system confirms). Empty state: left-aligned title and hint, the same pattern as Dictionary's state line.
- **Overflow menu.** "What the marks mean" (info glyph) then "Clear History…" (error colour), which keeps its confirmation dialog (title, saved and in-progress counts, button "Clear", no Undo).
- **Detail.** Title: the day. Meta "14:05 · 23 words" ("12 words so far" while recording). The mark's sentence is a 48dp button with the glyph and a chevron; it opens the marks sheet at that mark (highlighted, selected). Copy and Delete are icon actions at the end of the top bar, 48dp, Delete 16dp after Copy, both with content descriptions and tooltips. The transcript is selectable.
- **Marks sheet.** A square `BottomSheetDialog`: every mark with its glyph, name and one sentence, and "No mark — Finished and inserted."
- **Delete model** (unchanged): one transcript, no dialog, the row goes at once, Snackbar Undo for 8 s; storage is touched when the window ends or the page is left.
- **Status slot** (unchanged behaviour): one slot holds the page's own state as a plain line, else one pending warning as a secondaryContainer row with a 48dp Dismiss. Never two.
- **Privacy** (unchanged): secure window (also the sheet), no transcript text in logs, intents or saved state; no Toasts.

## Elevation and motion

Flat card: no shadows beyond Material's own sheet/Snackbar scrim. Motion is functional only: the recording ribbon follows loudness, the Preparing arc turns. With animations off both have a still form that reads correctly (thick static ring; segmented ring).

## Do's and Don'ts

### Do:

- **Do** resolve theme roles; hex values only in `values/colors.xml`.
- **Do** give every state words: status text, content descriptions and tooltips on icon-only buttons, one sentence per mark.
- **Do** keep 48dp targets (56dp on the panel) and check font scale 2 in portrait and landscape.
- **Do** keep Undo after deletion and after Discard take.

### Don't:

- **Don't** add DayNight resources, dynamic colour or a second theme.
- **Don't** round corners (except the microphone disk and ring) or add shadows.
- **Don't** put more than one mark on a row, or put transport glyphs in a mark slot.
- **Don't** add a global Save step to the per-entry editor or promise accuracy gains for new terms.

Verification: host — `ThemeContrastTest`, `HistoryRowsTest`, `HistoryScreenTest`, `SettingsScreenTest`, `DictationProductionLifecycleTest` (panel states, Undo exit paths, choice layout at font 2 portrait/landscape); device — `RecordingPanelTest.captureEveryPanelStateAtBothFontScales` (`files/lab/issue36/b2-panel`), `SettingsHistoryScreenshotTest` (`files/lab/settings-history`), `HistoryLayoutTest`, `DictionarySettingsTest`. Private captures: `corpus/lab/issue36/b2/`.
