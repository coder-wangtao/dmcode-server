package com.wangtao.domain.agent.service.coding.verification;

import java.nio.file.Path;

public interface VerificationStrategy {

    boolean supports(Path projectRoot);

    VerificationPlan plan(Path projectRoot);
}
