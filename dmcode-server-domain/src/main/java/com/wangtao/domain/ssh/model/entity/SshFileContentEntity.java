package com.wangtao.domain.ssh.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件内容实体
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshFileContentEntity {

    private String path;
    private String name;
    private String charset;
    private Long size;
    private boolean binary;
    private boolean truncated;
    /** 分片读取时的起始偏移量 */
    private Long offset;
    private String content;

    /**
     * 二进制文件的解析类型标识（如 "java-class"）。
     * 非空表示 content 为解析后的结构化文本视图（只读）。
     */
    private String parsedType;

}
