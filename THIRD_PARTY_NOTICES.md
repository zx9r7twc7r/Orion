# Third-Party Notices

`LICENSE` (MIT) covers **Orion's own source code and assets only**. The third-party
components redistributed alongside it (NVIDIA RTX SDKs binaries, Distant Horizons,
Dear ImGui, third-party textures, ...) remain under their own terms — see the
sections below and the full texts in `licenses/`.

---

The Tyndall cumulus cloud preset and density-shaping approach were adapted
from `tyndall-clouds`, Copyright (c) 2026 ProxySudo contributors, supplied
with this project as `tyndall-clouds-main.zip`.

The source project is licensed under the MIT License. This project does not
include its Rust/wgpu renderer; its ideas were reimplemented for the existing
Vulkan compute-cloud pipeline.

MIT License

Copyright (c) 2026 ProxySudo contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

--------------------------------------------------------------------------------

## psrdnoise (water_psrdnoise.glsl)

The water surface's non-repeating wave field uses `psrdnoise2.glsl`, vendored
verbatim from https://github.com/stegu/psrdnoise (src/psrdnoise2.glsl, version
2021-12-02), Copyright (c) 2021 Stefan Gustavson and Ian McEwan, supplied with
this project as `cpp/shaders/water_psrdnoise.glsl` (MIT license text preserved
in the file header). The 2-D simplex gradient noise with analytic derivatives
provides the non-periodic wave octaves; the octaves themselves (domain
rotation, advection, amplitude table) are this project's own composition in
`cpp/shaders/water_noise_wave.glsl`.

MIT License

Copyright (c) 2021 Stefan Gustavson and Ian McEwan

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

--------------------------------------------------------------------------------

## Snowflake icons (rt_rain_streaks.png, bottom half; tools/snow_atlas/)

The 16 snowflake tiles in the bottom half of the weather atlas
`assets/orion/textures/rt_rain_streaks.png` are derived from two icons by
Lorc, downloaded from https://game-icons.net/ (snowflake-1, snowflake-2;
white-on-transparent 512x512 PNGs), used and modified (rotated, mirrored,
rescaled, re-tiled) under the Creative Commons Attribution 3.0 Unported
License (CC BY 3.0).

Copyright (c) Lorc, via game-icons.net

Licensed under CC BY 3.0: https://creativecommons.org/licenses/by/3.0/


--------------------------------------------------------------------------------

## SHaRC (cpp/shaders/sharc/*.h)

This software contains source code provided by NVIDIA Corporation.

Vendored verbatim from https://github.com/NVIDIAGameWorks/Spatial-Hash-Radiance-Cache
(revision 4e21b58, v1.8.3), supplied with this project as
`cpp/shaders/sharc/SharcCommon.h`, `HashGridCommon.h`, `SharcTypes.h`,
`HashGridTypes.h` and `SharcGlslHelpers.h`. See `cpp/shaders/sharc/NOTICE.txt`
for the distribution requirements that apply, and the upstream `License.md`
for the full NVIDIA RTX SDKs License text.

The vendored files are unmodified; the surrounding renderer, the cache
resource management and the Update/Resolve/Query pass wiring are this
project's own work.

--------------------------------------------------------------------------------

## NVIDIA RTX SDKs — redistributed binaries (`src/main/resources/natives/windows/`)

This software contains NVIDIA Corporation binaries. Redistributed under the
**NVIDIA RTX SDKs License**, which permits distribution only as part of an
application and requires the license text to accompany it.

| File | Component | Terms |
|---|---|---|
| `nvngx_dlss.dll` | DLSS (Super Resolution) | NVIDIA RTX SDKs License |
| `nvngx_dlssd.dll` | DLSS Ray Reconstruction | NVIDIA RTX SDKs License |
| `nvngx_dlssg.dll` | DLSS Frame Generation | NVIDIA RTX SDKs License |
| `nvngx_deepdvc.dll` | DeepDVC | NVIDIA RTX SDKs License |
| `NvLowLatencyVk.dll` | Reflex (Vulkan low latency) | NVIDIA SDK License Agreement |
| `sl.common.dll`, `sl.interposer.dll` | Streamline runtime | NVIDIA SDK License Agreement |
| `sl.dlss.dll`, `sl.dlss_d.dll`, `sl.dlss_g.dll` | Streamline DLSS plugins | NVIDIA SDK License Agreement |
| `sl.reflex.dll`, `sl.pcl.dll`, `sl.nvperf.dll` | Streamline Reflex / PCL / NVPerf | NVIDIA SDK License Agreement |
| `sl.nis.dll`, `sl.directsr.dll`, `sl.deepdvc.dll` | Streamline NIS / DirectSR / DeepDVC | NVIDIA SDK License Agreement |

Full texts:

* `licenses/NVIDIA-RTX-SDKs-License.txt`
* `licenses/NVIDIA-SDK-License-Agreement.txt`

`rtx_backend.dll` is this project's own code and is covered by the MIT license.

NVIDIA, DLSS, Reflex and RTX are trademarks of NVIDIA Corporation.

--------------------------------------------------------------------------------

## NVIDIA NRD / OMM (vendored at build time, `cpp/deps/`)

The NRD (`cpp/deps/NRD`) and OMM (`cpp/deps/OMM`) SDK headers and prebuilt
libraries are pulled in for the build only and are **not** part of this
repository. They ship under the NVIDIA RTX SDKs License
(`cpp/deps/NRD/LICENSE.txt`, `cpp/deps/OMM/LICENSE.txt` in a local checkout).

--------------------------------------------------------------------------------

## Distant Horizons (`cpp/shaders/DistantHorizons-*.jar`, `libs/DistantHorizonsApi-*.jar`)

Distant Horizons is licensed under **LGPL-3.0**. The mod is a compile-time and
runtime dependency only: it is never bundled into the Orion jar. The copies
kept under `cpp/shaders/` and `libs/` exist so the client sources compile on a
machine where DH is not installed in `run/mods`.

Upstream: https://gitlab.com/distant-horizons-team/distant-horizons
Sources for the LGPL binaries are available from upstream.
Full texts: `licenses/DistantHorizons-LGPL-3.0.txt`,
`licenses/DistantHorizons-GPL-3.0.txt`,
`licenses/DistantHorizons-LOGOS-CC-BY-SA-4.0.txt`.

--------------------------------------------------------------------------------

## Dear ImGui (`cpp/external/imgui`)

Vendored verbatim, MIT license, Copyright (c) 2014-2024 Omar Cornut.
Full text: `licenses/ImGui-MIT.txt`.

--------------------------------------------------------------------------------

## ACES 2.0 output transform (`tools/aces2/`)

The colour pipeline's tone-mapping / output-transform constants
(`tools/aces2/aces2_tables.glsl`) are generated by `tools/aces2/port.py`, a
function-by-function port of the official **ACES 2.0 CTL** reference
implementation, licensed **Apache-2.0** (ACES Project / Academy Software
Foundation).

Upstream: https://github.com/ampas/aces-dev — Apache-2.0
text: https://www.apache.org/licenses/LICENSE-2.0

Only the generated numeric tables are shipped; the CTL sources are not
redistributed.

--------------------------------------------------------------------------------

## Planet / sky textures (`src/main/resources/assets/orion/textures/`)

`rt_end_atlas.png` is a composite of four equirectangular images:

* Milky Way panorama, Earth day/night, Earth cloud layer — **Solar System Scope**
  (https://www.solarsystemscope.com/textures/), **CC BY 4.0**.

Per-asset credits and the CC BY 4.0 reference are shipped with the mod in
`assets/orion/textures/END_SKY_CREDITS.txt`; that file must not be removed from
the distribution.

--------------------------------------------------------------------------------

## Minecraft / Fabric

Minecraft is not redistributed by this project. Orion is a mod that runs on top
of a user-supplied, legitimately licensed Minecraft installation. Minecraft is a
trademark of Mojang Studios; the Fabric loader is a separate project with its
own license.
