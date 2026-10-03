package com.evops.install.dto;

import com.evops.install.entity.InstallEvent;
import com.evops.install.entity.InstallNotice;
import com.evops.install.entity.InstallOrder;
import com.evops.install.entity.InstallPhoto;
import com.evops.install.entity.InstallReceipt;
import lombok.Data;

import java.util.List;

/** 派单全量档案：单 + 事件版本 + 照片 + 回执 + 改期通知。 */
@Data
public class InstallDetailView {
    private InstallOrder order;
    private List<InstallEvent> events;
    private List<InstallPhoto> photos;
    private InstallReceipt receipt;
    private List<InstallNotice> notices;
}
