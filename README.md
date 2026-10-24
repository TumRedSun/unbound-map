# Unbound Maps

Original open-source minimap and world map mod for **NeoForge 1.21.1**: a square HUD
minimap, a full-screen world map with a right-click menu (waypoints and teleport),
unrestricted cave mode and a waypoint system with in-world beacon beams.

An independent project: it contains no code, assets or identifiers from other map mods,
and it does not pretend to be them. Addons should integrate through the stable package
`net.unbound.maps.api`.

- **License:** GPL-3.0-or-later (see [LICENSE](LICENSE))
- **Mod id:** `unboundmaps`
- **Minecraft:** 1.21.1
- **Loader:** NeoForge 21.1.0+ (built against `neo_version` from `gradle.properties`)

## Features

- Square or round HUD minimap with zoom keys, coordinates and biome line
- Full-screen world map with drag panning, scroll zoom and a right-click context menu
  (edit/delete waypoint, create waypoint, "Teleport here" — the command template is
  configurable in the client config, e.g. `/tp @s {x} {y} {z}` or an `/execute in` variant)
- Cave mode (Xaero-style slice around the player's altitude, works at any depth and in
  every dimension, toggle with `C`)
- Waypoints saved per world / per server and per dimension as JSON, with optional
  death points, in-world beacon beams and on-screen floating labels
- A small API (`net.unbound.maps.api.UnboundMapsApi`) for other mods to add / remove /
  list waypoints

## Building

Requirements: **JDK 21** (no IDE needed, Gradle is included via the wrapper).

```bash
./gradlew build
```

The resulting mod jar is written to `build/libs/unboundmaps-<version>.jar`.

To run a dev client with the mod loaded:

```bash
./gradlew runClient
```

## Project layout

```
src/main/java/net/unbound/maps/
├── UnboundMapsMod.java        # @Mod entry point, config registration
├── UnboundMapsClient.java     # client bootstrap (events, gui layers, keys)
├── UnboundConfig.java         # ModConfigSpec (client config)
├── UnboundMapsDebug.java      # exception-rate limiter for logs
├── api/                       # stable integration API for other mods
├── keybinds/                  # key mappings and per-tick input handling
├── mapdata/                   # chunk color cache, minimap texture, world map cells
├── render/                    # minimap HUD, beacon beams, markers, draw helpers
├── screen/                    # world map screen, waypoint list / editor screens
├── util/                      # screen-space projection, teleport command formatting
└── waypoints/                 # waypoint model and persistence manager
src/main/resources/
├── META-INF/neoforge.mods.toml
├── assets/unboundmaps/lang/   # en_us.json, ru_ru.json
└── pack.mcmeta
```

## Note about the recovered sources

This source tree was reconstructed by decompiling the released
`unboundmaps-neoforge-1.21.1-1.2.0.jar` (permitted by GPL-3.0-or-later) with
Vineflower and cleaning up decompiler artifacts:

- generic type arguments and `Iterator`/`Map.Entry` raw types restored
- redundant casts (`(Boolean)`, `(Integer)`, `(String)`) inserted by the decompiler removed
- varargs calls (`I18n.get(..., new Object[0])`, `Component.translatable(..., new Object[]{...})`)
  restored to their natural form
- `ModConfigSpec.Builder.comment(...)` restored to varargs form

Local variable names are decompiler-generated (`var0`, `var1`, ...); original names are
not preserved in compiled bytecode. One private helper in `ScreenProjector` keeps its
compiled name `MthdClamp` for exactness. `UnboundMapsMod.VERSION` still says `"1.1.0"`
in the bytecode of the released 1.2.0 jar — the constant was not bumped by the author
before the release; update it as you see fit.

## License

Copyright (C) 2025 Unbound

This program is free software: you can redistribute it and/or modify it under the terms
of the GNU General Public License as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for more details.
