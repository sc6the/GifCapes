# GifCapes

Client-side custom cape mod for Minecraft **1.8.9** Forge. Loads any PNG or APNG as your cape — no Mojang/Optifine subscription required. Cape is visible only to you locally; other players see whatever Mojang has on file.

## Features

- Static PNG and animated APNG capes
- HD cape sheets up to 1024x512 (CAPE region 352x272)
- Persistent across restarts (saved in `.minecraft/capemod_cape.png` + `.json`)
- Front-of-cape preview swatch in the GUI
- Toggle button in the pause menu (top-left) to switch capes on/off without losing the loaded image
- "Cape" button in the pause menu (top-right) opens the change-cape screen
- OptiFine-aware (overrides OptiFine's `locationOfCape` when present)

## Build

Requires JDK 8 (Zulu 8 recommended).

```bash
JAVA_HOME=/path/to/zulu-8 ./gradlew build
```

Output jar lands in `build/libs/`.

## Cape format

The mod accepts:

- **22:17** sheets (e.g. 352x272) — drawn directly into the top-left of the cape canvas.
- **2:1** sheets (e.g. 1024x512) — drawn at full canvas size.
- Anything else — scaled to fit the 22:17 cape body region.

For animated capes, use APNG with up to 60 frames. WebP isn't supported — convert to PNG/APNG first.

Need a cape? Try the cape creator at <https://sc6the.github.io/cape-creator/>.

## Credits

Cape body sampling and HD layout follow the standard MC cape texture convention. APNG decoding uses a vendored decoder.
