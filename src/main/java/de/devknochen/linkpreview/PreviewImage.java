package de.devknochen.linkpreview;

import java.util.List;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

final class PreviewImage {
	private final Identifier textureId;
	private final int width;
	private final int height;
	private final DynamicTexture texture;
	private final List<NativeImage> frames;
	private final int[] frameDelaysMillis;
	private int frameIndex;
	private long nextFrameAtMillis;

	PreviewImage(Identifier textureId, int width, int height) {
		this(textureId, width, height, null, List.of(), new int[0]);
	}

	PreviewImage(Identifier textureId, int width, int height, DynamicTexture texture, List<NativeImage> frames, int[] frameDelaysMillis) {
		this.textureId = textureId;
		this.width = width;
		this.height = height;
		this.texture = texture;
		this.frames = List.copyOf(frames);
		this.frameDelaysMillis = frameDelaysMillis.clone();
	}

	Identifier textureId() {
		return textureId;
	}

	int width() {
		return width;
	}

	int height() {
		return height;
	}

	void updateAnimation(long nowMillis) {
		if (texture == null || frames.size() < 2) {
			return;
		}

		if (nextFrameAtMillis == 0L) {
			nextFrameAtMillis = nowMillis + frameDelay(frameIndex);
			return;
		}

		int advanced = 0;
		while (nowMillis >= nextFrameAtMillis && advanced < frames.size()) {
			frameIndex = (frameIndex + 1) % frames.size();
			NativeImage pixels = texture.getPixels();
			pixels.copyFrom(frames.get(frameIndex));
			texture.upload();
			nextFrameAtMillis += frameDelay(frameIndex);
			advanced++;
		}
	}

	private int frameDelay(int index) {
		if (index < 0 || index >= frameDelaysMillis.length) {
			return 100;
		}

		return frameDelaysMillis[index];
	}

	void close() {
		for (NativeImage frame : frames) {
			frame.close();
		}
	}
}
