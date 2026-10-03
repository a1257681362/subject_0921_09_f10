package com.evops.asset.dto;

import com.evops.asset.entity.Asset;
import com.evops.asset.entity.AssetFile;
import com.evops.asset.entity.AssetVersion;
import com.evops.asset.entity.TranscodeJob;
import lombok.Data;

import java.util.List;

/** 素材档案详情：档案 + 各转档版本（含每版多规格产物）+ 转档任务状态流水。 */
@Data
public class AssetDetailView {
    private Asset asset;
    private List<VersionView> versions;
    private List<TranscodeJob> jobs;

    @Data
    public static class VersionView {
        private AssetVersion version;
        private List<AssetFile> files;
    }
}
