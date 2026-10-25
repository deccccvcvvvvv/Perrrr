# Dependencies & Licences

## Bundled in the APK

| Dependency | Version | Licence | Used for |
|---|---|---|---|
| Kotlin stdlib | 2.0.20 | Apache-2.0 | language runtime |
| kotlinx-coroutines (core, android) | 1.8.1 | Apache-2.0 | async work, flows |
| kotlinx-serialization-json | 1.7.2 | Apache-2.0 | all JSON (metadata, profiles, layouts, settings) |
| AndroidX Compose BOM (ui, foundation, material3, material-icons-core, animation, runtime) | 2024.09.02 | Apache-2.0 | UI |
| AndroidX activity-compose | 1.9.2 | Apache-2.0 | activities, result APIs |
| AndroidX core-ktx | 1.13.1 | Apache-2.0 | compat helpers, FileProvider |
| AndroidX lifecycle (viewmodel-compose, runtime-compose) | 2.8.6 | Apache-2.0 | ViewModel, lifecycle-aware flows |
| Transitive AndroidX (collection, annotation, savedstate, emoji2, profileinstaller, startup, tracing, …), Guava `listenablefuture`, JetBrains annotations | (resolved by Gradle) | Apache-2.0 | pulled in by the above |
| `libcsglfw.so`, `libcsbridge.so` | this project | project licence | GLFW API on Android, in-process JVM bridge |

No other third-party code is bundled. All Kotlin and C sources in this repository were written for this project.

## Build-time only

| Tool | Licence |
|---|---|
| Android Gradle Plugin 8.5.2, Android SDK/NDK/build-tools | Android SDK licence (accepted by `setup_toolchain.sh`) |
| Gradle 8.7 | Apache-2.0 |
| Temurin JDK 17 | GPLv2 + Classpath Exception |
| JUnit 4.13.2 (tests) | EPL-1.0 |

## Downloaded at runtime (not redistributed)

| What | From | Terms |
|---|---|---|
| Minecraft client JAR, libraries, assets | Mojang (`piston-meta`, `piston-data`, `libraries.minecraft.net`, `resources.download.minecraft.net`) | Minecraft EULA. Users must own Minecraft Java Edition for online play |
| Fabric / Quilt loader metadata & libraries | `meta.fabricmc.net`, `meta.quiltmc.org`, their Maven repos | Apache-2.0 |
| Mods, resource packs, shaders | Modrinth API (no key), CurseForge API (user-supplied key) | per-project licences, shown on the project page |

## User-imported native payloads (see `COMPATIBILITY.md`)

| Payload | Typical licence | Obligation if *you* redistribute it |
|---|---|---|
| OpenJDK built for Android | GPLv2 + Classpath Exception | provide the corresponding source |
| LWJGL 3 | BSD-3-Clause | keep the notice |
| OpenAL Soft | LGPL-2.0+ | allow relinking, provide the source |
| GL4ES | MIT | keep the notice |
| Mesa (OSMesa, Zink) | MIT | keep the notice |
| ANGLE | BSD-3-Clause | keep the notice |

Component packs carry `license` and `source` fields, and the app shows them before activation.

## Policy compliance notes
* No Microsoft, Xbox, CurseForge or other private credentials are embedded. Client IDs and keys are provided by the user or publisher.
* Offline profiles generate the standard offline UUID. They are labelled as offline and are not presented as a way to play online.
* "Minecraft" is a trademark of Mojang Synergies AB. This project is not affiliated with Mojang or Microsoft.
