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

Java side (Fabric mod):

```
gradlew build
```

Native side (Vulkan backend, CMake + MSVC):

```
cmake -S cpp -B cpp/build -G "Visual Studio 17 2022" -A x64
cmake --build cpp/build --config Release --target rtx_backend
```

Shaders are precompiled SPIR-V; the build packages them from `run/shaders/` (see
`rtxRuntimeShaderFiles` in `build.gradle`). The bundled native runtime is expected under
`src/main/resources/natives/windows/`.

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
