package com.evops.asset.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.List;

/** 素材多规格转档配置：常规规格、长边限定、体积上限与质量压缩档位。 */
@Data
@Component
@ConfigurationProperties(prefix = "asset")
public class AssetProperties {

    /** 原图与产物落盘根目录 */
    private String storageRoot = "./data/assets";

    /** 常规规格：横竖两版 */
    private List<Spec> specs = new ArrayList<>();

    /** 产物体积上限，超标再压一轮质量 */
    private DataSize maxFileSize = DataSize.ofKilobytes(300);

    private Quality quality = new Quality();

    @Data
    public static class Spec {
        /** 规格标识，落库 usage code，如 feed_landscape */
        private String name;
        /** 用途说明（标清用途），如 信息流横版 */
        private String usage;
        /** 目标比例 宽:高，如 16:9 */
        private String ratio;
        /** 长边限定像素 */
        private int longEdge;

        /** 解析 "16:9" 为 [宽, 高]。 */
        public int[] ratioParts() {
            String[] parts = ratio.split(":");
            if (parts.length != 2) {
                throw new IllegalStateException("规格比例配置非法: " + name + "=" + ratio);
            }
            return new int[] { Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()) };
        }
    }

    @Data
    public static class Quality {
        /** 首轮 JPEG 质量 */
        private float initial = 0.85f;
        /** 质量下限：压到这档仍超标也不再降，按最好成绩交差 */
        private float floor = 0.5f;
        /** 每超一轮上限的质量降幅 */
        private float decrement = 0.15f;
    }
}
