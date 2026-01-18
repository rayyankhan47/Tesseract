package com.rayyan.tesseract.selection;

import net.minecraft.util.math.BlockPos;

public class Selection {
	private BlockPos cornerA;
	private BlockPos cornerB;

	public void setCornerA(BlockPos pos) {
		this.cornerA = pos;
	}

	public void setCornerB(BlockPos pos) {
		this.cornerB = pos;
	}

	public void clearCornerB() {
		this.cornerB = null;
	}

	public BlockPos getCornerA() {
		return cornerA;
	}

	public BlockPos getCornerB() {
		return cornerB;
	}

	public boolean isComplete() {
		return cornerA != null && cornerB != null;
	}

	public BlockPos getMin() {
		if (!isComplete()) {
			return null;
		}
		return new BlockPos(
			Math.min(cornerA.getX(), cornerB.getX()),
			Math.min(cornerA.getY(), cornerB.getY()),
			Math.min(cornerA.getZ(), cornerB.getZ())
		);
	}

	public BlockPos getMax() {
		if (!isComplete()) {
			return null;
		}
		return new BlockPos(
			Math.max(cornerA.getX(), cornerB.getX()),
			Math.max(cornerA.getY(), cornerB.getY()),
			Math.max(cornerA.getZ(), cornerB.getZ())
		);
	}

	public BlockPos getSize() {
		BlockPos min = getMin();
		BlockPos max = getMax();
		if (min == null || max == null) {
			return null;
		}
		return new BlockPos(
			max.getX() - min.getX() + 1,
			max.getY() - min.getY() + 1,
			max.getZ() - min.getZ() + 1
		);
	}
}
