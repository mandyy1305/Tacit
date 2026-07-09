# Tacit Voice-Note Overlay — UX Revamp

**Surface:** In-chat voice-note overlay card (shown over a WhatsApp "Notes" thread)
**Outcome:** `tacit-overlay-prototype-v2.html` — **final**
**Date:** 9 Jul 2026

---

## 1. Summary

The overlay was redesigned from a dense, multi-section card into a lean, **transcript-first** surface. Roughly half of the original UI was removed. What remained was re-prioritised around the one thing users open the card for — the transcript — while progress, view-switching, and the screen-share nudge were compressed into minimal, glanceable controls.

---

## 2. The problem (before)

The original card stacked, top to bottom:

1. `TACIT` eyebrow label + **Voice note** title
2. A full-width **"Using the mic for this one / Share screen"** notice block (three lines of copy + a big button)
3. A **✓ MATCHED · Part 2 of 2 · 5 Jul · 0:27** row
4. A **Transcribe all 2** button
5. Large **Transcript / Summary** tabs
6. The transcript text
7. A footer with **CLOUD**, **Wrong note?**, and a **COPY** button

The transcript — the actual payload — sat buried in the middle of a tall, busy card. Most of the surrounding elements were chrome, low-frequency actions, or restating information.

---

## 3. Approach / guiding principles

- **Promote the payload.** The transcript is why the card exists; make it the visual hero.
- **Remove before relocating.** If an element is chrome, redundant, or rarely used, cut it rather than reposition it.
- **Content as the affordance.** Prefer acting on the content directly over adding buttons around it.
- **Familiar metaphors.** Use patterns people already understand (e.g. Instagram-stories progress) instead of bespoke UI.
- **Nudge, don't block.** Helpful-but-optional guidance should be quiet and dismissible, never in the reading path.
- **Native, durable styling.** Flat brown surface, a single orange accent, line-art icons, normal-case type — deliberately avoiding trendy styling.

---

## 4. Step-by-step change log

Each step is one iteration, in the order it happened, with the reasoning.

**1. Built the editable prototype (v1).**
Recreated the screenshot as a self-contained, editable HTML card with a control panel (drag to move, edit text inline, sliders for position/size). Purpose: a fast surface to iterate layout on.

**2. Cut the header and the "matched/transcribe" block; declared the transcript the hero (v2).**
Removed the `TACIT` eyebrow + **Voice note** title, the **✓ MATCHED · Part 2 of 2** row, and the **Transcribe all 2** button. Added a progress indicator for the current and upcoming voice-note parts.
*Why:* The title restated context the user already had; the matched row and transcribe button were low-value. Clearing them let the transcript move up.

**3. Replaced the COPY button with tap-to-copy on the transcript.**
Clicking the transcript text now copies it (with a brief highlight).
*Why:* The content itself becomes the affordance — the largest, most obvious target performs the most common action, and a button is removed.

**4. Simplified the progress indicator to a dash + dots.**
Current part shown as a filled dash; each upcoming part as a dot.
*Why:* A lighter, more glanceable read of "where am I / how much is left."

**5. Removed the "Tap text to copy" hint line.**
*Why:* Once the hover/flash affordance was in place, the caption was clutter.

**6. Removed the "Using the mic / Share screen" notice block.**
*Why:* A full-width, three-line notice for an optional feature dominated the card and pushed the transcript down.

**7. Rebuilt the footer as minimal actions.**
Replaced the `CLOUD` / `Wrong note?` footer with: **Share screen** (icon + label) at bottom-left, and **Report** + **Settings** icon buttons at bottom-right.
*Why:* Turned a text-heavy footer into compact, recognisable controls; dim by default, accent on hover.

**8. Removed the meta line and moved progress to the top.**
Cut **MATCHED · 5 Jul · 0:27** and lifted the progress row to the very top, paired with the close (✕).
*Why:* The date/status line added little; putting progress at the top mirrors how stories/players show position first.

**9. Reworked progress into an Instagram-stories bar.**
One segment per part: completed parts filled, the current part a line filling by batch progress, upcoming parts empty tracks.
*Why:* A universally understood metaphor for sequential progress across items — no legend needed.

**10. Removed the "2 / 4" count.**
*Why:* The segmented bar already communicates count and position; the number was redundant.

**11. Added a blink + one-line tooltip to Share screen.**
The Share screen button gently blinks to draw attention, with a tooltip below it condensing the old notice to one line: *"Using mic — share screen for surer matches."*
*Why:* Preserves the useful nudge from the deleted notice block, at a fraction of the footprint, without blocking the transcript.

**12–13. Explored, then generated, slimmer view-switch options.**
The **Transcript / Summary** tabs were too large for a two-option switch. Produced a gallery of six redesigns (underline, soft segmented, slim pill, divider text, right-aligned mini, ghost outline) and a page rendering each as a full card, to compare in context.
*Why:* De-risk the change by seeing the options at true scale before committing.

**14–15. Replaced tabs with a two-state swap button.**
Removed the tab bar entirely. The footer **swap** button now switches the view and carries a state letter — **T** (Transcript) / **S** (Summary) — toggling the content on click.
*Why:* A 2-option switch didn't justify a full segmented control; swap + letter is a fraction of the height and keeps the control in the footer with the other actions.

**16–18. Consolidated the suggestion on/off control into settings.**
Added an on/off switch for the screen-share suggestion, then moved it out of the tooltip and into the **Layout controls** so it lives in one place (settings), not inline on the card.
*Why:* Keeps the card itself clean; configuration belongs in settings, not floating on the content.

**19. Made "off" actually hide the suggestion.**
Turning the suggestion off now removes the tooltip entirely and stops the blink (previously it only dimmed).
*Why:* "Off" should mean gone, not faded.

**20. Locked v2 as final.**

---

## 5. Before → after at a glance

**Removed**
- `TACIT` eyebrow + **Voice note** title
- **✓ MATCHED · Part 2 of 2** row and **Transcribe all 2** button
- Full-width **mic / Share screen** notice block
- **MATCHED · 5 Jul · 0:27** meta line
- **CLOUD** label, **Wrong note?** link, **COPY** button
- **Transcript / Summary** tab bar
- **2 / 4** count and the **Tap text to copy** hint

**Promoted**
- Transcript is now the hero — largest type, directly under the progress bar
- Progress moved to the top row, paired with close

**Re-modelled**
- Copy → tap the transcript text
- Progress → Instagram-stories segmented bar
- View switch → footer swap button with a T/S state letter
- Screen-share notice → a blinking button + one-line dismissible tooltip

**Added**
- Minimal footer: Share screen (left); swap, report, settings (right)
- Suggestion on/off control in settings (off fully hides it)

---

## 6. Final anatomy (v2)

Top to bottom:

1. **Progress row** — a stories-style segmented bar (one segment per part: done = filled, current = filling by batch progress, upcoming = empty), with the close (✕) at the far right.
2. **Transcript** — the hero. Large type; tap to copy; swaps to a short Summary via the swap button.
3. **Footer** —
   - *Bottom-left:* **Share screen** (icon + label). Blinks when the suggestion is on, with a one-line tooltip beneath it.
   - *Bottom-right:* **swap** (shows **T**/**S**), **report**, **settings** — icon-only, dim, accent on hover.

*(The prototype also carries a Layout-controls panel for tuning position, size, radius, padding, the stories progress, colours, and the suggestion on/off — a prototyping aid, not part of the shipped card.)*

---

## 7. Interaction model

- **Read/copy:** Tap the transcript → copies the text, brief highlight confirms.
- **Switch view:** Tap swap → toggles Transcript (**T**) ↔ Summary (**S**); the letter reflects the current view.
- **Progress:** Segments fill left-to-right; the current segment fills by batch progress, communicating position across all parts.
- **Screen-share nudge:** Button blinks + tooltip appears when the suggestion is on; turning it off in settings removes the tooltip and stops the blink.

---

## 8. Key decisions & rationale

- **Transcript-first over feature-parity** — users open the overlay to read or copy the note; everything else is secondary and was demoted or cut.
- **Remove over relocate** — every deleted element was chrome, redundant, or low-frequency; fewer elements means a faster scan.
- **Content as affordance** — tap-to-copy removes a button and a hint line and puts the common action on the biggest target.
- **Stories metaphor** — instantly conveys "how far along / how much remains" across parts without extra labels.
- **Swap over tabs** — a two-option switch didn't warrant a full segmented control; swap + letter is far shorter and lives with the other footer actions.
- **Nudge, dismissible, in settings** — the screen-share tip is useful but optional, so it's a quiet blink + one-liner, toggled from settings, and fully hideable.
- **Restrained visual language** — flat surface, single accent, line-art icons, normal-case type; avoids generic flashy styling for a native, durable feel.

---

## 9. Prototype files

- `tacit-overlay-prototype-v2.html` — **final** overlay + control panel
- `tacit-overlay-prototype.html` — v1 (original full prototype)
- `tacit-tabs-variants.html` — toggle-only comparison (exploration)
- `tacit-overlay-v2-all-toggles.html` — every toggle option as a full card (exploration)

---

## 10. Open follow-ups (optional, not done)

- **Auto-play progress** — current segment fills over the note's duration, then advances; tap a segment to jump (full stories behaviour).
- **Clean build** — a version with the control panel stripped, for sharing/screens.
- **Handoff** — export the tuned values into the real component.
