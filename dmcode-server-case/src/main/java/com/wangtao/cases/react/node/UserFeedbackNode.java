package com.wangtao.cases.react.node;

import com.wangtao.api.dto.ChatRequestDTO;
import com.wangtao.api.dto.ReActEventDTO;
import com.wangtao.api.dto.ReActResultDTO;
import com.wangtao.cases.react.AbstractAIAgentReActSupport;
import com.wangtao.cases.react.factory.DefaultReActFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * ReAct 用户反馈节点（结果发送 + 清理）
 *
 * <p>职责：
 * 1. 构建最终 ReActResultDTO
 * 2. 发送 done SSE 事件
 * 3. 关闭 Emitter
 * 4. 清理 ThreadLocal 上下文
 *
 * <p>这是 ReAct 循环链路的终点，负责：
 * - 将累积的响应文本封装为最终结果
 * - 通过 SSE 发送 done 事件通知前端
 * - 清理终端会话绑定
 *
 * 
 */
@Slf4j
@Component("reactUserFeedbackNode")
public class UserFeedbackNode extends AbstractAIAgentReActSupport {

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct UserFeedbackNode - 发送最终结果");

        ResponseBodyEmitter emitter = dynamicContext.getEmitter();

        try {
            // 1. 构建最终结果
            ReActResultDTO result = buildFinalResult(dynamicContext);

            // 2. 仅在连接仍然有效时发送 done 事件
            if (!dynamicContext.isCancelled()) {
                sendDoneEvent(emitter, result, dynamicContext);
            }

            // 3. 关闭 emitter（忽略已关闭异常）
            try {
                emitter.complete();
            } catch (Exception alreadyCompleted) {
                log.debug("emitter 已完成，忽略关闭异常");
            }

            log.info("ReAct 完成 - 步数: {}, 工具调用: {}, 停止原因: {}",
                    result.getTotalSteps(),
                    result.getTotalToolCalls(),
                    result.getStopReason() != null ? result.getStopReason() : "completed");

            return result;

        } catch (Exception e) {
            log.error("ReAct UserFeedbackNode 发送失败", e);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
            throw e;
        } finally {
            // 4. 清理上下文
            cleanup(dynamicContext);
        }
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        // 终点节点，无后续路由
        return StrategyHandler.DEFAULT;
    }

    // ═══════════════════════════════════════════════════════════════
    //  构建最终结果
    // ═══════════════════════════════════════════════════════════════

    /**
     * 构建最终结果 DTO
     */
    private ReActResultDTO buildFinalResult(DefaultReActFactory.DynamicContext dynamicContext) {
        String assistantText = dynamicContext.getAssistantContent() != null
                ? dynamicContext.getAssistantContent().toString()
                : "";
        int boundary = Math.min(
                Math.max(0, dynamicContext.getAssistantContentBoundary()),
                assistantText.length()
        );
        String fullText = assistantText.substring(boundary);
        boolean hasFileMutation = resultContainsFileMutation(dynamicContext);

        fullText = removeRepeatedAnswer(fullText);
        // 全量 Markdown 规范化（标题/列表/表格断行修复 + 加粗空格 + 空行压缩）
        String beforeNormalize = fullText;
        fullText = com.wangtao.cases.react.util.MarkdownNormalizer.normalize(fullText);
        if (fullText == null || fullText.isBlank()) {
            String reason = dynamicContext.getStopReason();
            if ("error".equals(reason)) {
                // 错误终止时给出简要准确的报错信息（来自 DynamicContext.errorMessage），便于用户定位操作
                fullText = buildErrorNotice(dynamicContext.getErrorMessage());
            } else {
                fullText = buildEmptyResultNotice(reason);
            }
        } else if ("error".equals(dynamicContext.getStopReason())) {
            // 已有部分回答但以错误终止：在结尾追加错误说明
            String errorNotice = buildErrorNotice(dynamicContext.getErrorMessage());
            if (!errorNotice.isBlank() && !fullText.contains(errorNotice)) {
                fullText = fullText + "\n\n---\n⚠️ " + errorNotice;
            }
        }
        if (!hasFileMutation) {
            fullText = removeReadOnlyChangeSummary(fullText);
            // 兜底：本次没有任何工具调用时，删除模型自行附加的「无需调用工具/未修改文件」类声明
            if (dynamicContext.getStep() == 0 || totalToolCalls(dynamicContext) == 0) {
                fullText = removeNoToolDisclaimer(fullText);
            }
        }
        log.info("[normalize] beforeLen={}, afterLen={}, before={}, after={}",
                beforeNormalize.length(), fullText.length(),
                beforeNormalize.length() > 80 ? beforeNormalize.substring(0, 80) : beforeNormalize,
                fullText.length() > 80 ? fullText.substring(0, 80) : fullText);

        String stopReason = dynamicContext.getStopReason();
        if (stopReason == null) {
            stopReason = "completed";
        }

        return ReActResultDTO.builder()
                .content(fullText)
                .totalSteps(dynamicContext.getStep())
                .totalToolCalls(dynamicContext.getResult() != null ? dynamicContext.getResult().getTotalToolCalls() : 0)
                .maxStepsReached("max_steps".equals(stopReason))
                .userStopped("user_stop".equals(stopReason))
                .idleTimeout("idle_timeout".equals(stopReason))
                .stopReason(stopReason)
                .toolCalls(dynamicContext.getCurrentToolCalls())
                .toolResults(dynamicContext.getCurrentToolResults())
                .error("error".equals(stopReason) ? dynamicContext.getErrorMessage() : null)
                .build();
    }

    private String removeRepeatedAnswer(String text) {
        if (text == null || text.length() < 80) {
            return text;
        }

        String normalized = text.replace("\r\n", "\n").trim();
        String[] lines = normalized.split("\n", -1);
            int meaningfulLines = 0;
            int meaningfulCharacters = 0;
            boolean repeated = true;
        for (int blockLength = lines.length / 2; blockLength >= 2; blockLength--) {
            for (int previousStart = lines.length - blockLength * 2; previousStart >= 0; previousStart--) {
                meaningfulLines = 0;
                meaningfulCharacters = 0;
                repeated = true;
                for (int index = 0; index < blockLength; index++) {
                    String previousLine = lines[previousStart + index].trim();
                    String currentLine = lines[lines.length - blockLength + index].trim();
                    if (!previousLine.equals(currentLine)) {
                        repeated = false;
                        break;
                    }
                    if (!previousLine.isEmpty()) {
                        meaningfulLines++;
                        meaningfulCharacters += previousLine.length();
                    }
                }

                if (repeated && meaningfulLines >= 2 && meaningfulCharacters >= 80) {
                    return String.join("\n", java.util.Arrays.copyOfRange(lines, 0, lines.length - blockLength)).trim();
                }
            }
        }
        return text;
    }

    private String buildEmptyResultNotice(String stopReason) {
        return switch (stopReason == null ? "completed" : stopReason) {
            case "diminishing_returns" -> "已自动停止：连续多轮执行了相同的工具序列，为避免继续循环，请补充信息或调整任务后继续。";
            case "idle_timeout" -> "已自动停止：执行空闲超时。";
            case "max_steps" -> "已停止：已达到最大执行步数。";
            case "max_tool_calls" -> "已停止：已达到最大工具调用次数。";
            case "user_stop" -> "已按你的操作停止执行。";
            case "error" -> "执行异常终止，请查看错误详情或重试。";
            default -> "已完成当前执行，但模型没有返回最终总结文本。";
        };
    }

    /**
     * 错误终止时构建简要、可操作的报错信息。
     * 从 errorMessage 中提取关键错误（HTTP 状态码 / 连接异常等），不倾倒完整堆栈。
     */
    private String buildErrorNotice(String errorMessage) {
        String brief = extractBriefError(errorMessage);
        StringBuilder sb = new StringBuilder("执行异常终止");
        if (!brief.isBlank()) {
            sb.append("：").append(brief);
        }
        sb.append("\n可尝试：稍等片刻后重试；若持续失败，请在设置里检查模型配置（API 地址 / Key / 额度）。");
        return sb.toString();
    }

    /**
     * 从原始错误信息提取一句话摘要：
     * - 优先识别 HTTP 状态码（429 限流 / 401 鉴权 / 5xx 服务端等）并给出对应操作建议
     * - 连接类异常给出网络/地址提示
     * - 兜底截取首行异常消息，过滤 Java 堆栈
     */
    private String extractBriefError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "";
        }
        String msg = errorMessage.replace("\r\n", "\n").trim();
        String lower = msg.toLowerCase();

        // 1. HTTP 状态码类（含 429 Too Many Requests / 401 Unauthorized 等）
        java.util.regex.Matcher statusMatcher = java.util.regex.Pattern
                .compile("(\\d{3})\\s+(Too Many Requests|Unauthorized|Forbidden|Not Found|Bad Request|Internal Server Error|Bad Gateway|Service Unavailable|Payment Required|Request Timeout)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(msg);
        if (statusMatcher.find()) {
            String code = statusMatcher.group(1);
            String phrase = statusMatcher.group(2).toLowerCase();
            String target = extractApiHost(msg);
            String withTarget = target.isBlank() ? "" : "（" + target + "）";
            return switch (code) {
                case "429" -> String.format("模型接口限流%s：请求过于频繁或额度不足，请稍后重试，或检查模型服务额度。", withTarget);
                case "401", "403" -> String.format("模型接口鉴权失败%s：请检查设置里的 API Key 是否正确/有效。", withTarget);
                case "402" -> String.format("模型接口余额不足%s：请为该模型账户充值或更换模型。", withTarget);
                case "404" -> String.format("模型接口地址不存在%s：请检查设置里的 Base URL 与模型 ID 是否正确。", withTarget);
                case "408", "timeout" -> String.format("模型接口请求超时%s：请稍后重试。", withTarget);
                default -> {
                    if (code.startsWith("5")) {
                        yield String.format("模型服务端错误%s（%s）：请稍后重试，或检查该模型服务状态。", withTarget, phrase);
                    }
                    yield String.format("模型接口返回 %s %s%s。", code, phrase, withTarget);
                }
            };
        }
        if (lower.contains("rate limited") || lower.contains("too many requests")) {
            return "模型接口限流：请求过于频繁或额度不足，请稍后重试。";
        }

        // 2. 连接类异常
        if (lower.contains("connection refused")) {
            String target = extractApiHost(msg);
            return String.format("无法连接模型接口%s：服务未启动或地址不可达，请检查设置里的 API 地址。", target.isBlank() ? "" : "（" + target + "）");
        }
        if (lower.contains("connect timed out") || lower.contains("connection timed out")) {
            return "连接模型接口超时：请检查网络或 API 地址是否可达。";
        }
        if (lower.contains("unknownhost") || lower.contains("no route to host")) {
            return "模型接口地址无法解析：请检查设置里的 API 地址。";
        }

        // 3. 兜底：取首行有意义的异常消息（跳过堆栈 at 行 / Caused by 前缀链）
        for (String line : msg.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("at ")) continue;
            trimmed = trimmed.replaceFirst("^(?:java\\.lang\\.)?\\w+(?:\\.\\w+)*:\\s*", "");
            if (trimmed.isBlank()) continue;
            return trimmed.length() > 160 ? trimmed.substring(0, 160) + "…" : trimmed;
        }
        return "";
    }

    /**
     * 从错误信息中提取 API 主机（如 https://api.taotoken.net ），便于用户定位是哪个模型服务出错。
     */
    private String extractApiHost(String msg) {
        java.util.regex.Matcher urlMatcher = java.util.regex.Pattern
                .compile("https?://[\\w.-]+")
                .matcher(msg);
        return urlMatcher.find() ? urlMatcher.group() : "";
    }

    private boolean resultContainsFileMutation(DefaultReActFactory.DynamicContext dynamicContext) {
        if (dynamicContext.getCurrentToolResults() == null) return false;
        return dynamicContext.getCurrentToolResults().stream()
                .map(result -> String.valueOf(result.getOrDefault("name", result.getOrDefault("toolName", ""))))
                .map(name -> name.toLowerCase())
                .anyMatch(name -> name.contains("write")
                        || name.contains("create")
                        || name.contains("delete")
                        || name.contains("remove")
                        || name.contains("edit")
                        || name.contains("rollback"));
    }

    private int totalToolCalls(DefaultReActFactory.DynamicContext dynamicContext) {
        Integer total = dynamicContext.getResult() != null ? dynamicContext.getResult().getTotalToolCalls() : null;
        if (total != null) {
            return total;
        }
        return dynamicContext.getCurrentToolCalls() != null ? dynamicContext.getCurrentToolCalls().size() : 0;
    }

    /**
     * 删除模型在纯问答（未调用任何工具）场景下自行附加的免责声明，
     * 如「无需调用工具，也未修改任何文件。」「（未调用工具）」「本次未修改任何文件。」等。
     */
    private String removeNoToolDisclaimer(String text) {
        if (text == null || text.isBlank()) return text;
        return text
                .replaceAll("(?m)^\\s*[（(]?\\s*(?:无需|无需调用|没有调用|未调用)(?:调用)?工具[^\\n。！？]*[）)]?[。！]?\\s*$", "")
                .replaceAll("(?m)^\\s*[（(]?\\s*(?:本次)?(?:未修改|没有修改|未变更|没有变更)(?:任何)?文件[^\\n。！？]*[）)]?[。！]?\\s*$", "")
                .replaceAll("(?m)^\\s*[（(]?\\s*无需调用工具[，,、\\s]*(?:也)?(?:未修改|没有修改)(?:任何)?文件[^\\n。！？]*[）)]?[。！]?\\s*$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String removeReadOnlyChangeSummary(String text) {
        if (text == null || text.isBlank()) return "";
        String[] lines = text.split("\\r?\\n");
        java.util.List<String> kept = new java.util.ArrayList<>();
        boolean removing = false;
        for (String line : lines) {
            String normalized = line.trim();
            if (normalized.startsWith("📋") && (normalized.contains("改动摘要") || normalized.contains("变更摘要"))) {
                removing = true;
                continue;
            }
            if (removing && (normalized.startsWith("#") || normalized.startsWith("📋"))) {
                removing = false;
            }
            if (!removing) kept.add(line);
        }
        return String.join("\n", kept).replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * 清理上下文资源
     */
    private void cleanup(DefaultReActFactory.DynamicContext dynamicContext) {
        try {
            // 清除终端会话绑定
            String sessionId = dynamicContext.getSessionId();
            if (sessionId != null) {
                unbindTerminalSession(sessionId);
                com.wangtao.domain.agent.service.armory.matter.tools.SshTerminalSessionRegistry.unbind(sessionId);
                com.wangtao.domain.agent.service.armory.matter.tools.SshExecuteAdkTool.clearTerminalSession(sessionId);
            }

            // 清除 ThreadLocal
            clearCurrentTerminalSession();

            log.debug("ReAct 上下文清理完成 sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("ReAct 上下文清理异常: {}", e.getMessage());
        }
    }

}
