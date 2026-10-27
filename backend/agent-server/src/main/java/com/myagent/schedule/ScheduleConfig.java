package com.myagent.schedule;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开 Spring 定时调度（{@code @Scheduled}）。
 *
 * <p>只用它做"每 10 秒 tick 一次"的触发；真正的分布式互斥由
 * {@code SELECT … FOR UPDATE SKIP LOCKED} 保证，不依赖单实例。
 */
@Configuration
@EnableScheduling
public class ScheduleConfig {}
