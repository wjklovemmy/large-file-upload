package com.example.largefileupload;

import lombok.Data;

@Data
public class CheckRequest {
    /**
     * 文件的md5值
     */
    private String md5;
    /**
     * 文件名称
     */
    private String fileName;
    /**
     * 文件的MimeType类型
     */
    private String fileMimeType;
    /**
     * 文件分片总数
     */
    private int totalChunks;
    /**
     * 文件大小
     */
    private long fileSize;
}
