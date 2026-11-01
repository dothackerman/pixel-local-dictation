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

Source of truth: native XML and the resolved runtime theme (`app/src/main/res/values{,-night}/settings_*.xml`, `layout/{settings,dictionary,dictionary_editor,dictionary_row}.xml`, `DictionaryActivity.kt`, `SettingsActivity.kt`). Frontmatter keeps Android dp/sp, not CSS pixels. Verification: `DictionarySettingsTest` (dark, and app-local light at fontScale 1.3); search hint contrast 4.94:1 light / 6.56:1 dark (`c23d2f1`). Private captures: `corpus/lab/dictionary-settings-final-review/` and, for contrast, `corpus/lab/dictionary-settings-contrast-review/`. A successful UI Add is code-reviewed, not automated.

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

The four frontmatter roles resolve the Material3 appearances used by this surface: title-large for the sheet heading, body-large for terms, body-medium for explanation/status, label-medium for counts and inline feedback. Toolbar, inputs and buttons retain their widget appearances. No display role or custom font is introduced. The existing benchmark switch explicitly uses 18sp; it is a legacy control, not a new type scale.

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

Launcher and Android keyboard settings resolve to the same Settings activity. Settings retains the benchmark switch and deletion controls alongside its dictionary button. Dictionary is a separate internal activity; the toolbar back action returns to Settings, with its icon tinted by `colorOnSurface`. No dictionary management controls belong in recording.

### Search and terms

Filled rounded search with a magnifying start icon and clear action. Hint sits inside the single-line input; floating hint is disabled and both normal/focused box strokes are zero. Search IME action disables personalized learning. Hint text explicitly resolves `colorOnSurfaceVariant`. Filter is trimmed and case-insensitive; displayed entries use German locale collation. Count describes the full dictionary, not just matches. Each row opens editing; its separate delete action has a term-specific accessibility description.

### Add action and editor

Extended Add FAB opens a native bottom sheet. Sheet contains a title, close action, one outlined Word or phrase field, helper text, quiet feedback and Add/Done button (minimum width 96dp). Field is single-line, maximum 200 characters, without suggestions, autofill or personalized learning. Keyboard opens with focus in the field.

Add saves one entry, clears the field and retains editor focus for repeated additions. Done saves an edit and closes the sheet. Keyboard Done invokes the same valid save action. Close dismisses the unsaved draft. Invalid, unchanged or busy entries disable commit; validation belongs to the field. Busy state disables mutation controls and reports preparation. Cold Settings saves validated text without starting ASR; if a runtime is already loaded, edits reuse its model while preparing the replacement dictionary configuration. Failure retains the previous dictionary and allows retry.

### Feedback and recovery

Counts/language labels and editor feedback use the subdued text role. Status and inline feedback use polite accessibility live regions. Empty, no-match, loading and failed-load states occupy the inline status area; failed load can be tapped to retry. Successful add stays inline. Edit/restore feedback uses anchored native Snackbar. Delete has no confirmation dialog: successful deletion shows an Undo Snackbar for 8000ms, anchored above the FAB.

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
