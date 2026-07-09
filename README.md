# MC-308593 Fix

A small Fabric client-side mod that works around the animated-texture rendering bug
[MC-308593](https://bugs.mojang.com/browse/MC/issues/MC-308593) in **Minecraft Java Edition 26.2**.

## Changelog

* **1.0.1** — Fixed a reflection bug that caused the workaround to fail to initialize
  (`NoSuchMethodException` for `SpriteContents$AnimationState.<init>`). This made the
  mod look like it "did nothing", especially when other mods such as Sodium were
  present. The constructor now correctly receives the synthetic outer
  `SpriteContents` instance.
* **1.0.0** — Initial workaround using `writeToTexture` instead of the broken
  PBO sub-region copy path.

## What is broken

In 26.2 (starting with 26.2 Pre-Release 3) Mojang changed how animated texture frames are uploaded to the GPU.
The old code used the synchronous `NativeImage`-based `CommandEncoder.writeToTexture(...)` path;
the new code uses a staging buffer + `CommandEncoder.copyBufferToTexture(...)` path.

On some drivers — most commonly the Intel HD 4000 / Gen 7 iGPU driver — the PBO-based
sub-region copy does not correctly honor the `GL_UNPACK_IMAGE_HEIGHT` / row-stride state.
The result is that the animated frames are uploaded from the wrong offsets, which makes
players see:

* **Water blocks** as completely invisible (they upload transparent pixels).
* **Lava, fire, soul fire, lanterns, sea lanterns, etc.** as solid black or dark blocks.
* Other animated blocks/items missing or discolored.

Static blocks (including stained glass) are unaffected because they still use the old
`writeToTexture` path.

## How this mod fixes it

The mod intercepts `SpriteContents$AnimatedTexture.createAnimationState(...)` and replaces
the broken staging-buffer upload with the old CPU-side upload path:

1. For every animation frame and every mip level, create a small temporary
   `NativeImage` that contains only that frame.
2. Copy the frame out of the full sprite-sheet image with `NativeImage.copyRect(...)`.
3. Upload the cropped image with `CommandEncoder.writeToTexture(...)`.

This preserves the original animation timing, transparency, and mipmapping, while avoiding
the `copyBufferToTexture` path that trips up the affected drivers.

## Requirements

* Minecraft 26.2
* Fabric Loader 0.19.3 or newer
* Java 25 (Minecraft 26.2 requires it)

## Building

```bash
./gradlew build
```

The resulting mod jar is in `build/libs/mc308593fix-1.0.1.jar`.

> **Note:** You need Java 25 to build and run. If it is not your default JDK, set
> `org.gradle.java.home=/path/to/jdk25` in `gradle.properties` or set `JAVA_HOME` before
> running Gradle.

The build currently depends on two files that are not on Maven Central:

* `libs/minecraft-26.2-client.jar` — the Minecraft 26.2 client jar
* `libs/fabric-loader-0.19.3.jar` — the Fabric Loader jar for 26.2

If you run a normal Fabric Launcher profile for 26.2, you can copy these jars from:

* `.minecraft/versions/26.2/26.2.jar` (or `client.jar` from the launcher cache)
* `.minecraft/libraries/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar`

and place them in the `libs/` folder.

## Installation

1. Install Fabric Loader for Minecraft 26.2.
2. Download the latest release jar from the [Releases](https://github.com/colGem-ga/26.2-Invisible-Water-Fix/releases) page and copy it into your `.minecraft/mods/` folder.
3. Launch the game. Water, lava, fire, and other animated textures should render correctly.

## Disabling the workaround

If you suspect this mod is causing problems, you can disable it without removing the mod by
adding the following JVM argument:

```
-Dmc308593fix.disable=true
```

When disabled, the mod falls back to vanilla behavior.

## Limitations

* This is a workaround, not an official Mojang fix. It restores the 26.1 upload behavior for
  animated sprites only.
* It does not fix any other `copyBufferToTexture`-based uploads (e.g. cube-map textures if
  those are also affected on your hardware). If you still see broken skies, please report it.
* The extra CPU-side copy adds a small one-time cost during resource reload, but it keeps
  animations fully functional.

## License

CC0 1.0 Universal — see `LICENSE`.
