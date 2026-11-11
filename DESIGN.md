---
name: Pixel Local Dictation
description: Native Android settings for private local dictation.
colors:
  primary: "#426B32"
  on-primary: "#FFFFFF"
  primary-container: "#CDEDBA"
  on-primary-container: "#10200A"
  surface: "#F8FAF3"
  on-surface: "#1B211B"
  surface-variant: "#E1E7DB"
  on-surface-variant: "#41493D"
  outline: "#71796B"
  night-primary: "#ADD994"
  night-on-primary: "#163908"
  night-primary-container: "#2D5220"
  night-on-primary-container: "#CDEDBA"
  night-surface: "#11150E"
  night-on-surface: "#E1E5D9"
  night-surface-variant: "#41493D"
  night-on-surface-variant: "#C1C9B8"
  night-outline: "#8B9483"
typography:
  title-large:
    fontFamily: "sans-serif"
    fontSize: "22sp"
    lineHeight: "28sp"
    letterSpacing: "0em"
  body-large:
    fontFamily: "sans-serif"
    fontSize: "16sp"
    lineHeight: "24sp"
    letterSpacing: "0.03125em"
  body-medium:
    fontFamily: "sans-serif"
    fontSize: "14sp"
    lineHeight: "20sp"
    letterSpacing: "0.01785714em"
  label-medium:
    fontFamily: "sans-serif-medium"
    fontSize: "12sp"
    lineHeight: "16sp"
    letterSpacing: "0.04166667em"
rounded:
  search: "28dp"
spacing:
  inset-4: "4dp"
  inset-8: "8dp"
  inset-12: "12dp"
  inset-16: "16dp"
  inset-20: "20dp"
  inset-24: "24dp"
  inset-26: "26dp"
components:
  toolbar:
    height: "64dp"
  term-row:
    typography: "{typography.body-large}"
    padding: "18dp 10dp"
  icon-button:
    width: "48dp"
    height: "48dp"
  search-field:
    rounded: "{rounded.search}"
  editor-title:
    typography: "{typography.title-large}"
  feedback:
    typography: "{typography.label-medium}"
---
# Design System: Pixel Local Dictation

## Overview

**Creative North Star: "Native Material3 task UI"**

Native Material3 task UI: familiar controls, readable terms, quiet feedback. System typography and dynamic color make Settings feel at home on Android; the green palette is the static fallback.

Scope: the Settings/dictionary surface only. The recording panel (#30) is not covered here.

Source of truth: native XML and the resolved runtime theme (`app/src/main/res/values{,-night}/settings_*.xml`, `layout/{settings,dictionary,dictionary_editor,dictionary_row}.xml`, `DictionaryActivity.kt`, `SettingsActivity.kt`). Frontmatter keeps Android dp/sp, not CSS pixels. Verification: `DictionarySettingsTest` (dark, and app-local light at fontScale 1.3), `SettingsScreenTest` (host: equal rows, aligned inset, quiet delete), `HistoryLayoutTest` (Settings at 320dp, fontScale 2); search hint contrast 4.94:1 light / 6.56:1 dark (`c23d2f1`). Private captures: `corpus/lab/dictionary-settings-final-review/` and, for contrast, `corpus/lab/dictionary-settings-contrast-review/`. A successful UI Add is code-reviewed, not automated.

## Colors

Dynamic Material colors apply to both activities when available. The frontmatter records the light fallback and corresponding `night-` tokens. Resolve attributes such as `colorSurface` and `colorOnSurface`, rather than looking up these hex values in components.

### Primary

Muted green carries primary actions; pale green containers support primary content. Paired on-colors preserve text contrast in each mode.

### Neutral

Near-white or deep green-black surfaces carry content. On-surface is for terms; on-surface-variant is for explanation, metadata and quiet delete icons. Surface-variant and outline belong to native fields and theme chrome. Secondary, tertiary, error and disabled colors remain inherited Material roles; no custom palette is established for them.

**The System Color Rule.** Resolve semantic theme attributes at runtime; static green values are fallbacks, not a forced brand palette.

## Typography

**Body Font:** Android `sans-serif`.
**Label Font:** Android `sans-serif-medium`.

The four frontmatter roles resolve the Material3 appearances used by this surface: title-large for the sheet heading, body-large for terms, body-medium for explanation/status, label-medium for counts and inline feedback. Toolbar, inputs and buttons retain their widget appearances. No display role or custom font is introduced. The benchmark switch uses body-large like the navigation rows.

**The Native Type Rule.** Use Android text appearances and scalable sp text; preserve the system font.

## Layout

Single-column, full-width Android screens. Dictionary has a top app bar (minimum 64dp), explanation, search, count/languages, status, then a weighted scrolling list. The extended Add FAB sits bottom-end with 24dp margins. List bottom padding (104dp) leaves room for the FAB. Explanation/editor use 24dp horizontal insets; search uses 20dp, list 16dp and metadata 26dp. These are observed component-specific insets, not a universal grid.

Root padding accommodates system bars and display cutouts; dictionary also accommodates the keyboard. Settings adds its 24dp inset to system bars. Sheet is wrap-content and expanded on opening. No tablet breakpoint or desktop layout is established.

## Elevation & Depth

Native Material tonal surfaces, FAB, bottom sheet and Snackbar provide depth. No app-defined shadow, blur or motion durations exist. Preserve native ripple, focus, disabled and pressed states.

**The Native Depth Rule.** Let Material widgets own elevation, state layers and transitions; do not introduce a separate shadow system.

## Shapes

Rounded filled search field with explicit corner token. Other shapes come from their Material3 widgets: outlined editor field, filled commit button, icon buttons, extended FAB and modal sheet. No custom card or radius scale is established. Icons are 24dp vector resources; close/delete actions have 48dp bounds. Term labels have minimum 64dp height and native selectable backgrounds.

## Components

### Settings and navigation

Launcher and Android keyboard settings resolve to the same Settings activity. Under the toolbar, Dictionary and History are navigation rows of equal weight on the Dictionary row pattern: full-width selectable row, at least 64dp, title in body-large `colorOnSurface`, text on the 24dp inset (the ripple reaches 10dp past it). The History row adds only its saved count, trailing, in label-large `colorOnSurfaceVariant` (spoken as "History · N"); its warnings live on the History page. A divider separates navigation from the benchmark section: switch (body-large), explanation (body-medium, subdued), stored count, and "Delete benchmark data" as a quiet text button under the data it deletes, behind its confirmation dialog. Dictionary is a separate internal activity; the toolbar back action returns to Settings, with its icon tinted by `colorOnSurface`. No dictionary management controls belong in recording.

### Search and terms

Filled rounded search with a magnifying start icon and clear action. Hint sits inside the single-line input; floating hint is disabled and both normal/focused box strokes are zero. Search IME action disables personalized learning. Hint text explicitly resolves `colorOnSurfaceVariant`. Filter is trimmed and case-insensitive; displayed entries use German locale collation. Count describes the full dictionary, not just matches. Each row opens editing; its separate delete action has a term-specific accessibility description.

### Add action and editor

Extended Add FAB opens a native bottom sheet. Sheet contains a title, close action, one outlined Word or phrase field, helper text, quiet feedback and Add/Done button (minimum width 96dp). Field is single-line, maximum 200 characters, without suggestions, autofill or personalized learning. Keyboard opens with focus in the field.

Add saves one entry, clears the field and retains editor focus for repeated additions. Done saves an edit and closes the sheet. Keyboard Done invokes the same valid save action. Close dismisses the unsaved draft. Invalid, unchanged or busy entries disable commit; validation belongs to the field. Busy state disables mutation controls and reports preparation. Cold Settings saves validated text without starting ASR; if a runtime is already loaded, edits reuse its model while preparing the replacement dictionary configuration. Failure retains the previous dictionary and allows retry.

### Feedback and recovery

Counts/language labels and editor feedback use the subdued text role. Status and inline feedback use polite accessibility live regions. Empty, no-match, loading and failed-load states occupy the inline status area; failed load can be tapped to retry. Successful add stays inline. Edit/restore feedback uses anchored native Snackbar. Delete has no confirmation dialog: successful deletion shows an Undo Snackbar for 8000ms, anchored above the FAB.

### History

History is a sibling of Dictionary, not a variant. Source: `layout/{history,history_row,history_detail}.xml`, `menu/history.xml`, `HistoryActivity.kt`, `HistoryRows.kt`. Same toolbar (64dp, back icon tinted `colorOnSurface`), same 24dp explanation inset, same 16dp list inset, same selectable rows with a 48dp trailing icon action.

- **List.** One short explanation (stored locally, audio is not), then one status slot, then the list. Rows are at least 64dp: preview (two lines, `BodyLarge`, `colorOnSurface`) over one meta line (`LabelMedium`, `colorOnSurfaceVariant`): medium date, short time, no seconds, status after a " · " separator. A status appears only when the take is not complete and inserted: Recording, Finishing, May be incomplete, Not inserted, Insertion uncertain. The empty state is its own centered block (title and hint), not another line of the explanation. Returning from a detail keeps the list in place and never shows a loading state.
- **Detail.** Own title: the date. Metadata line, full transcript, then Copy as the one filled primary action; Delete is a quiet text button below it. No Clear History here (the menu item is hidden).
- **Delete model.** One transcript, from a row or the detail, no dialog: the row disappears at once and a Snackbar offers Undo for 8 s. Storage is touched when the window ends or the page is left, whichever is first; a second delete settles the first at once; process death inside the window leaves the transcript in place. A failed removal restores the row and posts the Delete warning. Clear History is the exception: it keeps a confirmation (title "Clear History?", saved and in-progress counts, clipboard copies and benchmark recordings not affected, button "Clear") and has no Undo.
- **Status slot.** One view under the explanation holds either the page's own state (loading, clearing, read or clear failure with Retry, missing transcript) as a plain line, or, when there is none, one pending warning as a filled row; the page state wins and the warning waits. Never two rows.
- **Warning row.** History warnings (save failure, incomplete recovery, delete failure, receipt failure) are one inline row (filled card, `colorSurfaceVariant` with `colorOnSurface` text and a `colorPrimary` Dismiss, polite live region; the theme sets no secondary colors, so `colorSecondaryContainer` would fall back to Material lavender) in the slot Dictionary uses for its state line, with a 48dp Dismiss action. One at a time; dismissing shows the next. Each message names only actions that exist on the page. They never appear on the recording panel, and Settings shows at most a saved-count on its History row.
- **Feedback.** Inline status or a Snackbar on the page; no Toasts.

### Recording panel: landscape

The recording panel's design is otherwise outside this document (#30); only its landscape form is recorded here. Source: `layout-land/ime.xml`, `RecordingRingView.kt`, `PanelPreview.kt`.

- **Same panel, side by side.** Same views, ids, colors and copy as portrait; no extra controls. Start side: Back (48dp) and status, the private-field line, then the three-line preview, both indented to the status text. End side: a 232×148dp stage with the 70dp microphone and ring, under the right thumb; in a window narrower than 600dp the stage is 148dp square so the status and preview keep room (`panel_stage_width_land`).
- **Compact.** The editor stays visible above: on the Pixel 10 Pro XL the panel is 156dp tall at default font and 196dp at 2× with the private-field line (of 443dp). The input method never uses fullscreen extract mode.
- **Ring.** The resting ring keeps its portrait size; loudness motion scales to the shorter stage so the loudest ring is never clipped.
- **Preview.** In both orientations the preview shows the newest line when the text needs more lines than it shows. It holds the last 240 characters from a whole word (no fragment or leading space where the cut falls mid-word).
- **Rotation.** Rotating rebuilds the panel for the new orientation from the current take; it never starts, stops or interrupts recording.
- **Finishing after a rotation.** The returned panel shows the same Finishing state: status "Finishing…", microphone disabled and dimmed, ring at its resting dimmed ink. It reads as finishing in a still frame (animations off); nothing moves to say so.
- **A Cancel choice through a rotation.** An open choice is closed while the panel is away and shown again on the returned panel once it has stayed visible (`ROTATION_SETTLE_MS`), with the safe action focused. Rotation never chooses. If the choice cannot be attached there, its safe action applies (Continue recording, Keep finishing). Lock, hide and focus change override it (History).
- Verification: `RecordingPanelTest` on the Pixel (landscape controls, 48dp targets, height, unclipped text and ring at fontScale 1 and 2); private captures in `corpus/lab/issue36/56-panel-*/` (light and dark, default and large font). The panel keeps its fixed light palette in dark mode (not yet themed); the Cancel choices follow the system theme. Host: `DictationProductionLifecycleTest` (returned finishing panel light/dark at fontScale 2, narrow 320dp landscape, prompts at 320dp light/dark). Device captures of the rotation states: pending (#53).

Dictionary semantics the UI must respect: [PRODUCT.md](PRODUCT.md).

## Do's and Don'ts

### Do:

- **Do** use semantic theme colors and DayNight resources.
- **Do** preserve at least 48dp action targets, system insets and keyboard accommodation.
- **Do** keep saving feedback close to the field and provide Undo after deletion.
- **Do** keep dictionary management in Settings and recording focused on recording.

### Don't:

- **Don't** hardcode the fallback green over dynamic color.
- **Don't** add a global Save step to the per-entry editor.
- **Don't** promise measured accuracy gains for arbitrary new terms.

Not canonized: legacy benchmark controls are a mixed platform/Material surface, not a reusable component system. Tablet/landscape and font extremes beyond 1.3 are unverified. The owner smoke test was accepted later ([ADR-027](adrs/027-editable-dictionary.md)).
