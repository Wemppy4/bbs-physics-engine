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

Then, in this project:

```
gradlew build      # produces build/libs/bbs_physics-0.1-1.20.4.jar
gradlew runClient  # starts the game with both BBS and this addon loaded
```

## How it plugs into BBS

BBS loads addons through the `bbs-addon` entry point: it instantiates the listed classes at the
top of its own initialization and scans them for methods annotated with `@Subscribe`. Those
methods then receive BBS's registration events.

* `BBSPhysicsAddon` — the common half, listed in `fabric.mod.json`. It registers the addon's
  own asset source pack, and hands off to the client half.
* `BBSPhysicsClientAddon` — the client half, in the client source set so that BBS's client-only
  event classes are never loaded on a dedicated server. It registers the language files and the
  settings module.

Beyond the events, BBS's factories are reachable statically — `BBSMod.getForms()`,
`BBSMod.getFactoryCameraClips()`, `BBSMod.getFactoryActionClips()` — so new form types and clip
types can be registered from the addon's own initializer, which Fabric runs after BBS's.
