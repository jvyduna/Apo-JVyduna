# Open questions & autonomous-decision log

> Jeff was asleep during this run and asked me to proceed autonomously (depth
> over guided quality). This file records the non-obvious choices I made so you
> can override any of them, plus genuine questions I couldn't resolve. Nothing
> here blocked the work — I picked a default and proceeded.

## Decisions I made (override freely)

1. **Worked in the main tree, and did NOT commit anything.** Your tree had a
   mid-flight reorg staged in the index (22 renames moving `patterns/*` into
   `patterns/bliss/`, the new `effects/bliss/`, `AudioScope` deletion). A
   `git worktree` would have branched off HEAD and not seen those uncommitted
   moves, so pattern agents couldn't read the `bliss/` exemplars → I worked
   additively in the main tree. I then **left every new file untracked and
   committed nothing**, because your reorg is already staged: any `git commit`
   would have swept your in-progress reorg into my commit. All my deliverables
   are additive untracked files — review them and commit when you're ready. To
   stage just the composition work (leaving your reorg as its own commit):
   `git add docs/composition src/main/java/apotheneum/jvyduna/patterns/{temper,chrome,rafters,opus,distance} src/main/java/apotheneum/jvyduna/util/SymbolGlyphs.*`
   (note this shares the index with your staged reorg, so separate the two commits
   deliberately). Nothing was pushed; `main` is untouched on the remote.

2. **All deliverables are additive files** under `docs/composition/` and new
   per-song folders `patterns/{temper,chrome,rafters,opus,distance}/` and
   `effects/{...}/`. No existing file was modified.

3. **Spreadsheet pulled via public CSV/XLSX export**, since the Google Drive MCP
   needs interactive auth this session couldn't do. If the sheet wasn't meant to
   be world-readable, note that — the export worked without credentials.

4. **Hero pattern picks** (for the "prioritize Chrome + Temper" depth call) were
   chosen from your sheet ideas + the MIR structure; see each `<slug>-brief.md`
   "Pattern & effect plan" and `patterns/<slug>/*.md`. Change any you dislike.

5. **Permission emails are DRAFTS only** — nothing was sent to anyone. The bio
   uses your public record with `[PLACEHOLDER]` slots for the non-public ILDA
   award. See `permissions/`.

6. **First-pass Java is a compiling starting point, not curated.** No hardware /
   live-LED verification was possible; every unverifiable constant is marked
   `CURATE:` in the design docs per your convention.

## Questions for you (answer on return; I proceeded with the noted default)

- **ILDA award name + year** — left as `[PLACEHOLDER]` in `permissions/artist-bio.md`.
  Default intro reads fine without it; fill it in to strengthen the emails.
- **Bliss permissions** — I still researched contacts + drafted a permission
  email for Bliss (you need permission even though its patterns are built).
  Default: treat it like the others. Skip if you already have Ducky's OK.
- **Opus "song"** — it's the famous "Opus No. 1" hold music; the rights/contact
  path there is unusual (Tim Carleton / Cisco). See `permissions/opus-contact.md`
  for the recommended (and lighthearted) approach — confirm you want to bother.
- **Hero vs. candidate scope** — Rafters/Opus/Distance got one hero design doc +
  a Java stub each (lighter), per your "prioritize Chrome + Temper" answer. Say
  the word and I'll promote any of them to full first-pass Java.

## Known caveats

- Rights-holder confidence varies by artist (indies like Helios/Ducky/Kalia are
  reachable directly; Warp/Ghostly are label-gated). Confidence is rated per
  contact file — treat Low-confidence emails as best-guesses.
- Web research reflects sources as of July 2026; citations are inline.
