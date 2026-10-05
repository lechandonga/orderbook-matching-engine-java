package com.github.lechandonga.orderbookmatchingenginejava.tests.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在测试日志中打印业务覆盖摘要：
 *   [业务覆盖] == 用例组：xxx ==
 *   [业务覆盖]   ✔ 用例x.x ...（或 ✘ 及失败原因）
 * 最后汇总各业务用例组的通过/失败数量。
 */
public class BusinessCoverageLogger implements TestExecutionListener {

    private static final Logger log = LoggerFactory.getLogger("BUSINESS-COVERAGE");

    private final Map<String, GroupStats> groups = new LinkedHashMap<>();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        log.info("[业务覆盖] ================= 撮合引擎业务用例开始执行 ================");
    }

    @Override
    public void executionStarted(TestIdentifier identifier) {
        if (identifier.isTest()) {
            String group = groupName(identifier).orElse("其他");
            groups.computeIfAbsent(group, name -> {
                log.info("[业务覆盖] == 用例组：{} ==", name);
                return new GroupStats();
            });
        }
    }

    @Override
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        if (!identifier.isTest()) {
            return;
        }
        String group = groupName(identifier).orElse("其他");
        GroupStats stats = groups.computeIfAbsent(group, ignored -> new GroupStats());
        String name = identifier.getDisplayName();
        if (result.getStatus() == TestExecutionResult.Status.SUCCESSFUL) {
            stats.passed++;
            log.info("[业务覆盖]   ✔ 通过 | {}", name);
        } else {
            stats.failed++;
            String reason = result.getThrowable().map(Throwable::getMessage).orElse("无异常信息");
            log.error("[业务覆盖]   ✘ 失败 | {} | 原因: {}", name, reason);
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        log.info("[业务覆盖] ---------------- 业务覆盖汇总 ----------------");
        int totalPass = 0;
        int totalFail = 0;
        for (Map.Entry<String, GroupStats> entry : groups.entrySet()) {
            GroupStats stats = entry.getValue();
            totalPass += stats.passed;
            totalFail += stats.failed;
            log.info("[业务覆盖] {} : 通过 {} 个, 失败 {} 个",
                    entry.getKey(), stats.passed, stats.failed);
        }
        log.info("[业务覆盖] 合计：用例 {} 个，通过 {}，失败 {}",
                totalPass + totalFail, totalPass, totalFail);
        log.info("[业务覆盖] ================ 撮合引擎业务用例执行结束 ================");
    }

    private java.util.Optional<String> groupName(TestIdentifier identifier) {
        return identifier.getSource()
                .filter(source -> source instanceof org.junit.platform.engine.support.descriptor.MethodSource)
                .map(source -> ((org.junit.platform.engine.support.descriptor.MethodSource) source)
                        .getJavaClass())
                .filter(clazz -> clazz.isAnnotationPresent(DisplayName.class))
                .map(clazz -> clazz.getAnnotation(DisplayName.class).value());
    }

    private static final class GroupStats {
        private int passed;
        private int failed;
    }
}
