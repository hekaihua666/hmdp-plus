package com.hmdp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 只启用Spring调度，后台任务由容器管理生命周期，不再手工启动Stream消费线程。 */
@Configuration
@EnableScheduling
public class SeckillMqConfiguration {}
