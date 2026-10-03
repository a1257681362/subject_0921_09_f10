package com.evops.install.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 到场/完工照片：ARRIVAL 到场打卡（必传） / COMPLETION 完工回执 两阶段。
 * 不去重，每次上传都是独立证据；SHA-256 随档，库里只存相对路径。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_install_photo")
public class InstallPhoto extends BaseEntity {
    private Long dispatchId;
    /** ARRIVAL / COMPLETION */
    private String phase;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private String storagePath;
    private String fingerprintSha256;
    private String uploaderName;
    private LocalDateTime uploadTime;
}
