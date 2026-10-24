# 基准与相机路径

两条基建，解决的都是"同一句话在两次运行里得不到同一个答案"：

- **相机路径**（`com.example.client.bench.CameraPath*`）：把视角录成时间序列，
  回放时按固定步长采样 ⇒ 第 N 帧的相机位姿每次完全相同。
- **基准会话 + 跑批器**（`BenchmarkSession` + `tools/orion_bench/orion_bench.py`）：
  逐帧落盘、心跳看门狗、卡死抓现场、按签名分类失败，并自己判"这次运行能不能用"。

两条都默认关闭，不开时每帧只有一次 `static final boolean` 判空。

---

## 1. 开关一览

全部是 **Java 系统属性**（`-D`），不是 `ORION_*` 环境变量 —— 这两个子系统住在 Java 侧，
`-D` 能到，`std::getenv` 不到（见 [env-switches](env-switches.md) 的说明）。

| 属性 | 默认 | 作用 |
|---|---|---|
| `orion.bench` | 关 | `1`/`true` 开启基准会话 |
| `orion.bench.frames` | `0` | 跑满 N 帧（不含预热）自动收尾；`0` = 关服时收尾 |
| `orion.bench.warmup` | `120` | 前 N 帧不计入统计（管线首次创建、区块首波） |
| `orion.bench.dir` | `benchmark` | 产物目录（相对 gameDir） |
| `orion.bench.tag` | 空 | 写进 `summary.json`，供 `compare` 分组 |
| `orion.campath.record` | 空 | 录制到该名字 |
| `orion.campath.record.frames` | `0` | 采满 N 个航点自动停止并落盘 |
| `orion.campath.play` | 空 | 回放该名字 |
| `orion.campath.loop` | `false` | 回放到末尾后从第 0 帧重启 |
| `orion.campath.fixedStepHz` | 取文件里的值 | 回放步长（帧/秒） |
| `orion.campath.startDelayFrames` | `0` | 跳过开头 N 帧（等世界加载/菜单） |
| `orion.campath.world` | 空 | 覆盖路径目录的第二段（世界名/服务器地址） |
| `orion.campath.diag` | 关 | 每 60 帧打一行回放位姿 |

录制与回放**不能同时开**（两个属性都设时驱动直接拒绝并打一行日志）。

## 2. 快速开始

```bat
:: 录一条路径（走到想复现的机位，然后正常关游戏 —— 关机钩子会落盘）
gradlew.bat runClient -Dorion.campath.record=shot-1
:: 也可以只录 600 帧
gradlew.bat runClient "-Dorion.campath.record=shot-1" -Dorion.campath.record.frames=600

:: 跑一次受控基准（注入 orion.bench，自动挂看门狗）
python tools\orion_bench\orion_bench.py run --cmd "gradlew.bat runClient" ^
    --bench-arg orion.campath.play=shot-1 --bench-arg orion.bench.frames=1800 ^
    --gpu-label "RTX 4090"

:: 已经手动起好了游戏，只挂监视
python tools\orion_bench\orion_bench.py watch --game-dir run

:: 事后复算 / 两次对比 / 列历史
python tools\orion_bench\orion_bench.py analyze run\benchmark\runs\20261024-101500
python tools\orion_bench\orion_bench.py compare <runA> <runB>
python tools\orion_bench\orion_bench.py runs
```

`run` 子命令用 `JAVA_TOOL_OPTIONS` 注入 `-D`，所以对 `gradlew`、启动器、`.bat` 都有效
（不会篡改任何启动脚本）。

## 3. 相机路径

### 3.1 接缝与语义

唯一写入点是 `VulkanCameraMixin` 在 `GameRenderer.renderLevel` HEAD 取到的
`cameraRenderState.pos` / `viewRotationMatrix`，紧接着就写进 native（`updateCamera`）。
驱动就在这两步之间：

- **录制**：把本帧 native 实际会收到的位姿抄一份 ⇒ 录下来的路径与当时画面严格对应；
- **回放**：返回替换位姿，只替换 `pos` 与视图旋转矩阵。**玩家本体、物理、区块加载
  都不受影响**（回放不做碰撞、不挪玩家），所以画面是纯相机运动，和录制时"谁在走"
  无关 —— 这是刻意的：基准要的是可复现的机位，不是可复现的玩家。

视图旋转必须与 `net.minecraft.client.Camera` 逐字一致：
`rotationYXZ(PI - yawRad, -pitchRad, 0)`。少一个 `PI` 画面反 180°，符号写反是俯仰翻转，
两者都不会崩溃，只会让基准截图"看着正常但永远对不上"。

### 3.2 确定性

回放**不用墙钟**，用"渲染帧号 × 固定步长"：同一台机器跑两次，第 N 帧的位姿逐位相同。
墙钟会让一次掉帧把整段路径推后，截图与帧号立刻错位。

### 3.3 文件

`<gameDir>/config/orion/camera-paths/<维度>__<世界>/<name>.json`

```json
{
  "kind": "orion-camera-path",
  "version": 1,
  "name": "shot-1",
  "fixedStepHz": 60,
  "createdAt": 1761200000,
  "waypoints": [
    { "t": 0.0, "x": 12.5, "y": 71.0, "z": -33.25, "yaw": 91.5, "pitch": -12.0 }
  ]
}
```

编解码是**严格的**（照参考实现 `CameraPathCodec` 的口径）：未知键、类型不符、非有限数、
`t` 不严格递增、空航点表，一律拒绝整份文件并打一行日志，而不是"尽量读进来"。
静默容错会让一次坏录制变成一次看起来正常、实际不可比的运行。

`yaw` 存**展开后**的值（连续累加），读取端也会再展开一次，手写 JSON 不会在正北附近转圈。
采样是时间感知的分段三次 Hermite（非均匀切向，端点单侧）；两个航点退化为线性。

## 4. 产物与判读

| 文件 | 内容 |
|---|---|
| `benchmark/status.json` | 心跳：`pid`/`seq`/`frame`/`phase`/`lastFrameMs`/相机状态。**外部只看它是否前进** |
| `benchmark/runs/<stamp>/frames.jsonl` | 每帧一行 `{"i","frameMs","renderMs"}`，事后可重算任意分位数 |
| `benchmark/runs/<stamp>/summary.json` | 分位数 + `valid` + `reasons` + 环境元数据 |
| `benchmark/runs/<stamp>/session.txt` | pid、tag、预热帧数、gameDir |
| `benchmark/runs/<stamp>/gpu.csv` | 跑批器采集的 `nvidia-smi` 时间序列（利用率/显存/频率/功耗） |
| `benchmark/runs/<stamp>/artifacts/` | 日志尾部、最后的 status、线程转储 |

## 5. 三条守卫（这是这套东西的全部价值）

1. **空帧不许冒充成绩**：`frameMs < 3.5ms` 记为退化帧，占比 > 1% ⇒ `valid=false`。
   跑批器在 `analyze` 里**独立复算一遍**，并与 `summary.json` 对账（不一致就警告）——
   不信任被测进程自己算的数。
2. **一次长卡顿不许被平均掉**：`frameMs > 5000ms` 单列计数，提示"去看 frames.jsonl，
   别看平均值"。
3. **卡死要留现场**：`status.json` 的 `seq` 停止前进 25s（`--stall-seconds`）⇒ 立刻
   `jcmd <pid> Thread.print` 落盘，超过 `--kill-seconds` 才杀进程。若日志里出现
   `VK_ERROR_DEVICE_LOST` / `VK_TIMEOUT` / `EXCEPTION_ACCESS_VIOLATION` /
   `VK_ERROR_OUT_OF_DEVICE_MEMORY`，`watch` 会给出分类并拒绝给"通过"结论。

`compare` 在任一边 `valid=false` 时直接说 **NOT COMPARABLE**，不给数字。

## 6. 与本工程既有仪器的关系

- `[PERF L10 *]`（`ORION_PERF_TIMESTAMPS`）和 `[FRAME BUDGET]`（`FrameBudget`）仍然是
  **现场排查**工具：它们回答"这一帧卡在哪一段"。
- 本套东西回答的是另一个问题："这次改动到底有没有变快，以及这次测量能不能信"。
  它不解析 native 日志，只用自己逐帧落的数。
- 参考实现里有一条守卫在本工程**不适用**：他们必须强制关掉 shader 异步编译（否则
  基准测到的是"出帧但没渲染"）。我们的 SPV 是编译期预编译，没有这条路；对应的风险
  变成管线首次创建，用 `orion.bench.warmup`（默认 120 帧）兜住。

## 7. 局限（写出来免得误用）

- 只测"一次连续的世界内渲染"。**不覆盖**：来回切 RT、资源重载、换世界/维度、
  打开设置界面 —— 这些要单独跑，参考实现也明确说了同样的限制。
- 不做截图对比。截图仍要人看，但相机路径保证"同一帧号 = 同一机位"。
- `gpu.csv` 依赖 `nvidia-smi`，非 NVIDIA 机器上自动跳过。
