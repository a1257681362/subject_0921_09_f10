package com.evops.asset.dto;

import lombok.Data;

import java.util.List;

/** 当前生效的转档规格口径（给前端标清用途与体积上限）。 */
@Data
public class SpecsView {
    private List<SpecItem> specs;
    private long maxFileSizeBytes;
    private long maxFileSizeKb;
    private float qualityInitial;
    private float qualityFloor;
    private float qualityDecrement;

    @Data
    public static class SpecItem {
        private String name;
        private String usage;
        private String ratio;
        private int longEdge;
    }
}
