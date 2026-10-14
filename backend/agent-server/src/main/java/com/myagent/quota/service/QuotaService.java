package com.myagent.quota.service;

import com.myagent.agentconfig.entity.Agent;
import com.myagent.agentconfig.mapper.AgentMapper;
import com.myagent.audit.mapper.TokenUsageMapper;
import com.myagent.auth.entity.SysUser;
import com.myagent.auth.mapper.SysUserMapper;
import com.myagent.quota.dto.QuotaStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 支出配额（tier1-upgrades ④）。
 *
 * <p>校验时机是**发起 run 之前**（spec 明确不做"事后强制中断"）。成本口径沿用 Phase 15
 * （每百万 token 单价 × 用量，不做汇率换算），直接复用审计的聚合 SQL，不另立一套算法。
 *
 * <p>两级同时校验：用户额度（{@code sys_user}）与 Agent 额度（{@code agent}）；额度为空=不限。
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final SysUserMapper userMapper;
    private final AgentMapper agentMapper;
    private final TokenUsageMapper usageMapper;

    public QuotaService(SysUserMapper userMapper, AgentMapper agentMapper, TokenUsageMapper usageMapper) {
        this.userMapper = userMapper;
        this.agentMapper = agentMapper;
        this.usageMapper = usageMapper;
    }

    /**
     * 评估该用户 + 该 Agent 的额度状态。
     *
     * <p>{@code agentDbId} 可为 null（例如只查用户额度时）。
     */
    public QuotaStatus evaluate(Long userId, Long agentDbId) {
        SysUser user = userId == null ? null : userMapper.selectById(userId);
        Agent agent = agentDbId == null ? null : agentMapper.selectById(agentDbId);

        OffsetDateTime dayStart = startOfDay();
        // 上界用「明天 00:00 / 下月 1 日 00:00」而不是"此刻"：
        // created_at 是**数据库**的 now()，而此刻是**应用进程**的 now()，两台机器时钟会有偏差。
        // 实测：DB 快 3 秒 → 刚记录的用量落在 [今日, 此刻] 之外、被静默漏计，
        // 后果是刚花完一笔却仍判定未超限（额度形同虚设）。用整日/整月窗口就没有这个问题。
        OffsetDateTime dayEnd = startOfNextDay();
        OffsetDateTime monthStart = startOfMonth();
        OffsetDateTime monthEnd = startOfNextMonth();

        BigDecimal usedTodayUser = cost(userId, dayStart, dayEnd, null);
        BigDecimal usedMonthUser = cost(userId, monthStart, monthEnd, null);
        BigDecimal usedTodayAgent = agentDbId == null ? BigDecimal.ZERO : cost(userId, dayStart, dayEnd, agentDbId);
        BigDecimal usedMonthAgent = agentDbId == null ? BigDecimal.ZERO : cost(userId, monthStart, monthEnd, agentDbId);

        // 用户级
        if (exceeds(usedTodayUser, user == null ? null : user.getDailyQuota())
                || exceeds(usedMonthUser, user == null ? null : user.getMonthlyQuota())) {
            return status(user, usedTodayUser, usedMonthUser, "USER", agent);
        }
        // Agent 级
        if (agent != null
                && (exceeds(usedTodayAgent, agent.getDailyQuota())
                        || exceeds(usedMonthAgent, agent.getMonthlyQuota()))) {
            // Agent 级的已用量也带上（便于提示"这个 Agent 用了多少"），货币仍取用户/Agent 配置
            return new QuotaStatus(
                    usedTodayAgent,
                    agent.getDailyQuota(),
                    usedMonthAgent,
                    agent.getMonthlyQuota(),
                    currencyOf(agent.getQuotaCurrency(), user),
                    modeOf(user),
                    true,
                    "AGENT",
                    buildMessage("AGENT", usedTodayAgent, agent.getDailyQuota(), usedMonthAgent,
                            agent.getMonthlyQuota(), currencyOf(agent.getQuotaCurrency(), user)));
        }
        return status(user, usedTodayUser, usedMonthUser, null, agent);
    }

    /** 用户级额度状态（未超限时 used 用用户维度）。 */
    private QuotaStatus status(SysUser user, BigDecimal usedToday, BigDecimal usedMonth, String scope, Agent agent) {
        String currency = currencyOf(user == null ? null : user.getQuotaCurrency(), user);
        BigDecimal daily = user == null ? null : user.getDailyQuota();
        BigDecimal monthly = user == null ? null : user.getMonthlyQuota();
        boolean exceeded = "USER".equals(scope);
        return new QuotaStatus(
                usedToday,
                daily,
                usedMonth,
                monthly,
                currency,
                modeOf(user),
                exceeded,
                scope,
                exceeded
                        ? buildMessage("USER", usedToday, daily, usedMonth, monthly, currency)
                        : null);
    }

    /** 超限提示：必须包含「已用 / 额度」。 */
    static String buildMessage(
            String scope,
            BigDecimal usedToday,
            BigDecimal daily,
            BigDecimal usedMonth,
            BigDecimal monthly,
            String currency) {
        String who = "AGENT".equals(scope) ? "该 Agent" : "你";
        StringBuilder sb = new StringBuilder();
        if (exceeds(usedToday, daily)) {
            sb.append(String.format("%s今日已用 %s %s，日额度 %s", who, plain(usedToday), currency, plain(daily)));
        }
        if (exceeds(usedMonth, monthly)) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(String.format("%s本月已用 %s %s，月额度 %s", who, plain(usedMonth), currency, plain(monthly)));
        }
        if (sb.length() == 0) {
            sb.append("额度不足");
        }
        return sb.append("，已超出额度上限。").toString();
    }

    /** 金额展示：保留到 8 位小数再去掉尾零（额度可能小到 0.000001，按 4 位会被四舍五入成 0）。 */
    static String plain(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    static boolean exceeds(BigDecimal used, BigDecimal quota) {
        return quota != null && quota.signum() > 0 && used != null && used.compareTo(quota) >= 0;
    }

    private static String modeOf(SysUser user) {
        String mode = user == null ? null : user.getQuotaMode();
        return mode == null || mode.isBlank() ? "BLOCK" : mode;
    }

    private static String currencyOf(String preferred, SysUser user) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        if (user != null && user.getQuotaCurrency() != null && !user.getQuotaCurrency().isBlank()) {
            return user.getQuotaCurrency();
        }
        return "CNY";
    }

    private BigDecimal cost(Long userId, OffsetDateTime from, OffsetDateTime to, Long agentDbId) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal cost = usageMapper.costInRange(userId, from, to, agentDbId);
            return cost == null ? BigDecimal.ZERO : cost;
        } catch (Exception ex) {
            // 统计失败不该拦住用户对话
            log.warn("额度成本统计失败 userId={} agentId={}: {}", userId, agentDbId, ex.toString());
            return BigDecimal.ZERO;
        }
    }

    private static OffsetDateTime startOfDay() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
    }

    /** 明天 00:00（作为「今天」的**开区间上界**，不受时钟偏差影响）。 */
    private static OffsetDateTime startOfNextDay() {
        return LocalDate.now(ZoneId.systemDefault())
                .plusDays(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toOffsetDateTime();
    }

    private static OffsetDateTime startOfMonth() {
        return LocalDate.now(ZoneId.systemDefault())
                .withDayOfMonth(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toOffsetDateTime();
    }

    /** 下月 1 日 00:00（作为「本月」的开区间上界）。 */
    private static OffsetDateTime startOfNextMonth() {
        return LocalDate.now(ZoneId.systemDefault())
                .withDayOfMonth(1)
                .plusMonths(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toOffsetDateTime();
    }
}
