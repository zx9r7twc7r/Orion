# Orion

A real-time **path-traced renderer for Minecraft**, implemented as a Fabric mod with a custom
Vulkan backend (`rtx_backend.dll`) written in C++.

Rather than a hybrid raster/path-tracing pipeline, Orion builds its own acceleration structures
from the world's section data and renders the frame entirely with hardware ray tracing, then
uses NVIDIA DLSS Ray Reconstruction / Streamline for denoising and frame generation.

> Status: **beta**. Behaviour and configuration keys may change between builds.

## Requirements

* A legitimate copy of **Minecraft Java Edition** (26.3). Minecraft itself is **not** included
  or redistributed by this project; you must own it.
* **Windows x64**.
* An **NVIDIA RTX** GPU (Vulkan ray tracing + DLSS). Non-RTX GPUs are not supported.
* Java 25+ (the mod targets Java 25 bytecode).

Optional integrations:

* **Distant Horizons** 3.3.x — detected at runtime; without it the DH LOD path is skipped.
* **Voxy** — optional LOD provider, loaded reflectively when present.

## Building

### Java side (the mod)

```
gradlew build
```

Outputs `build/libs/orion-*.jar` (and `build/distributions/*.zip` with the shaders/DLL layout
used by releases). Requires JDK 25+; Gradle resolves the toolchain itself.

Precompiled SPIR-V is shipped in the repo under `src/main/resources/shaders/`. `distZip`
prefers `run/shaders/` when it exists (dev machines keep a deployed copy there) and falls back
to `src/main/resources/shaders/`, so a fresh clone builds without any manual setup.

To launch a dev client: `gradlew runClient`.

### Native side (`rtx_backend.dll`)

Prerequisites: **Vulkan SDK**, **JDK** (JNI headers), **Visual Studio 2022** (MSVC x64),
**CMake ≥ 3.20**.

```
cmake -S cpp -B cpp/build -G "Visual Studio 17 2022" -A x64
cmake --build cpp/build --config Release --target rtx_backend
```

The backend links against NVIDIA SDKs that are **not** included in this repository (size and
SDK terms), so `cpp/deps/` has to be populated first — the CMake configure step stops with
`NRD.lib not found` until it is:

| Path | What |
|---|---|
| `cpp/deps/streamline/include` | Streamline SDK headers |
| `cpp/deps/NRD/Include` | NRD 4.18 headers |
| `cpp/deps/NRD/_Bin/Release/NRD.lib` | NRD static library, built with `NRD_STATIC_LIBRARY=ON`, `NRD_NRI=OFF`, `NRD_SUPPORTS_QUAD_INTRINSICS=OFF` |
| `cpp/deps/NRD/build/_deps/shadermake-build/Release/ShaderMakeBlob.lib` | produced by that same NRD build |

The prebuilt runtime (`rtx_backend.dll` + the NVIDIA `nvngx_*` / `sl.*` DLLs) is checked in under
`src/main/resources/natives/windows/`, so building the mod alone is enough to run it.

Offline tools that used to live in `tools/` (cloud previewer, ACES validator) are not part of
this repository; `cpp/CMakeLists.txt` skips those targets automatically when the sources are
absent.

## Running (development)

```
gradlew runClient
```

## License

Orion's own source code and assets are released under the **MIT License** — see [LICENSE](LICENSE).

Third-party components redistributed with it keep their own licenses (NVIDIA RTX SDKs binaries,
Distant Horizons, ImGui, and several textures). Full texts are in the [`licenses/`](licenses)
directory and summarized in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Trademarks and disclaimer

DLSS, Reflex, RTX and NVIDIA are trademarks of NVIDIA Corporation. Minecraft is a trademark of
Mojang Studios. This project is **not affiliated with, endorsed by, or sponsored by** NVIDIA,
Mojang, Microsoft or the Fabric project.
