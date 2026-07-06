# Apo-JVyduna

Jeff Vyduna's patterns and utilities for the [Apotheneum](https://github.com/Apotheneum/Apotheneum)
LED installation, packaged as a standalone [Chromatik](https://chromatik.co/) LX package.
Composed against the `arrange` / Compositions branch of LXStudio (timeline-based composition beta).

Java packages remain `apotheneum.jvyduna.*`, so existing `.lxp` project files load unmodified.
This package depends on the upstream Apotheneum package at compile time and runs alongside it in
Chromatik (LX loads all jars in `~/Chromatik/Packages` into one classloader).

## Build

```
mvn -Pinstall install
```

Installs `jvyduna-apotheneum-<version>.jar` to `~/Chromatik/Packages`. The distinct jar name
(`jvyduna-apotheneum-*`, not `apotheneum-*`) avoids colliding with the upstream Apotheneum jar
in the same folder.

## Prerequisites

- Java 21+
- `com.heronarts:lx`, `glx`, `glxstudio` `1.2.2-SNAPSHOT` in `~/.m2` — built from the `arrange`
  branches of [heronarts/LX](https://github.com/heronarts/LX), [heronarts/GLX](https://github.com/heronarts/GLX),
  and [mcslee/LXStudio](https://github.com/mcslee/LXStudio) (`mvn install` in each)
- `apotheneum:apotheneum:2.0.0-SNAPSHOT` in `~/.m2` — run `mvn install` in an Apotheneum checkout

## For Mark / viewing Jeff's compositions

1. Get a Chromatik build with the 1.2.2 Compositions (`arrange`) features.
2. Install this package jar into that build's `~/Chromatik/Packages` — either build from source
   here with `mvn -Pinstall install`, or drop a provided `jvyduna-apotheneum-*.jar` in.
3. Make sure the Apotheneum package jar is also installed (this package's classes extend its
   base classes at runtime).
4. Open the `.lxp` from Jeff's fork/PR — project files live in the Apotheneum repo under
   `src/main/resources/projects/jvyduna/`.

Since java packages are unchanged (`apotheneum.jvyduna.*`), the `.lxp` files reference the same
class names as before and load without modification.
