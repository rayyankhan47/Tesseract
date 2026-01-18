package com.rayyan.tesseract.gumloop;

import java.util.List;

public final class GumloopPayload {
	private GumloopPayload() {}

	public static final class Request {
		public String prompt;
		public Origin origin;
		public Size size;
		public List<String> palette;
		public int maxBlocks;
		public Context context;
	}

	public static final class Context {
		public Origin origin;
		public Size size;
		public List<BlockOp> blocks;
		public String screenshot;
	}

	public static final class Origin {
		public int x;
		public int y;
		public int z;
	}

	public static final class Size {
		public int w;
		public int h;
		public int l;
	}

	public static final class BlockOp {
		public int x;
		public int y;
		public int z;
		public String block;
	}

	public static final class Response {
		public Meta meta;
		public List<BlockOp> ops;
	}

	public static final class Meta {
		public String theme;
		public int blockCount;
		public List<String> warnings;
	}
}
