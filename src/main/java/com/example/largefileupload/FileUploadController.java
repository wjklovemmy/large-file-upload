package com.example.largefileupload;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/upload")
@CrossOrigin(origins = "*") // 允许前端跨域访问，可以更换为具体的域
public class FileUploadController {
    @Autowired
    private FileUploadService fileUploadService;

    /**
     * 检查文件状态
     *
     * @param request
     * @return
     */
    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> checkFile(@RequestBody CheckRequest request){
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> sizeCheckResult = fileUploadService.checkFile(request.getFileSize(), request.getFileName(), request.getFileMimeType());
        if (!(boolean) sizeCheckResult.get("isLegal")) {
            result.put("code", 413);
            result.put("message", "File validation is invalid");
            return ResponseEntity.ok(result);
        }
        String saveFileName = request.getFileName() + "_" + request.getMd5();
        try {
            // 检查文件是否已经上传完整存在（秒传）
            boolean exists = fileUploadService.isFileComplete(saveFileName, request.getFileSize());
            // 获取已经上传的分片列表（断点续传）
            List<Integer> uploadedChunks = fileUploadService.getUploadedChunks(saveFileName, request.getTotalChunks());
            Map<String, Object> data = new HashMap<>();
            data.put("exists", exists);
            data.put("uploadedChunks", uploadedChunks);
            Map<String, Object> safeData = Collections.unmodifiableMap(data);
            result.put("code", 200);
            result.put("data", safeData);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 上传分片
     * @param file
     * @param md5
     * @param fileName
     * @param chunkIndex
     * @param totalChunks
     * @return
     */
    @PostMapping("/chunk")
    public ResponseEntity<Map<String, Object>> uploadChunk(
            @RequestParam("file") MultipartFile file,
            @RequestParam String md5,
            @RequestParam String fileName,
            @RequestParam int chunkIndex,
            @RequestParam int totalChunks){
        Map<String, Object> result = new HashMap<>();
        try {
            fileUploadService.saveChunk(file, md5, fileName, chunkIndex, totalChunks);
            result.put("code", 200);
            result.put("message", "chunk upload successfully");
        } catch (IOException e) {
            result.put("code", 500);
            result.put("message", "Failed to save chunk:" + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 合并分片
     *
     * @param request
     * @return
     */
    @PostMapping("/merge")
    public ResponseEntity<Map<String, Object>> mergechunks(@RequestBody MergeRequest request){
        Map<String, Object> result = new HashMap<>();
        try {
            fileUploadService.mergeChunks(request.getMd5(), request.getFileName(), request.getFileSize(), request.getTotalChunks());
            result.put("code", 200);
            result.put("message", "File merged successfully");
        } catch (IOException e) {
            result.put("code", 500);
            result.put("message", "Failed to merge file:" + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }
}
