package com.wangtao.domain.agent.service.coding.verification;

import java.util.List;

public record VerificationPlan(String projectType, List<String> commands, String reason) {
}
