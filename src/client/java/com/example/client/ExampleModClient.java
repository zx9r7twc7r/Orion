package com.example.client;

import com.example.client.compat.dh.DhBridge;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

public class ExampleModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 远场接入（Distant Horizons）——可选依赖，未装 DH 时整个通路保持关闭。
		// 所有 DH 类型都隔离在 compat.dh 包内，这里只碰门面，不引入任何 DH 类引用。
		DhBridge.install();

		// 2026-10-24 相机路径录制/回放（-Dorion.campath.record / -Dorion.campath.play）。
		// 只读系统属性，未设置时整个驱动保持 OFF（每帧一次 boolean 判空）。
		com.example.client.bench.CameraPathDriver.install();

		// 2026-10-03 实体可见性剔除（来源与授权见类注释）：
		// 仅启动编排线程；判定失败/线程死亡 ⇒ 自动失效，回到"全部提交"的旧行为。
		com.example.client.cull.EntityVisibilityCuller.install();

		// 2026-10-03（发行默认）：把原版「平滑光照」强制关掉。
		// 原因：Orion 的光照完全由 RT 管线产生（VLI 体素光照 + 本地光 RIS），原版的
		// 平滑光照只影响原版顶点光照，在 RT 路径下既无意义、又会让部分方块出现与 RT
		// 光照不一致的边角渐变。作者实机调校即为关闭状态，这里把它作为发行默认。
		//
		// 实现说明：用反射拿选项，避免不同 Minecraft 版本的选项 API 名称差异导致编译
		// 失败；任何异常只记一行日志，绝不影响启动。
		forceVanillaSmoothLightingOff();
	}

	/**
	 * 关闭原版「平滑光照」（options.smoothLighting）。找不到该选项时静默跳过。
	 */
	private static void forceVanillaSmoothLightingOff() {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null) {
				return;
			}
			Object options = minecraft.getClass().getMethod("options").invoke(minecraft);
			if (options == null) {
				return;
			}
			for (String candidate : new String[] { "smoothLighting", "ambientOcclusion" }) {
				try {
					Object option = options.getClass().getMethod(candidate).invoke(options);
					if (option == null) {
						continue;
					}
					// OptionInstance<T>.set(T) 擦除后是 set(Object)。
					option.getClass().getMethod("set", Object.class).invoke(option, Boolean.FALSE);
					System.out.println("[ORION] vanilla " + candidate + " forced off");
					return;
				} catch (NoSuchMethodException notFound) {
					// 换下一个候选名。
				}
			}
			System.out.println("[ORION] vanilla smooth lighting option not found; skipped");
		} catch (Throwable failure) {
			System.out.println("[ORION] smooth lighting force-off skipped: " + failure);
		}
	}
}
