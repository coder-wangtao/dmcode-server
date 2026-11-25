package com.wangtao.trigger.http;

import com.wangtao.api.dto.SshFileContentResponseDTO;
import com.wangtao.api.response.Response;
import com.wangtao.domain.ssh.service.file.ClassFileParser;
import com.wangtao.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Map;

/**
 * 通用文件解析接口（非 SSH 链路）。
 * <p>
 * 供桌面端本地文件预览使用：前端读取本地字节后 base64 上传，
 * 服务端解析为结构化文本视图返回。当前支持 .class 字节码文件。
 */
//TODO: 前端读取本地 .class 文件 → 转换成 Base64 → 发送给后端 → 后端解析字节码 → 返回可读的 Java 类结构文本。
@Slf4j
@RestController
@RequestMapping("/api/v1/file")
@CrossOrigin(origins = "*")
public class FileParseController {

    /** 解析允许的最大字节数（base64 解码前约 21MB） */
    private static final int MAX_PARSE_BYTES = 16 * 1024 * 1024;


    @RequestMapping(value = "parse-class", method = RequestMethod.POST)
    public Response<SshFileContentResponseDTO> parseClass(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "unknown.class");
        String contentBase64 = body.getOrDefault("contentBase64", "");
        try {
            if (contentBase64.isBlank()) {
                return Response.<SshFileContentResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info("内容不能为空").build();
            }
            byte[] bytes = Base64.getDecoder().decode(contentBase64);
            if (bytes.length > MAX_PARSE_BYTES) {
                return Response.<SshFileContentResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info("文件过大，暂不支持解析（上限 16MB）").build();
            }
            if (!ClassFileParser.isClassFile(bytes)) {
                return Response.<SshFileContentResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info("不是合法的 class 文件").build();
            }
            String text = ClassFileParser.parse(bytes, name);
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                    .data(SshFileContentResponseDTO.builder()
                            .path(name).name(name).charset("UTF-8")
                            .size((long) bytes.length)
                            .binary(false).truncated(false)
                            .content(text).parsedType("java-class").build())
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("class 文件解析异常 name={}", name, e);
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info("class 文件解析失败: " + e.getMessage()).build();
        }
    }
}
