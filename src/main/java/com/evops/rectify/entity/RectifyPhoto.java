package com.evops.rectify.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 留证照片：现场上报 / 整改回传两阶段各传各的。
 * 单子关闭前一律不许删（接口与服务层双重拒绝），留证要完整。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_rectify_photo")
public class RectifyPhoto extends BaseEntity {
    private Long rectifyId;
    /** REPORT 现场上报 / REPAIR 整改回传 */
    private String phase;
    private String fileName;
    private String contentType;
    private Long fileSize;
    /** 相对 storage-root 的落盘路径 */
    private String storagePath;
    /** 文件指纹 SHA-256（留证核对用，不去重——每次上传都是独立证据） */
    private String fingerprintSha256;
    private String uploaderName;
    private LocalDateTime uploadTime;
}
