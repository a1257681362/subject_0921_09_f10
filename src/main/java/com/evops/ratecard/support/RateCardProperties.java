package com.evops.ratecard.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 年度刊例配置：新建刊例版本未单独指定税率时的默认税率（0.06 = 6%）。 */
@Data
@Component
@ConfigurationProperties(prefix = "rate-card")
public class RateCardProperties {

    /** 默认税率，刊例版本建档未显式给税率时用它 */
    private BigDecimal defaultTaxRate = new BigDecimal("0.0600");
}
