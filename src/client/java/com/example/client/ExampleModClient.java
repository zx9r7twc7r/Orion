package com.example.client;

import com.example.client.compat.dh.DhBridge;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

public class ExampleModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		DhBridge.install();

		com.example.client.bench.CameraPathDriver.install();

		com.example.client.cull.EntityVisibilityCuller.install();

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
				}
			}
			System.out.println("[ORION] vanilla smooth lighting option not found; skipped");
		} catch (Throwable failure) {
			System.out.println("[ORION] smooth lighting force-off skipped: " + failure);
		}
	}
}
