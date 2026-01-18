package com.rayyan.tesseract.network;

import com.rayyan.tesseract.selection.Selection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public final class SelectionNetworking {
	public static final Identifier SELECTION_UPDATE = new Identifier("tesseract", "selection_update");

	private SelectionNetworking() {}

	public static void writeSelection(PacketByteBuf buf, boolean isBuild, Selection selection) {
		buf.writeBoolean(isBuild);
		writeCorner(buf, selection.getCornerA());
		writeCorner(buf, selection.getCornerB());
	}

	public static Selection readSelection(PacketByteBuf buf) {
		Selection selection = new Selection();
		BlockPos cornerA = readCorner(buf);
		BlockPos cornerB = readCorner(buf);
		selection.setCornerA(cornerA);
		selection.setCornerB(cornerB);
		return selection;
	}

	private static void writeCorner(PacketByteBuf buf, BlockPos pos) {
		buf.writeBoolean(pos != null);
		if (pos != null) {
			buf.writeBlockPos(pos);
		}
	}

	private static BlockPos readCorner(PacketByteBuf buf) {
		if (!buf.readBoolean()) {
			return null;
		}
		return buf.readBlockPos();
	}
}
