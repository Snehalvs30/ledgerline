package com.ledgerline.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled jobs, such as the reconciliation check. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}