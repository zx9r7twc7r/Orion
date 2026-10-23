# Changelog

格式参考 [Keep a Changelog](https://keepachangelog.com/)，版本号跟 `fabric.mod.json` 的 `version` 走。

## [0.42] — 2026-10-23

首个公开版本。实时光线追踪渲染后端 + Fabric 侧接入。

### 渲染

- 自研 Vulkan RT 后端（`rtx_backend.dll`），整帧由硬件光追产出，不做光栅混合
- 延迟材质解析（`RT_DEFERRED_MATERIAL` 为唯一模式）：命中着色器只写命中身份，材质在 ray generation 阶段求值
- NRD RELAX 本地光去噪 + DLSS Ray Reconstruction / Streamline 后处理链
- 体积云 v1–v4、体积雾、体积光（空气分支退役，仅保留水下）、水面 FFT 涌浪 + 焦散
- ACES 2.0 SDR 输出变换（按官方 CTL 逐函数移植，常量离线烘焙进 `rr_sdr_post.comp`）
- 远场：Distant Horizons 顶点缓冲接管 / Voxy LOD（均为可选，运行时探测）

### 集成

- Distant Horizons 3.3.x：官方 API 通道化（near clip、雾/云所有权、深度约定护栏）
- 可选的无描述符回退：体积光不引入额外 descriptor

### 工程

- 47 个 `ORION_*` 环境变量集中登记在 [`docs/env-switches.md`](docs/env-switches.md)
- 设计取舍与实测记录迁到 [`docs/decisions/`](docs/decisions)
- CI：每次 push/PR 用官方 Vulkan SDK 的 glslang 编译全部 58 个 shader，并断言 SER 扩展可用
- 全新 clone 可直接 `gradlew build`（shader 源目录会回退到 `src/main/resources/shaders`）

### 已知限制

- 仅 Windows x64 + NVIDIA RTX
- 仓库内含 NVIDIA RTX SDKs 运行时 DLL（约 118MB），受其许可约束，见 `THIRD_PARTY_NOTICES.md`
- `nvngx_dlss.dll` 单文件 56MB，超过 GitHub 的 50MB 建议值（未超 100MB 硬上限）
