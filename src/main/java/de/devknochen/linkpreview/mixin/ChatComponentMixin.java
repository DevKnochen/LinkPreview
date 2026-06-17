package de.devknochen.linkpreview.mixin;

import java.util.List;

import net.minecraft.client.MinecraftClient;
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
	public abstract double getChatScale();

	@Shadow
	public abstract int getWidth();

	@Shadow
	private int getMessageIndex(double chatLineX, double chatLineY) {
		throw new AssertionError();
	}

	@Shadow
	private double toChatLineX(double x) {
		throw new AssertionError();
	}

	@Shadow
	private double toChatLineY(double y) {
		throw new AssertionError();
	}

	@Shadow
	private int getLineHeight() {
		throw new AssertionError();
	}

	@Shadow
	private boolean isChatFocused() {
		throw new AssertionError();
	}

	@Shadow
	private int getIndicatorX(ChatHudLine.Visible line) {
		throw new AssertionError();
	}

	@Shadow
	private void drawIndicatorIcon(DrawContext context, int x, int y, MessageIndicator.Icon icon) {
		throw new AssertionError();
	}

	@Shadow
	private static double getMessageOpacityMultiplier(int age) {
		throw new AssertionError();
	}

	/**
	 * @author DevKnochen
	 * @reason Hide LinkPreview spacer messages and keep the source message accent used by the 26.1 implementation.
	 */
	@Overwrite
	public void render(DrawContext context, int currentTick, int mouseX, int mouseY) {
		if (isChatHidden()) {
			return;
		}

		int lineCount = getVisibleLineCount();
		int messageCount = visibleMessages.size();
		if (messageCount <= 0) {
			return;
		}

		boolean chatFocused = isChatFocused();
		float scale = (float) getChatScale();
		int maxWidth = MathHelper.ceil(getWidth() / scale);
		int scaledHeight = context.getScaledWindowHeight();
		context.getMatrices().push();
		context.getMatrices().scale(scale, scale, 1.0F);
		context.getMatrices().translate(4.0F, 0.0F, 0.0F);
		int chatBottom = MathHelper.floor((scaledHeight - 40) / scale);
		int hoveredMessageIndex = getMessageIndex(toChatLineX(mouseX), toChatLineY(mouseY));
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

			double opacityMultiplier = chatFocused ? 1.0 : getMessageOpacityMultiplier(age);
			int textAlpha = (int) (255.0 * opacityMultiplier * textOpacity);
			int backgroundAlpha = (int) (255.0 * opacityMultiplier * backgroundOpacity);
			renderedLines++;
			if (textAlpha <= 3) {
				continue;
			}

			int entryBottom = chatBottom - visibleIndex * lineHeight;
			int textY = entryBottom + textYInset;
			context.getMatrices().push();
			context.getMatrices().translate(0.0F, 0.0F, 50.0F);
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
				if (lineIndex == hoveredMessageIndex && indicator.icon() != null) {
					int indicatorX = getIndicatorX(visible);
					int indicatorY = textY + 9;
					drawIndicatorIcon(context, indicatorX, indicatorY, indicator.icon());
				}
			}

			context.getMatrices().translate(0.0F, 0.0F, 50.0F);
			context.drawTextWithShadow(client.textRenderer, visible.content(), 0, textY, 0xFFFFFF + (textAlpha << 24));
			context.getMatrices().pop();
		}

		long queuedMessages = client.getMessageHandler().getUnprocessedMessageCount();
		if (queuedMessages > 0L) {
			int queueTextAlpha = (int) (128.0 * textOpacity);
			int queueBackgroundAlpha = (int) (255.0 * backgroundOpacity);
			context.getMatrices().push();
			context.getMatrices().translate(0.0F, (float) chatBottom, 50.0F);
			context.fill(-2, 0, maxWidth + 4, 9, queueBackgroundAlpha << 24);
			context.getMatrices().translate(0.0F, 0.0F, 50.0F);
			context.drawTextWithShadow(client.textRenderer, Text.translatable("chat.queue", queuedMessages), 0, 1, 0xFFFFFF + (queueTextAlpha << 24));
			context.getMatrices().pop();
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

		context.getMatrices().pop();
	}

	@Unique
	private static int linkpreview$accentWithAlpha(float alphaScale) {
		int argb = 0xFF2F81F7;
		int alpha = (argb >>> 24) & 0xFF;
		int scaledAlpha = MathHelper.clamp(Math.round(alpha * alphaScale), 0, 255);
		return (scaledAlpha << 24) | (argb & 0x00FFFFFF);
	}
}
