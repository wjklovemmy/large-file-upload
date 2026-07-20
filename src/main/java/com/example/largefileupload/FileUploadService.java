package com.example.largefileupload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class FileUploadService {
    private String uploadDir;
    private String tempDir;
    private int bufferSize;
    private Long maxAllowFileSize;
    private List<String> allowedMimeList;
    private List<String> forbiddenExtensionList;

    public FileUploadService(
            @Value("${file.upload.dir}") String uploadDir,
            @Value("${file.upload.temp-dir}") String tempDir,
            @Value("${file.upload.buffer-size}") int bufferSize,
            @Value("${file.upload.max-allow-file-size}") long maxAllowFileSize,
            @Value("${file.upload.allowed-mime-list") List<String> allowedMimeList,
            @Value("${file.upload.forbidden-extension-list}") List<String> forbiddenExtensionList
    ){
        if (uploadDir == null || uploadDir.isEmpty()) {
            throw new IllegalArgumentException("file.upload.dir 不能为空");
        }
        if (tempDir == null || tempDir.isEmpty()) {
            throw new IllegalArgumentException("file.upload.temp-dir 不能为空");
        }
        if (maxAllowFileSize <= 0 && maxAllowFileSize != -1) {
            throw new IllegalArgumentException("file.upload.max-allow-file-size 配置不合法");
        }

        this.uploadDir = uploadDir;
        this.tempDir = tempDir;
        this.bufferSize = bufferSize;
        this.maxAllowFileSize = maxAllowFileSize;
        this.allowedMimeList = allowedMimeList;
        this.forbiddenExtensionList = forbiddenExtensionList;
    }

    // 使用细粒度锁管理并发，在mergeChunks函数中要使用该锁
    private final ConcurrentHashMap<String, ReentrantLock> mergeLocks = new ConcurrentHashMap<>();

    /**
     * 文件校验
     *
     * @param fileSize
     * @param fileName
     * @param fileMimeType
     * @return
     */
    public Map<String, Object> checkFile(long fileSize, String fileName, String fileMimeType){
        Map<String, Object> result = new HashMap<>();
        // 原有文件大小校验（对其配置规则）
        if (maxAllowFileSize != -1 && fileSize > maxAllowFileSize) {
            result.put("isLegal", false);
            result.put("message", "文件大小超出系统限制的最大允许值");
            return result;
        }

        // 文件后缀名称校验，拦截可执行文件
        if (fileName == null || fileName.trim().isEmpty()) {
            result.put("isLegal", false);
            result.put("message", "文件名称不能为空");
            return result;
        }

        if (!forbiddenExtensionList.isEmpty()) {
            String fileNameLower = fileName.toLowerCase();
            for (String forbiddenExt : forbiddenExtensionList) {
                if (fileNameLower.endsWith(forbiddenExt)) {
                    result.put("isLegal", false);
                    result.put("message", "安全限制，不允许上传exe等可执行类危险文件");
                    return result;
                }
            }
        }

        if (!allowedMimeList.isEmpty()) {
            if (!allowedMimeList.contains(fileMimeType)) {
                result.put("isLegal", false);
                result.put("message", "当前文件类型不在系统允许上传的范围内");
                return result;
            }
        }

        result.put("isLegal", true);
        result.put("message", "文件大小和类型校验通过");
        return result;
    }

    public void saveChunk(MultipartFile file, String md5, String fileName, int chunkIndex, int totalChunks) throws IOException {
        // 创建分片存储目录
        // saveFileName最终保存的文件名，采用fileName+"_"+md5值
        // chunkDir是保存文件分片的离世目录
        // mkdirs()尝试创建目录机器所有必须的父目录，如果目录已经村子，则返回false，但不报错，如果创建成功，返回true。
        String saveFileName = fileName + "_" + md5;
        String chunkDir = Paths.get(tempDir, saveFileName).toString();
        new File(chunkDir).mkdirs();
        // 文件分片的名称用0、1、2、3......表示，保存在chunkIndex中
        // Paths.get(chunkDir, String.valueOf(chunkIndex)).toString()在上一步创建的目录下，以分片索引作为文件名构建完整路径。
        String chunkPath = Paths.get(chunkDir, String.valueOf(chunkIndex)).toString();
        // 创建目录文件的File对象引用，此时文件尚未在磁盘上物理创建，只是内存中的一个路径标识
        File dest = new File(chunkPath);

        // 如果分片已经存在且大小已知，则跳过（幂等处理）
        // 价值：支持断点续传，如果网络中断，后续重新上传，前端可能重传成功的分片，后端通过此逻辑避免重复写入，节省IO资源
        if (dest.exists() && (dest.length() == file.getSize())) {
            return;
        }

        // file.transferTo(dest)：String MultipartFile提供的核心方法
        // 它将上传的文件流直接写入到目标文件dest中
        // 底层优化：如果可能，它会使用操作系统的零拷贝技术或者直接移动临时文件，而不是在内存中缓存整个文件，因此效率较高
        // 异常：如果写入失败（如磁盘满、权限denied），则会抛出IOException
        file.transferTo(dest);
    }


}
