package com.wangtao.domain.agent.service.coding.verification;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
public class VerificationEngine {

    private final List<VerificationStrategy> strategies;

    public VerificationEngine(List<VerificationStrategy> strategies) {
        this.strategies = strategies.stream()
                .sorted(java.util.Comparator.comparingInt(strategy -> priority(strategy)))
                .toList();
    }

    public VerificationPlan plan(Path projectRoot) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(projectRoot))
                .map(strategy -> strategy.plan(projectRoot))
                .findFirst()
                .orElse(new VerificationPlan("unknown", List.of(), "未识别到项目类型"));
    }

    private int priority(VerificationStrategy strategy) {
        if (strategy instanceof MavenVerificationStrategy) return 1;
        if (strategy instanceof GradleVerificationStrategy) return 2;
        if (strategy instanceof NpmVerificationStrategy) return 3;
        return 99;
    }
}
