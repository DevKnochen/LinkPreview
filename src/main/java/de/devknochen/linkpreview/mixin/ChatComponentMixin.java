package de.devknochen.linkpreview.mixin;

import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import de.devknochen.linkpreview.PreviewCardStore;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ChatHud.class)
public abstract class ChatComponentMixin {
	@Shadow
	@Final
	private MinecraftClient client;

	@Shadow
	@Final
	private List<ChatHudLine.Visible> visibleMessages;

	@Shadow
	private int scrolledLines;

	@Shadow
	private boolean hasUnreadNewMessages;

	@Shadow
	private boolean isChatHidden() {
		throw new AssertionError();
	}

	@Shadow
	public abstract int getVisibleLineCount();

	@Shadow
	private double getChatScale() {
		throw new AssertionError();
	}

	@Shadow
	private int getWidth() {
		throw new AssertionError();
	}

	@Shadow
	private int getLineHeight() {
		throw new AssertionError();
	}

	@Shadow
	public abstract boolean isChatFocused();

	/**
	 * @author DevKnochen
	 * @reason Hide LinkPreview spacer messages and keep the source message accent used by the 26.1 implementation.
	 */
	@Overwrite
	public void render(DrawContext context, TextRenderer textRenderer, int currentTick, int mouseX, int mouseY, boolean focused, boolean refresh) {
		if (isChatHidden()) {
			return;
		}

		int lineCount = getVisibleLineCount();
		int messageCount = visibleMessages.size();
		if (messageCount <= 0) {
			return;
		}

		boolean chatFocused = focused;
		float scale = (float) getChatScale();
		int maxWidth = MathHelper.ceil(getWidth() / scale);
		int scaledHeight = context.getScaledWindowHeight();
		context.getMatrices().pushMatrix();
		context.getMatrices().scale(scale, scale);
		context.getMatrices().translate(4.0F, 0.0F);
		int chatBottom = MathHelper.floor((scaledHeight - 40) / scale);
		double textOpacity = client.options.getChatOpacity().getValue() * 0.9F + 0.1F;
		double backgroundOpacity = client.options.getTextBackgroundOpacity().getValue();
		double spacing = client.options.getChatLineSpacing().getValue();
		int lineHeight = getLineHeight();
		int textYInset = (int) Math.round(-8.0 * (spacing + 1.0) + 4.0 * spacing);
		int renderedLines = 0;

		for (int visibleIndex = 0; visibleIndex + scrolledLines < visibleMessages.size() && visibleIndex < lineCount; visibleIndex++) {
			int lineIndex = visibleIndex + scrolledLines;
			ChatHudLine.Visible visible = visibleMessages.get(lineIndex);
			if (visible == null || PreviewCardStore.isSpacerLine(visible)) {
				continue;
			}

			int age = currentTick - visible.addedTime();
			if (age >= 200 && !chatFocused) {
				continue;
			}

			double opacityMultiplier = chatFocused ? 1.0 : linkpreview$messageOpacityMultiplier(age);
			int textAlpha = (int) (255.0 * opacityMultiplier * textOpacity);
			int backgroundAlpha = (int) (255.0 * opacityMultiplier * backgroundOpacity);
			renderedLines++;
			if (textAlpha <= 3) {
				continue;
			}

			int entryBottom = chatBottom - visibleIndex * lineHeight;
			int textY = entryBottom + textYInset;
			context.getMatrices().pushMatrix();
			if (PreviewCardStore.isPreviewSourceLine(visible)) {
				context.fill(-4, entryBottom - lineHeight, maxWidth + 8, entryBottom, backgroundAlpha << 24);
				context.fill(-4, entryBottom - lineHeight, -1, entryBottom, linkpreview$accentWithAlpha((float) opacityMultiplier));
			} else {
				context.fill(-4, entryBottom - lineHeight, maxWidth + 8, entryBottom, backgroundAlpha << 24);
			}

			MessageIndicator indicator = visible.indicator();
			if (indicator != null) {
				int indicatorColor = indicator.indicatorColor() | textAlpha << 24;
				context.fill(-4, entryBottom - lineHeight, -2, entryBottom, indicatorColor);
			}

			context.drawTextWithShadow(textRenderer, visible.content(), 0, textY, 0xFFFFFF + (textAlpha << 24));
			context.getMatrices().popMatrix();
		}

		long queuedMessages = client.getMessageHandler().getUnprocessedMessageCount();
		if (queuedMessages > 0L) {
			int queueTextAlpha = (int) (128.0 * textOpacity);
			int queueBackgroundAlpha = (int) (255.0 * backgroundOpacity);
			context.getMatrices().pushMatrix();
			context.getMatrices().translate(0.0F, (float) chatBottom);
			context.fill(-2, 0, maxWidth + 4, 9, queueBackgroundAlpha << 24);
			context.drawTextWithShadow(textRenderer, Text.translatable("chat.queue", queuedMessages), 0, 1, 0xFFFFFF + (queueTextAlpha << 24));
			context.getMatrices().popMatrix();
		}

		if (chatFocused) {
			int totalHeight = messageCount * lineHeight;
			int visibleHeight = renderedLines * lineHeight;
			int scrollbarTop = scrolledLines * visibleHeight / messageCount - chatBottom;
			int scrollbarHeight = visibleHeight * visibleHeight / totalHeight;
			if (totalHeight != visibleHeight) {
				int scrollbarAlpha = scrollbarTop > 0 ? 170 : 96;
				int scrollbarColor = hasUnreadNewMessages ? 13382451 : 3355562;
				int scrollbarX = maxWidth + 4;
				context.fill(scrollbarX, -scrollbarTop, scrollbarX + 2, -scrollbarTop - scrollbarHeight, scrollbarColor + (scrollbarAlpha << 24));
				context.fill(scrollbarX + 2, -scrollbarTop, scrollbarX + 1, -scrollbarTop - scrollbarHeight, 13421772 + (scrollbarAlpha << 24));
			}
		}

		context.getMatrices().popMatrix();
	}

	@Unique
	private static double linkpreview$messageOpacityMultiplier(int age) {
		double opacity = 1.0D - (double) age / 200.0D;
		opacity = MathHelper.clamp(opacity * 10.0D, 0.0D, 1.0D);
		return opacity * opacity;
	}

	@Unique
	private static int linkpreview$accentWithAlpha(float alphaScale) {
		int argb = 0xFF2F81F7;
		int alpha = (argb >>> 24) & 0xFF;
		int scaledAlpha = MathHelper.clamp(Math.round(alpha * alphaScale), 0, 255);
		return (scaledAlpha << 24) | (argb & 0x00FFFFFF);
	}
}
