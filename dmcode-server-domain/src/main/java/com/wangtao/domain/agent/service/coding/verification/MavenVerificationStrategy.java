package com.wangtao.domain.agent.service.coding.verification;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
class MavenVerificationStrategy implements VerificationStrategy {

    @Override
    public boolean supports(Path projectRoot) {
        return Files.exists(projectRoot.resolve("pom.xml"));
    }

    @Override
    public VerificationPlan plan(Path projectRoot) {
        return new VerificationPlan("maven", List.of("mvn", "-q", "test"), "检测到 pom.xml");
    }
}
