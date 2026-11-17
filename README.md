# EndExpansion (Paper 1.21.11)

A separate Paper plugin that adds five procedurally generated End structures:
- Ancient Ruins
- Void Towers
- End Dungeons
- Broken Bridges
- End Temples

It does not modify or depend on the Enderite plugin.

## Build
Requires Java 21 and Gradle 8+:
```bash
gradle build
```
The plugin JAR is created in `build/libs/`.

## Install
1. Build the project (or use the included GitHub Actions workflow).
2. Put `EndExpansionPlugin-1.0.0.jar` in the server's `plugins` folder.
3. Restart the server.
4. Reset the End world so newly generated chunks can contain the structures.

## Configuration
Edit `plugins/EndExpansion/config.yml`. Each structure has an `enabled` switch and a per-chunk `chance`.
Loot uses vanilla Minecraft loot tables.

Note: structure generation applies to newly generated End chunks. Existing chunks are not retroactively changed.
