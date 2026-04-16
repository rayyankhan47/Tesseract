package com.rayyan.tesseract;

import com.rayyan.tesseract.network.SelectionNetworking;
import com.rayyan.tesseract.selection.Selection;
import com.rayyan.tesseract.selection.SelectionManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Matrix3f;

public class TesseractClient implements ClientModInitializer {
	private static final float BUILD_R = 1.0f;
	private static final float BUILD_G = 0.1f;
	private static final float BUILD_B = 0.1f;
	private static final float CONTEXT_R = 0.2f;
	private static final float CONTEXT_G = 1.0f;
	private static final float CONTEXT_B = 1.0f;
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
		WorldRenderEvents.LAST.register(context ->
			renderSelectionOutline(context.matrixStack(), context.consumers(), context.camera().getPos(), context.tickDelta()));
	}

	private void renderSelectionOutline(MatrixStack matrices, VertexConsumerProvider consumers, Vec3d cameraPos, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null) {
			return;
		}
		Selection buildSelection = SelectionManager.getBuildSelection(client.player.getUuid());
		Selection contextSelection = SelectionManager.getContextSelection(client.player.getUuid());

		renderSelectionOutlineFor(matrices, consumers, cameraPos, tickDelta, buildSelection, BUILD_R, BUILD_G, BUILD_B);
		renderSelectionOutlineFor(matrices, consumers, cameraPos, tickDelta, contextSelection, CONTEXT_R, CONTEXT_G, CONTEXT_B);
	}

	private void renderSelectionOutlineFor(MatrixStack matrices, VertexConsumerProvider consumers, Vec3d cameraPos,
		float tickDelta, Selection selection, float colorR, float colorG, float colorB) {
		if (selection == null || selection.getCornerA() == null) {
			return;
		}

		BlockPos cornerA = selection.getCornerA();
		BlockPos cornerB = selection.getCornerB();

		matrices.push();
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
		Matrix4f matrix = matrices.peek().getPositionMatrix();
		Matrix3f normalMatrix = matrices.peek().getNormalMatrix();
		VertexConsumer buffer = consumers.getBuffer(RenderLayer.getLines());

		// Anchor mode: cornerA == cornerB (build wand single punch).
		// Render a tall beacon-style indicator: 4 vertical lines + floor square.
		if (cornerB != null && cornerA.equals(cornerB)) {
			float ax = cornerA.getX();
			float ay = cornerA.getY() + OUTLINE_Y_OFFSET;
			float az = cornerA.getZ();
			float top = ay + 20.0f;
			// Four vertical pillars at the block corners
			drawLine(buffer, matrix, normalMatrix, colorR, colorG, colorB, ax,       ay, az,       ax,       top, az);
			drawLine(buffer, matrix, normalMatrix, colorR, colorG, colorB, ax + 1,   ay, az,       ax + 1,   top, az);
			drawLine(buffer, matrix, normalMatrix, colorR, colorG, colorB, ax,       ay, az + 1,   ax,       top, az + 1);
			drawLine(buffer, matrix, normalMatrix, colorR, colorG, colorB, ax + 1,   ay, az + 1,   ax + 1,   top, az + 1);
			// Floor square
			drawLine(buffer, matrix, normalMatrix, colorR, colorG, colorB, ax,     ay, az,     ax + 1, ay, az);
			drawLine(buffer, matrix, normalMatrix, colorR, colorG, colorB, ax + 1, ay, az,     ax + 1, ay, az + 1);
			drawLine(buffer, matrix, normalMatrix, colorR, colorG, colorB, ax + 1, ay, az + 1, ax,     ay, az + 1);
			drawLine(buffer, matrix, normalMatrix, colorR, colorG, colorB, ax,     ay, az + 1, ax,     ay, az);
			matrices.pop();
			return;
		}

		// Normal two-corner box (used by context wand / web builds after synthesis).
		BlockPos min;
		BlockPos max;
		if (cornerB != null) {
			min = selection.getMin();
			max = selection.getMax();
		} else {
			// cornerB not yet set — live preview follows player's crosshair
			BlockPos targetPos = getTargetBlockPos(MinecraftClient.getInstance(), tickDelta);
			if (targetPos == null) { matrices.pop(); return; }
			min = new BlockPos(Math.min(cornerA.getX(), targetPos.getX()),
				Math.min(cornerA.getY(), targetPos.getY()), Math.min(cornerA.getZ(), targetPos.getZ()));
			max = new BlockPos(Math.max(cornerA.getX(), targetPos.getX()),
				Math.max(cornerA.getY(), targetPos.getY()), Math.max(cornerA.getZ(), targetPos.getZ()));
		}

		int sizeX = max.getX() - min.getX() + 1;
		int sizeZ = max.getZ() - min.getZ() + 1;
		if ((long) sizeX * sizeZ > 4096) { matrices.pop(); return; }

		float minX = min.getX();
		float minY = min.getY() + OUTLINE_Y_OFFSET;
		float minZ = min.getZ();
		float maxX = max.getX() + 1.0f;
		float maxZ = max.getZ() + 1.0f;

		drawLine(buffer, matrix, normalMatrix, colorR, colorG, colorB, minX, minY, minZ, maxX, minY, minZ);
		drawLine(buffer, matrix, normalMatrix, colorR, colorG, colorB, maxX, minY, minZ, maxX, minY, maxZ);
		drawLine(buffer, matrix, normalMatrix, colorR, colorG, colorB, maxX, minY, maxZ, minX, minY, maxZ);
		drawLine(buffer, matrix, normalMatrix, colorR, colorG, colorB, minX, minY, maxZ, minX, minY, minZ);

		matrices.pop();
	}

	private void drawLine(VertexConsumer buffer, Matrix4f matrix, Matrix3f normalMatrix,
		float colorR, float colorG, float colorB, float x1, float y1, float z1, float x2, float y2, float z2) {
		buffer.vertex(matrix, x1, y1, z1).color(colorR, colorG, colorB, OUTLINE_A)
			.normal(normalMatrix, 0.0f, 1.0f, 0.0f).next();
		buffer.vertex(matrix, x2, y2, z2).color(colorR, colorG, colorB, OUTLINE_A)
			.normal(normalMatrix, 0.0f, 1.0f, 0.0f).next();
	}

	private BlockPos getTargetBlockPos(MinecraftClient client, float tickDelta) {
		Entity cameraEntity = client.getCameraEntity();
		if (cameraEntity == null) {
			return null;
		}
		HitResult hit = cameraEntity.raycast(64.0, tickDelta, false);
		if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
			return blockHit.getBlockPos();
		}
		return null;
	}
}
