package com.rayyan.tesseract.paste;

import com.rayyan.tesseract.agent.BlockOp;

import java.util.List;

/** JSON shape for the { meta, ops } build plan used by the paste path and web-build serialisation. */
public final class BuildPlan {
    public Meta meta;
    public List<BlockOp> ops;

    public static final class Meta {
        public String theme;
        public int blockCount;
        public List<String> warnings;
    }
}
