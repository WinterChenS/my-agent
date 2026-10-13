package com.myagent.quota.dto;

import java.math.BigDecimal;

/**
 * 额度状态（tier1-upgrades ④）。
 *
 * <p>{@code exceeded} 为 true 时，若 {@code mode=BLOCK} 则应拒绝发起；{@code mode=WARN} 只提示。
 */
public record QuotaStatus(
        BigDecimal usedToday,
        BigDecimal dailyQuota,
        BigDecimal usedMonth,
        BigDecimal monthlyQuota,
        String currency,
        String mode,
        boolean exceeded,
        /** 触发超限的那一级：USER / AGENT / null。 */
        String exceededScope,
        String message) {

    /** 是否达到告警线（≥80%）。 */
    public boolean nearLimit() {
        return !exceeded
                && (ratio(usedToday, dailyQuota) >= 0.8 || ratio(usedMonth, monthlyQuota) >= 0.8);
    }

    private static double ratio(BigDecimal used, BigDecimal quota) {
        if (quota == null || quota.signum() <= 0 || used == null) {
            return 0;
        }
        return used.doubleValue() / quota.doubleValue();
    }
}
