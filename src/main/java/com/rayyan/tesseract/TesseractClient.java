package com.rayyan.tesseract;

import com.rayyan.tesseract.network.SelectionNetworking;
import com.rayyan.tesseract.selection.Selection;
import com.rayyan.tesseract.selection.SelectionManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3f;

public class TesseractClient implements ClientModInitializer {
	private static final float OUTLINE_R = 1.0f;
	private static final float OUTLINE_G = 0.1f;
	private static final float OUTLINE_B = 0.1f;
	private static final float OUTLINE_A = 1.0f;
	private static final float OUTLINE_Y_OFFSET = 0.01f;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(SelectionNetworking.SELECTION_UPDATE, (client, handler, buf, responseSender) -> {
			boolean isBuild = buf.readBoolean();
			Selection selection = SelectionNetworking.readSelection(buf);
			client.execute(() -> {
				if (client.player == null) {
					return;
				}
				if (isBuild) {
					SelectionManager.getBuildSelection(client.player.getUuid()).setCornerA(selection.getCornerA());
					SelectionManager.getBuildSelection(client.player.getUuid()).setCornerB(selection.getCornerB());
				} else {
					SelectionManager.getContextSelection(client.player.getUuid()).setCornerA(selection.getCornerA());
					SelectionManager.getContextSelection(client.player.getUuid()).setCornerB(selection.getCornerB());
				}
			});
		});
		WorldRenderEvents.LAST.register(context -> renderSelectionOutline(context.matrixStack(), context.consumers(), context.camera().getPos()));
	}

	private void renderSelectionOutline(MatrixStack matrices, VertexConsumerProvider consumers, Vec3d cameraPos) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		Selection selection = SelectionManager.getBuildSelection(client.player.getUuid());
		if (selection == null || selection.getCornerA() == null) {
			return;
		}

		BlockPos cornerA = selection.getCornerA();
		BlockPos cornerB = selection.getCornerB();
		BlockPos targetPos = null;

		if (cornerB == null && client.crosshairTarget instanceof BlockHitResult hit) {
			targetPos = hit.getBlockPos();
		}

		BlockPos min;
		BlockPos max;
		if (cornerB != null) {
			min = selection.getMin();
			max = selection.getMax();
		} else if (targetPos != null) {
			min = new BlockPos(
				Math.min(cornerA.getX(), targetPos.getX()),
				Math.min(cornerA.getY(), targetPos.getY()),
				Math.min(cornerA.getZ(), targetPos.getZ())
			);
			max = new BlockPos(
				Math.max(cornerA.getX(), targetPos.getX()),
				Math.max(cornerA.getY(), targetPos.getY()),
				Math.max(cornerA.getZ(), targetPos.getZ())
			);
		} else {
			return;
		}

		BlockPos size = new BlockPos(
			max.getX() - min.getX() + 1,
			max.getY() - min.getY() + 1,
			max.getZ() - min.getZ() + 1
		);
		if ((long) size.getX() * (long) size.getZ() > 4096) {
			return;
		}

		float minX = min.getX();
		float minY = min.getY() + OUTLINE_Y_OFFSET;
		float minZ = min.getZ();
		float maxX = max.getX() + 1.0f;
		float maxY = minY;
		float maxZ = max.getZ() + 1.0f;

		matrices.push();
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
		Matrix4f matrix = matrices.peek().getPositionMatrix();

		VertexConsumer buffer = consumers.getBuffer(RenderLayer.getLines());
		drawLine(buffer, matrix, minX, maxY, minZ, maxX, maxY, minZ);
		drawLine(buffer, matrix, maxX, maxY, minZ, maxX, maxY, maxZ);
		drawLine(buffer, matrix, maxX, maxY, maxZ, minX, maxY, maxZ);
		drawLine(buffer, matrix, minX, maxY, maxZ, minX, maxY, minZ);

		matrices.pop();
	}

	private void drawLine(VertexConsumer buffer, Matrix4f matrix, float x1, float y1, float z1, float x2, float y2, float z2) {
		buffer.vertex(matrix, x1, y1, z1).color(OUTLINE_R, OUTLINE_G, OUTLINE_B, OUTLINE_A).next();
		buffer.vertex(matrix, x2, y2, z2).color(OUTLINE_R, OUTLINE_G, OUTLINE_B, OUTLINE_A).next();
	}
}
