package de.devknochen.linkpreview;

import java.util.List;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

final class PreviewImage {
	private final Identifier textureId;
	private final int width;
	private final int height;
	private final NativeImageBackedTexture texture;
	private final List<NativeImage> frames;
	private final int[] frameDelaysMillis;
	private int frameIndex;
	private long nextFrameAtMillis;

	PreviewImage(Identifier textureId, int width, int height) {
		this(textureId, width, height, null, List.of(), new int[0]);
	}

	PreviewImage(Identifier textureId, int width, int height, NativeImageBackedTexture texture, List<NativeImage> frames, int[] frameDelaysMillis) {
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
			NativeImage pixels = texture.getImage();
			if (pixels == null) {
				return;
			}
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
