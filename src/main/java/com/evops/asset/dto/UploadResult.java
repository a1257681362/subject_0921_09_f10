package com.evops.asset.dto;

import com.evops.asset.entity.Asset;
import com.evops.asset.entity.TranscodeJob;
import lombok.Data;

/** 上传结果：deduped=true 表示同指纹已存在，没另开档；否则带首轮转档任务。 */
@Data
public class UploadResult {
    private boolean deduped;
    private String message;
    private Asset asset;
    private TranscodeJob job;

    public static UploadResult created(Asset asset, TranscodeJob job) {
        UploadResult result = new UploadResult();
        result.deduped = false;
        result.message = "原图已建档，横竖两版转档任务已提交";
        result.asset = asset;
        result.job = job;
        return result;
    }

    public static UploadResult deduped(Asset asset) {
        UploadResult result = new UploadResult();
        result.deduped = true;
        result.message = "这张图传过了（指纹一致），已并入素材档案 " + asset.getAssetNo() + "，没有另开档";
        result.asset = asset;
        return result;
    }
}
