package com.evops.playlist.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;

/**
 * 锁版后改单申请：该周已锁版时，再动排片不能直接改——提交改单申请（PENDING 待审批），
 * 申请里带改后整周编排；审批通过后新草稿锁版生效，原锁版版本置 SUPERSEDED。
 */
@Data
public class PlaylistChangeRequest {
    /** 已锁版周的锚定日（周内任意一天），留空取最新锁版周 */
    private LocalDate weekStart;
    @NotEmpty(message = "改单申请必须提交改后的整周时段")
    @Valid
    private List<SlotSpec> slots;
    /** 改单原因，必填（锁版后为什么动） */
    @NotBlank(message = "锁版后改单必须填写申请原因")
    private String reason;
    /** 申请人，留空取当前登录账号（申请人不能审批自己） */
    private String operatorName;
}
