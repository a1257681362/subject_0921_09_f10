package com.evops.playlist.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDate;

/** 建播放列表：给一块屏开排片单，并带上首个编排周（ISO 周一，可传周内任意一天）。 */
@Data
public class PlaylistCreateRequest {
    /** 已挂牌的点位（屏）编号 */
    @NotBlank(message = "点位编号不能为空")
    private String siteNo;
    /** 编排周锚定日（传周内任意一天即可，按 ISO 周归到周一），留空取今天所在周 */
    private LocalDate weekStart;
    private String operatorName;
    private String remark;
}
