# CLAUDE.notes.md — jvyduna package (personal Claude memory)

Personal Claude Code guidance for Jeff Vyduna's `apotheneum.jvyduna.*` work.

**Why this file is not named `CLAUDE.md`:** a file literally named `CLAUDE.md`
auto-loads as project instructions for *anyone* running Claude Code in this subtree
— including people who pull Jeff's fork or if this merges upstream. To avoid
force-feeding personal preferences to other contributors, the real notes live here
under a non-auto-loading name, and a **gitignored** sibling `CLAUDE.md` (present
only on Jeff's machine, see `.git/info/exclude`) imports this file via
`@CLAUDE.notes.md`. Net effect: these notes auto-load for Jeff, but never for
anyone else. (Same intent as `doved/1.md`, plus a stub so it still auto-loads for
the owner.)

When Claude records new jvyduna guidance, edit **this** file, not the root
`CLAUDE.md`.

## Package Overview

The `apotheneum.jvyduna` package contains patterns and audio work by Jeff Vyduna
for the Apotheneum LED installation.

Jeff's goal is to compose a 20 minute piece for Apothneum that is tightly choreographed to the music in audio.songs/. At the same time he will use the beta 'arrange' branch of LXStudio (and LX/GLX) to provide helpful feedback on new features that allow artists to compose timeline-based compositions in Chromatik, as an alternative to the Ableton-based workflows most artists currently use to do this.  

## Layout

- `patterns/` — pattern classes
- `audio/` — audio assets used while developing/performing (e.g. `audio/songs/`)
- Design docs live next to the code they describe, as `<PatternName>.md` in the
  same directory as the `.java` file (mirrors the `doved/` convention).

## Reference skills

- **`te-patterns`** (personal skill) — idioms for writing native-Java LX/Chromatik
  patterns, distilled from the TitanicsEnd team's example code. Useful cross-repo
  reference when authoring Apotheneum patterns (same LX framework; different
  geometry). It surfaces automatically for pattern work; invoke it explicitly if it
  doesn't.

## Preferences

- **Avoid custom UI; prefer the default auto-generated control panel.** My patterns
  should rely on Chromatik's automatic UI — just register parameters with
  `addParameter(...)` — and should **not** implement `UIDeviceControls` /
  `buildDeviceControls` unless absolutely necessary. Custom device UI depends on the
  private GLXStudio repo, which isn't reliably available here. This matches the
  `thesilveresa/*` convention (e.g. `GoldenRise`); `mcslee/*` and `doved/*` do use
  custom UI. If custom UI is ever truly required, follow the Dan Oved / Theresa /
  Justin Belcher conventions, and keep **max 3 controls per column**.

  _(Self-imposed for my own package; not a repo-wide rule.)_

- **Keep Claude notes in this file, not the repo root.** Any guidance, workflow
  notes, or preferences Claude records go in *this* `apotheneum/jvyduna/CLAUDE.notes.md`
  (my subtree), almost never the repo-root `CLAUDE.md` or `apotheneum/CLAUDE.md` —
  those are auto-injected for every contributor, and I can't assume others use
  IntelliJ or Claude the way I do. Only edit the shared/root files when explicitly
  told to.

- **The `pom.xml` `lx.version` → `1.2.2-SNAPSHOT` bump stays UNSTAGED.** It is a
  local working-tree change needed to build against the local `arrange`
  LX/GLX/glxstudio artifacts. It must **not** enter any commit — not even on my fork
  — because other contributors won't have `1.2.2-SNAPSHOT` in their `~/.m2` and it
  would break their build. Carry it as a persistent unstaged modification; never
  `git add pom.xml` for this change. (Revisit if/when arrange is released and the
  version is one everyone has.)

## Conventions inherited from the repo

- Use `LX.log("...")` for debugging output, not `System.out.println` (won't
  appear in `~/Chromatik/Logs`).
- Build/deploy with `mvn -Pinstall install` (not just `mvn compile`).
- No `new` allocations in render loops; reuse collections. Max 3 controls per UI
  column.

## Debugging against the `arrange` LXStudio build (tighter loop)

To run/debug my patterns against the beta `arrange` branch of Chromatik (rather than
a released Chromatik.app), the host app is launched **from source in IntelliJ** and
this project is loaded into it as a **package jar**. Package vs. host: this project
is a plugin; LXStudio/GLX/LX are the host.

Local layout (all on branch `arrange`, all `1.2.2-SNAPSHOT`):
- `~/Code/heronarts/LX`, `~/Code/heronarts/GLX`, `~/Code/mcslee/LXStudio` (`glxstudio`)
- Their `1.2.2-SNAPSHOT` artifacts are installed in `~/.m2`.

Setup (one-time, in IntelliJ):
- Open `~/Code/mcslee/LXStudio/pom.xml` as the project; add LX, GLX, and this
  Apotheneum project as modules (`Module from Existing Sources`) so breakpoints bind
  across the whole stack from source.
- Run config: Application, main class `heronarts.lx.studio.Chromatik`, `-cp` module
  `glxstudio`, SDK Temurin 21, VM options `-XstartOnFirstThread` (mandatory on
  macOS), program args `--warnings`. VM options are hidden until `Modify options ▸
  Add VM options`.
- Keep `pom.xml` `<lx.version>` matching the arrange host (currently
  `1.2.2-SNAPSHOT`) so the package compiles against the same API it runs in. The
  `lx.package` `lxVersion` is filtered from this property; a stale value triggers a
  "built for an older version" warning at load.

Iteration loop (the important part):
- **Patterns register from the deployed jar, not from IntelliJ's compiled module
  classes.** So each cycle: edit → `mvn -Pinstall install` → restart the Chromatik
  debug session. Editing source alone will NOT update the running app.
- **Keep only one Apotheneum jar in `~/Chromatik/Packages`.** LX loads packages
  alphabetically and the first-loaded class wins; a leftover older-versioned
  `apotheneum-*.jar` will shadow the current build (log: "Ignoring duplicate
  class"). Delete stale jars.
- Quit any standalone Chromatik.app before launching from IntelliJ — a second
  instance grabs the OSC port ("[OSC] ... Address already in use") and also reads
  the same Packages dir.
