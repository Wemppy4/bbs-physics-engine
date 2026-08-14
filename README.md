# BBS Physics Engine

A physics engine addon for [BBS](https://github.com/Wemppy4/bbs-fs).

## Building

BBS is not published to any public repository, so it has to be put into the local Maven
repository first:

```
cd ../bbs-fs
gradlew publishToMavenLocal
```

That installs `mchorse:bbs:2.4-1.20.4` into `~/.m2`, which is what `bbs_version` in
`gradle.properties` points at. Repeat it whenever BBS changes in a way this addon depends on.

BBS keeps the same version string while it is being developed, and Loom caches remapped mod
dependencies by coordinates — so a republished BBS would normally go unnoticed. This project's
`build.gradle` drops the stale remap by itself, so a plain `gradlew build` is enough after
republishing; `--refresh-dependencies` is not needed.

Then, in this project:

```
gradlew build      # produces build/libs/bbs_physics-0.1-1.20.4.jar
gradlew runClient  # starts the game with both BBS and this addon loaded
```

Note that this dev client runs BBS **without Sodium and Iris** — they are compile-time
dependencies of BBS, not of this project, and pulling them in transitively was turned off. BBS's
mixins that target them log a warning and are skipped. To test anything that touches rendering
under shaders, add them here as `modLocalRuntime` first.

## How it plugs into BBS

BBS loads addons through two entry points: it instantiates the listed classes and scans them for
methods annotated with `@Subscribe`, which then receive BBS's registration events.

* `bbs-addon` → `BBSPhysicsAddon` — read on both sides, at the top of BBS's own initialization.
  It registers the addon's asset source pack.
* `bbs-client-addon` → `BBSPhysicsClientAddon` — read on the client only, before any client-side
  event is posted. It lives in the client source set, so BBS's client-only event classes are
  never loaded on a dedicated server. It registers the language files and the settings module.

Beyond the events, BBS's factories are reachable statically — `BBSMod.getForms()`,
`BBSMod.getFactoryCameraClips()`, `BBSMod.getFactoryActionClips()` — so new form types and clip
types can be registered from the addon's own initializer, which Fabric runs after BBS's.
