# CLAUDE.md — Apo-JVyduna package

Claude Code guidance for Jeff Vyduna's `apotheneum.jvyduna.*` Chromatik package.
This repo is personal, so these notes live directly in `CLAUDE.md` — no more
`CLAUDE.notes.md` + gitignored-stub indirection from the fork days.

## Package Overview

The `apotheneum.jvyduna` package contains patterns and audio work by Jeff Vyduna
for the Apotheneum LED installation.

Jeff's goal is to compose a 20 minute piece for Apotheneum that is tightly
choreographed to the music in `audio/songs/`. At the same time he uses the beta
`arrange` branch of LXStudio (and LX/GLX) to provide helpful feedback on new
features that allow artists to compose timeline-based compositions in Chromatik,
as an alternative to the Ableton-based workflows most artists currently use.

## Repo relationships

- **This repo** (`~/Code/jvyduna/Apo-JVyduna`) — the LX package: patterns, utils,
  design docs. Most development happens here. Builds
  `jvyduna-apotheneum-<version>.jar` into `~/Chromatik/Packages`.
- **`~/Code/Apotheneum`** (fork of Apotheneum/Apotheneum) — upstream code plus
  `.lxp` project files only (`src/main/resources/projects/jvyduna/`). PRs to
  upstream are `.lxp`-only. Its `pom.xml` stays pristine at upstream's
  `lx.version` (1.2.1); the arrange override happens at build invocation (below).
- **Host app from source** — `~/Code/mcslee/LXStudio` + `~/Code/heronarts/LX` +
  `~/Code/heronarts/GLX`, all on branch `arrange`, all `1.2.2-SNAPSHOT`.

## Layout

- `src/main/java/apotheneum/jvyduna/patterns/` — pattern classes
- `audio/` (repo root, gitignored) — audio assets used while developing/performing
  (e.g. `audio/songs/`), ~258 MB of copyrighted reference tracks. Excluded via the
  committed `.gitignore`; never `git add` audio.
- Design docs live next to the code they describe, as `<PatternName>.md` in the
  same directory as the `.java` file.

## Git workflow

Two Claude Code session modes touch this repo. **First figure out which mode
you are in** — check whether the checkout is Jeff's real working copy
(`/Users/jvyduna/Code/jvyduna/Apo-JVyduna`, `~/Chromatik` and `~/.m2` exist,
`mvn` resolves the provided-scope LX/apotheneum jars) or an ephemeral
per-session container (some sandbox path like `/home/user/...`, on a
`claude/<name>` branch, `mvn` cannot resolve `com.heronarts:lx`). Following
the wrong mode's rules has already lost work once (see remote mode below).

**Unconditional in both modes: pushing `main` to GitHub requires Jeff's
explicit approval. Never open PRs unless asked.**

### Branch conventions (adopted 2026-07-08)

- **`main`** — stable, deployable. What `mvn -Pinstall install` should
  normally build from. Receives only merges Jeff has approved.
- **`pattern-dev`** — integration branch for pattern/effect development.
  Session worktrees branch from and merge into `pattern-dev`, not `main`.
  Jeff periodically merges `pattern-dev` → `main` when a batch is stable
  and tested.
- **`composition-ui`** — long-lived branch for the Arrange Tools plugin:
  editor helpers (cross-lane range shift, event-range copy/paste) for the
  arrange-branch host. Scope: `src/main/java/apotheneum/jvyduna/arrange/**`
  only. Merged to `main` by Jeff once verified.
- **Hard contract for arrange/editor work** (`composition-ui`): package-side
  only — never edit the LXStudio/LX/GLX repos. Narrow **read-only** reflection
  into host UI internals is allowed, but every access must be try/catch
  guarded so a host refactor degrades to a logged no-op. Features are
  editor-only: they must never change how compositions play back or parse,
  and any `.lxp` they touch must be verified to load cleanly on stock
  Chromatik 1.2.2.

### Local Mac sessions (Jeff's machine)

- Sessions develop in worktrees under `.claude/worktrees/` on
  `worktree-bridge-*` branches; the repo root stays on `main`.
- **Minimal remote activity.** When a turn of improvements is done, merge it
  back into the **local** `pattern-dev` branch (or `composition-ui` for
  Arrange Tools work). Do not push worktree branches or create remote
  branches.
- **Rebase on the integration branch at the start of every turn.** Before
  making new edits, run `git rebase pattern-dev` (or `composition-ui`) in the
  worktree so the session builds on the latest merged state and current
  CLAUDE.md. Only rebase a clean tree — commit (or stash) first, never
  mid-edit. Worktree branches are single-session and local-only, so rebasing
  is always safe here.
- Keep each session scoped to its own pattern's files. Shared utils
  (`SurfaceCanvas`, `AudioReactive`, `TempoLock`) are the conflict hotspot:
  keep util edits small and additive, call them out in the merge commit body,
  and merge finished turns promptly so other sessions pick them up on their
  next rebase.
- When a session's work is approved: commit it on the worktree branch with a
  descriptive message summarizing the curation pass, then merge into the
  integration branch with a merge commit —
  `git -C ~/Code/jvyduna/Apo-JVyduna merge --no-ff <branch> -m "Merge <what> into pattern-dev"`
  (run from a checkout of the integration branch; the repo root stays on
  `main`, so use a worktree or `git worktree` checkout of `pattern-dev`) —
  noting in the body any file overlap with other recent sessions.
- After Jeff merges an approved batch to `main`, rebuild on main
  (`mvn -Pinstall clean install`) so the deployed jar in
  `~/Chromatik/Packages` matches main.

### Remote/cloud sessions (Claude Code web, server mode, worktree isolation)

These run in an isolated, throwaway container that dies with the session.
**The pushed session branch is the ONLY thing that survives.** Lesson learned
2026-07-06: a remote session "merged into main" inside its container and
committed CLAUDE.md edits to that main — none of it was pushed, the container
evaporated, and the work had to be reconstructed by hand on the Mac.

- Commit **all** work — code, design docs, CLAUDE.md edits, everything — on
  the session's own `claude/<name>` branch. Never commit to, or merge into,
  `main` inside the container: that main is an ephemeral copy, and unpushed
  commits on it are silently lost when the session ends.
- When a workstream is complete (implemented and verified as far as the
  sandbox allows), **push the session branch to origin automatically** —
  `git push -u origin <branch>`, no need to ask. This is required, not
  optional: it is the only channel back to Jeff's machine, where the branch
  gets merged into the real `main` to compile and review.
- If Jeff says "merge to main" during a remote session, that means: make sure
  the session branch is pushed and tell him it's ready to merge locally — do
  NOT perform the merge in the container.
- The container cannot build (`mvn` fails on the provided-scope
  `com.heronarts:lx` / `apotheneum:apotheneum` jars — no `~/.m2`). Verify via
  stubbed-API compile + headless harnesses; real build/verify happens on the
  Mac after the merge.

### Both modes

- Leave local `.idea/` drift in the repo root uncommitted unless asked.
- Never `git add` audio (see Layout).

## Commit signing (known remote-environment quirk)

Some remote Claude Code sessions get a container whose SSH commit-signing key
(`~/.ssh/commit_signing_key.pub`) is provisioned empty, even though
`~/.gitconfig` correctly sets `commit.gpgsign=true`, `gpg.format=ssh`, and
`gpg.ssh.program`. Commits then land unsigned, and a stop hook may flag them
as "Unverified" on GitHub. This is an Anthropic environment-provisioning gap
outside this repo's (and Jeff's) control — not a git config problem, not
something a commit --amend or rebase fixes, and not worth chasing:

- Do not attempt to amend/rebase to "fix" a signature, especially not on any
  commit already pushed to a remote branch — that requires force-pushing
  published history to correct a purely cosmetic GitHub badge.
- Do not stop to ask Jeff for permission about it. Mention it at most once,
  in passing, in your final summary — never loop on it.
- Signed vs. unsigned commits are functionally identical for this solo repo.
  Treat the hook's warning as informational noise, not a blocker.

## Reference skills

- **`te-patterns`** (personal skill) — idioms for writing native-Java LX/Chromatik
  patterns, distilled from the TitanicsEnd team's example code. Useful cross-repo
  reference when authoring Apotheneum patterns (same LX framework; different
  geometry). It should surface automatically for pattern work; invoke it
  explicitly if it doesn't.

## Preferences

- **Avoid custom UI; prefer the default auto-generated control panel.** Patterns
  should rely on Chromatik's automatic UI — just register parameters with
  `addParameter(...)` — and should **not** implement `UIDeviceControls` /
  `buildDeviceControls` unless absolutely necessary. If custom UI is ever truly
  required, keep **max 3 controls per column**.
- Use `LX.log("...")` for debugging output, not `System.out.println` (println
  won't appear in `~/Chromatik/Logs`).
- Build/deploy with `mvn -Pinstall install` (not just `mvn compile` — patterns
  load from the deployed jar).
- No `new` allocations in render loops; reuse collections.
- **Every pattern exposes a `Smooth` knob** (`CompoundParameter("Smooth", 1.0)`,
  key `smooth`): motion blending between moving pixels + antialiasing of forms
  (0 = steppy/pixel-snapped/hard, 1 = smooth). Standardized name is **Smooth**;
  "Blend" now means compositing mode only. A static bench may omit it but must
  document the exemption in its `.md`. Register after pattern params, before
  `audio`. Full spec in `docs/TEMPLATE.md`.
- **`energy` → `speed`**: name the motion-rate knob `speed`/"Speed" when it only
  scales motion; keep `energy`/"Energy" only when it also drives non-motion
  (brightness/density/regime). Rename key + label + field together.
- **Grid-less songs (Distance, Chrome, Temper) use continuous, beat-relative
  speeds** ("tempoDiv units"): units-per-beat × live `lx.engine.tempo.period`,
  clamped to the >=5 s cap — continuous, tempo-following, never a Sync/TempoDiv
  grid snap. **Chrome is an outlier** (part grid-driven): decide each Chrome
  speed param with Jeff. See `docs/TEMPLATE.md` Tempo section.

## Debugging against the `arrange` LXStudio build

The host app is launched **from source in IntelliJ** and this project loads into
it as a **package jar**. Package vs. host: this project is a plugin;
LXStudio/GLX/LX are the host.

Setup (one-time, in IntelliJ):
- One IntelliJ window rooted at **this repo**, with LXStudio, LX, GLX, and
  Apotheneum attached as Maven projects (`Add Maven Project` on each `pom.xml`)
  so breakpoints bind across the whole stack from source.
- The committed shared run config **"Chromatik (arrange)"**
  (`.idea/runConfigurations/Chromatik.xml`) does the full cycle:
  1. builds the Apotheneum fork with `install -Pinstall
     -Dlx.version=1.2.2-SNAPSHOT` (the `-D` override targets the arrange host
     while the fork's committed pom stays at upstream's 1.2.1 — the old
     committed-lx.version-bump policy is RETIRED),
  2. builds this package with `install -Pinstall`,
  3. launches `heronarts.lx.studio.Chromatik` from source (module `glxstudio`,
     Temurin 21, `-XstartOnFirstThread`, `--warnings`, working dir
     `~/Code/mcslee/LXStudio`).

Iteration loop (the important part):
- **Patterns register from the deployed jar, not IntelliJ's compiled module
  classes.** After editing, rerun the "Chromatik (arrange)" config (or
  `mvn -Pinstall install` + restart). Editing source alone will NOT update the
  running app.
- **Keep only one `jvyduna-apotheneum-*.jar` in `~/Chromatik/Packages`.** LX
  loads packages alphabetically and the first-loaded class wins; a leftover
  older-versioned jar shadows the current build (log: "Ignoring duplicate
  class"). Delete stale jars. Same rule applies to the upstream
  `apotheneum-*.jar`.
- **Quit any standalone Chromatik.app before launching from IntelliJ** — a
  second instance grabs the OSC port ("[OSC] ... Address already in use") and
  reads the same Packages dir.
- Keep this pom's `<lx.version>` matching the arrange host (currently
  `1.2.2-SNAPSHOT`). `lx.package`'s `lxVersion` is filtered from it; a stale
  value triggers a "built for an older version" warning at load.

## LX serialization gotchas (hard-won, verified on `arrange`)

Learned building the MIR-verify template generator in the Claude-MIR-pipeline
repo; the LX source is easy to misread on these.

- **LinearMaskEffect polarity**: a NON-inverted mask **shows** the band and
  blacks out everything else (empirically verified — reading
  `LXColor.multiply` in the FADE mask function suggests the opposite; the
  alpha there is a *darkening* amount). Band geometry: `ABS` mode is
  **centered** on `offset` with ±`size` half-width (total width 2·size);
  `POS` shows position ≤ offset+size (bottom-anchored bars, since normalized
  yn=0 is the model bottom); `NEG` shows position ≥ offset−size. `offset` and
  `size` are CompoundParameters — valid clip-lane and modulation targets.
- **Composition lane visibility**: the arrange timeline groups a parameter
  clip lane under `p.getAncestor(LXBus.class)` of the target parameter
  (`LXComposition.getParameterLaneBus`). A lane targeting a **global**
  modulator's parameter has no bus ancestor and silently appears under no
  channel row. Scope modulators inside the pattern (or channel) they serve.
- **Scoped modulation JSON** (pattern-level `children.modulation`): paths are
  RELATIVE to the host component — source
  `{"id": <modId>, "path": "/modulation/modulator/1"}`, target
  `{"componentId": <patternId>, "parameterPath": "color/brightness", "path":
  "/color/brightness"}` (`LXParameterModulation.save` uses
  `getCanonicalPath(scope.getParent())`). A clip lane targeting that
  modulator uses the full root path:
  `/lx/mixer/channel/N/pattern/M/modulation/modulator/K/<param>`.
- **Scoped modulations cannot target ancestor parameters**: a modulator
  inside a pattern's modulation engine can only drive parameters on that
  pattern (or below). Targeting the channel-owned `compositeLevel` of the
  pattern silently does nothing — modulate a pattern-local stand-in instead
  (e.g. a SolidPattern's `color/brightness` resting at 0%).
- **Clip-lane events**: all `ParameterClipLane$*` subtypes (Normalized,
  Discrete, Boolean, Trigger) share the event shape
  `{"cursor": {millis, beatCount, beatBasis}, "normalized": <0-1>}` — key is
  `normalized`, trigger events use 1.0. The loader picks the lane subtype
  from the resolved parameter's runtime type, not the `class` string.
- **Cube-face views**: model views `left/right/front/back` (channel `view`
  7/8/9/10, 1-based, 0 = whole model) select both LED layers of one cube
  wall with RELATIVE normalization. Horizontal axis within a face is world
  **X on Front/Back** but **Z on Left/Right** — masks and patterns need a
  per-face axis choice (compare `model.xRange` vs `zRange` at runtime).

## MIR-verify support (Text pattern, font, 3D-label plugin)

Built for the Claude-MIR-pipeline light organ (`mir compose`), but general:

- `patterns/Text.java` — view-based LED text in a 5px bitmap font
  (`util/PixelFont5.java`, uppercase A-Z/0-9/`# : - . /`, authored as ASCII art;
  run its `main` to preview). Params: `text` (StringParameter), scale, rotation,
  x/y offset, line/char spacing, hue/sat/bright, flip. Layout is in **world
  units** so rotation stays undistorted on any face aspect; rendering maps each
  LED through the inverse text transform (exact at 0/90/180/270°). Free-text
  entry uses `UITextBox.setParameter(text)` in `buildDeviceControls`
  (`implements UIDeviceControls<Text>`); Chromatik has **no multi-line text
  widget**, so the pattern parses literal `\n` (and real newlines set via JSON).
  `StringParameter` serializes into the .lxp for free.
- `MirVerifyLabelsPlugin.java` — `@LXPlugin.Name`/`LXStudio.Plugin` that draws 3D
  preview labels via GLX's built-in `heronarts.glx.ui.text3d.UI3dText`
  (`addLabel(text).setPosition(...).setOrientation(CAMERA).setTextScale(WORLD)...`).
  A **pattern cannot draw in 3D** — only a plugin gets `onUIReady` +
  `ui.preview.addComponent(...)` (add to `ui.preview` AND `ui.previewAux`).
  `UI3dText(glx)` takes the GLX (LXStudio is one). `Apotheneum.initialize(lx)` in
  the plugin's `initialize` populates `Apotheneum.cube` before use. The built-in
  `Label` has no per-label rotation setter, so outward-facing = billboard
  (`TextOrientation.CAMERA`); TE's `UIModelLabels` is the reference if you ever
  need true world-oriented, per-label rotation (custom font-atlas shaders).
