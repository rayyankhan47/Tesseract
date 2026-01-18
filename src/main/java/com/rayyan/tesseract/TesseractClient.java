package com.rayyan.tesseract;

import com.mojang.blaze3d.systems.RenderSystem;
import com.rayyan.tesseract.selection.Selection;
import com.rayyan.tesseract.selection.SelectionManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;

public class TesseractClient implements ClientModInitializer {
	private static final float OVERLAY_R = 0.2f;
	private static final float OVERLAY_G = 0.7f;
	private static final float OVERLAY_B = 1.0f;
	private static final float OVERLAY_A = 0.25f;
	private static final double OVERLAY_HEIGHT = 0.02;
	private static final double OVERLAY_Y_OFFSET = 0.01;

	@Override
	public void onInitializeClient() {
		WorldRenderEvents.LAST.register(context -> renderSelectionOverlay(context.matrixStack(), context.camera().getPos()));
	}

	private void renderSelectionOverlay(MatrixStack matrices, Vec3d cameraPos) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		Selection selection = SelectionManager.getBuildSelection(client.player.getUuid());
		if (selection == null || !selection.isComplete()) {
			return;
		}

		BlockPos min = selection.getMin();
		BlockPos max = selection.getMax();
		if (min == null || max == null) {
			return;
		}
		BlockPos size = selection.getSize();
		if (size != null && (long) size.getX() * (long) size.getZ() > 4096) {
			return;
		}

		double minX = min.getX();
		double minY = min.getY() + OVERLAY_Y_OFFSET;
		double minZ = min.getZ();
		double maxX = max.getX() + 1.0;
		double maxY = minY + OVERLAY_HEIGHT;
		double maxZ = max.getZ() + 1.0;

		matrices.push();
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
		Matrix4f matrix = matrices.peek().getPositionMatrix();

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableTexture();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);

		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder buffer = tessellator.getBuffer();
		buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

		// Top face quad (filled ground tint)
		buffer.vertex(matrix, (float) minX, (float) maxY, (float) minZ).color(OVERLAY_R, OVERLAY_G, OVERLAY_B, OVERLAY_A).next();
		buffer.vertex(matrix, (float) maxX, (float) maxY, (float) minZ).color(OVERLAY_R, OVERLAY_G, OVERLAY_B, OVERLAY_A).next();
		buffer.vertex(matrix, (float) maxX, (float) maxY, (float) maxZ).color(OVERLAY_R, OVERLAY_G, OVERLAY_B, OVERLAY_A).next();
		buffer.vertex(matrix, (float) minX, (float) maxY, (float) maxZ).color(OVERLAY_R, OVERLAY_G, OVERLAY_B, OVERLAY_A).next();

		tessellator.draw();
		RenderSystem.enableTexture();
		RenderSystem.disableBlend();
		matrices.pop();
	}
}
