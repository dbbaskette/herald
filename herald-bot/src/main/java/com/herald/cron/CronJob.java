package com.herald.cron;

import java.time.LocalDateTime;

public record CronJob(Integer id, String name, String schedule, String prompt,
                      LocalDateTime lastRun, boolean enabled) {
}
