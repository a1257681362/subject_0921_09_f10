package com.evops.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 素材转档版本：原图每跑一轮转档记一版（上传首轮 / 重新转档）。
 * 转档状态挂在版本上，失败时 failReason 必填，美工翻档案就看得见原因。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_asset_version")
public class AssetVersion extends BaseEntity {
    private Long assetId;
    /** 版本序号，同一素材内从 1 递增 */
    private Integer versionNo;
    /** UPLOAD 上传首轮 / RETRY 重新转档 */
    private String source;
    /** 本版所依据的原文件名 */
    private String fileName;
    private Long fileSize;
    private Integer width;
    private Integer height;
    /** 本版原图落盘路径 */
    private String storagePath;
    /** PENDING / PROCESSING / SUCCESS / FAILED */
    private String transcodeStatus;
    /** 失败原因（成功时为空） */
    private String failReason;
    private String operatorName;
    private String remark;
}
