package com.wangtao.domain.agent.service.coding.verification;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
class GradleVerificationStrategy implements VerificationStrategy {

    @Override
    public boolean supports(Path projectRoot) {
        return Files.exists(projectRoot.resolve("build.gradle"))
                || Files.exists(projectRoot.resolve("build.gradle.kts"));
    }

    @Override
    public VerificationPlan plan(Path projectRoot) {
        return new VerificationPlan("gradle", List.of("./gradlew", "test"), "检测到 Gradle 构建文件");
    }
}
