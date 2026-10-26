package com.mengde.clouddrive.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 回收站保留策略。
 *
 * <p>保留天数设为 0 或负数即关闭自动清理——
 * 演示环境里有时希望"删掉的东西一直留着"，需要一个不改代码就能关掉的开关。</p>
 */
@ConfigurationProperties(prefix = "clouddrive.trash")
public class TrashProperties {

    /** 回收站保留天数，超过则自动彻底删除。<= 0 表示关闭自动清理。 */
    private int retentionDays = 30;

    /** 清理任务的 cron 表达式，默认每天凌晨 3:30。 */
    private String cleanupCron = "0 30 3 * * *";

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public String getCleanupCron() {
        return cleanupCron;
    }

    public void setCleanupCron(String cleanupCron) {
        this.cleanupCron = cleanupCron;
    }
}
