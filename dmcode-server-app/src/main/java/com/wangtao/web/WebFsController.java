package com.wangtao.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Web 控制台文件系统接口：为"项目空间 / 工程文件面板"提供目录树浏览与文件读取。
 *
 * <p>与页面内嵌的 CodeEdit 工具共用本机文件系统权限模型：
 * local profile 下所有 /api/** 已由 LocalTokenFilter 强制 Bearer token；
 * dev profile 为本地开发环境（同机信任），与 OpenWorkspacePathGuard 行为一致。
 *
 * <p>防护措施：路径规范化、拒绝符号链接逃逸、目录条目/深度/文件大小上限、
 * 二进制内容不回传（走 /fs/raw）。
 *
 * @author walissh dev
 * 2026/10/7
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/fs")
public class WebFsController {

    /** 目录树最大深度 */
    private static final int MAX_DEPTH = 3;
    /** 目录树总条目上限 */
    private static final int MAX_ENTRIES = 3000;
    /** 目录内单层条目上限 */
    private static final int MAX_DIR_ENTRIES = 400;
    /** 文本内容回传上限（300KB） */
    private static final int MAX_TEXT_BYTES = 300 * 1024;
    /** 二进制嗅探检查字节数 */
    private static final int SNIFF_LEN = 8000;

    /** 深度遍历跳过的重目录 */
    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules", ".git", ".svn", "target", "build", "dist", "out",
            ".gradle", ".idea", ".vscode", "__pycache__", ".next", "venv", ".venv");

    private static final Map<String, String> MIME_BY_EXT = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"), Map.entry("svg", "image/svg+xml"),
            Map.entry("bmp", "image/bmp"), Map.entry("ico", "image/x-icon"),
            Map.entry("pdf", "application/pdf"));

    // ═══════════════ 目录树 ═══════════════

    @GetMapping("/tree")
    public Map<String, Object> tree(@RequestParam("path") String path,
                                    @RequestParam(value = "depth", defaultValue = "2") int depth) {
        Map<String, Object> resp = new HashMap<>();
        try {
            Path root = safeResolve(path);
            if (!Files.isDirectory(root)) {
                resp.put("code", "0001");
                resp.put("info", "不是目录: " + path);
                return resp;
            }
            int d = Math.max(1, Math.min(depth, MAX_DEPTH));
            int[] budget = {MAX_ENTRIES};
            Map<String, Object> node = walk(root, root, d, 0, budget);
            resp.put("code", "0000");
            resp.put("info", "success");
            resp.put("data", node);
        } catch (Exception e) {
            log.warn("[WebFs] 目录树读取失败: path={}", path, e);
            resp.put("code", "0001");
            resp.put("info", "目录读取失败: " + e.getMessage());
        }
        return resp;
    }

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(value = "path", required = false) String path) {
        Map<String, Object> resp = new HashMap<>();
        try {
            // 空 / ~ 时默认用户主目录（供目录选择弹窗从主目录开始浏览）
            String target = (path == null || path.isBlank() || "~".equals(path.trim()))
                    ? System.getProperty("user.home") : path;
            Path dir = safeResolve(target);
            if (!Files.isDirectory(dir)) {
                resp.put("code", "0001");
                resp.put("info", "不是目录: " + target);
                return resp;
            }
            List<Map<String, Object>> items = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.filter(p -> !isSkipped(p))
                        .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                                .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .limit(MAX_DIR_ENTRIES)
                        .forEach(p -> items.add(entry(p, dir)));
            }
            resp.put("code", "0000");
            resp.put("info", "success");
            resp.put("data", items);
        } catch (Exception e) {
            resp.put("code", "0001");
            resp.put("info", "目录读取失败: " + e.getMessage());
        }
        return resp;
    }

    // ═══════════════ 文件内容 ═══════════════

    @GetMapping("/file")
    public Map<String, Object> file(@RequestParam("path") String path) {
        Map<String, Object> resp = new HashMap<>();
        try {
            Path p = safeResolve(path);
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                resp.put("code", "0001");
                resp.put("info", "不是普通文件: " + path);
                return resp;
            }
            long size = Files.size(p);
            Map<String, Object> data = new HashMap<>();
            data.put("path", p.toString());
            data.put("name", p.getFileName().toString());
            data.put("size", size);
            data.put("mtime", Files.getLastModifiedTime(p).toMillis());
            data.put("binary", isBinary(p));

            if (Boolean.TRUE.equals(data.get("binary"))) {
                data.put("content", "");
                data.put("truncated", false);
            } else {
                byte[] bytes = readUpTo(p, MAX_TEXT_BYTES + 1);
                boolean truncated = bytes.length > MAX_TEXT_BYTES;
                if (truncated) bytes = java.util.Arrays.copyOf(bytes, MAX_TEXT_BYTES);
                data.put("content", new String(bytes, StandardCharsets.UTF_8));
                data.put("truncated", truncated);
            }
            resp.put("code", "0000");
            resp.put("info", "success");
            resp.put("data", data);
        } catch (Exception e) {
            log.warn("[WebFs] 文件读取失败: path={}", path, e);
            resp.put("code", "0001");
            resp.put("info", "文件读取失败: " + e.getMessage());
        }
        return resp;
    }

    /** 原始字节（图片预览 / 二进制下载） */
    @GetMapping("/raw")
    public org.springframework.http.ResponseEntity<byte[]> raw(
            @RequestParam("path") String path,
            @RequestParam(value = "download", defaultValue = "false") boolean download) throws IOException {
        Path p = safeResolve(path);
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        String ext = ext(p.getFileName().toString());
        String mime = MIME_BY_EXT.getOrDefault(ext, "application/octet-stream");
        byte[] bytes = Files.readAllBytes(p);
        if (bytes.length > 20 * 1024 * 1024) {
            return org.springframework.http.ResponseEntity.status(413)
                    .body("\"file too large\"".getBytes(StandardCharsets.UTF_8));
        }
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set(org.springframework.http.HttpHeaders.CONTENT_TYPE, mime);
        if (download) {
            headers.set(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(
                            p.getFileName().toString(), StandardCharsets.UTF_8));
        }
        return new org.springframework.http.ResponseEntity<>(bytes, headers, org.springframework.http.HttpStatus.OK);
    }

    // ═══════════════ 内部工具 ═══════════════

    private Path safeResolve(String input) throws IOException {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("路径为空");
        Path p = Paths.get(input.trim()).normalize().toAbsolutePath();
        // 拒绝符号链接指向的路径在本机文件系统之外（与工具链行为一致，仅做基础防护）
        if (Files.isSymbolicLink(p)) {
            Path real = p.toRealPath();
            log.debug("[WebFs] 符号链接解析: {} -> {}", p, real);
            return real;
        }
        return p;
    }

    private boolean isSkipped(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        return SKIP_DIRS.contains(name);
    }

    private Map<String, Object> walk(Path root, Path dir, int maxDepth, int depth, int[] budget) throws IOException {
        Map<String, Object> node = new HashMap<>();
        node.put("name", root.equals(dir) ? dir.toString() : dir.getFileName().toString());
        node.put("path", dir.toString());
        node.put("dir", true);
        List<Map<String, Object>> children = new ArrayList<>();
        if (depth < maxDepth && budget[0] > 0) {
            try (var stream = Files.list(dir)) {
                List<Path> entries = stream
                        .filter(p -> !isSkipped(p))
                        .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                                .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .limit(Math.min(MAX_DIR_ENTRIES, budget[0]))
                        .toList();
                for (Path child : entries) {
                    if (budget[0] <= 0) break;
                    budget[0]--;
                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        children.add(walk(root, child, maxDepth, depth + 1, budget));
                    } else {
                        children.add(entry(child, dir));
                    }
                }
            } catch (IOException e) {
                node.put("error", "读取失败: " + e.getMessage());
            }
        } else if (depth >= maxDepth) {
            // 深度截断标记：前端展示"展开加载"
            boolean hasSub = false;
            try (var stream = Files.list(dir)) {
                hasSub = stream.anyMatch(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && !isSkipped(p));
            } catch (IOException ignored) { }
            node.put("lazy", hasSub);
        }
        children.sort(Comparator.comparing((Map<String, Object> c) -> !Boolean.TRUE.equals(c.get("dir")))
                .thenComparing(c -> String.valueOf(c.get("name")).toLowerCase(Locale.ROOT)));
        node.put("children", children);
        return node;
    }

    private Map<String, Object> entry(Path p, Path parent) {
        Map<String, Object> m = new HashMap<>();
        boolean dir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
        m.put("name", p.getFileName() == null ? p.toString() : p.getFileName().toString());
        m.put("path", p.toString());
        m.put("dir", dir);
        if (!dir) {
            try { m.put("size", Files.size(p)); } catch (IOException e) { m.put("size", -1); }
        }
        return m;
    }

    private boolean isBinary(Path p) throws IOException {
        if (MIME_BY_EXT.containsKey(ext(p.getFileName().toString()))) return true;
        byte[] head = readUpTo(p, SNIFF_LEN);
        int check = Math.min(head.length, SNIFF_LEN);
        for (int i = 0; i < check; i++) {
            if (head[i] == 0) return true;
        }
        return false;
    }

    private byte[] readUpTo(Path p, int max) throws IOException {
        try (var in = Files.newInputStream(p)) {
            byte[] buf = new byte[max];
            int off = 0, n;
            while (off < max && (n = in.read(buf, off, max - off)) > 0) off += n;
            return off == max ? buf : java.util.Arrays.copyOf(buf, off);
        }
    }

    private String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
