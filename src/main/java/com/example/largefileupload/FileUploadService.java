package com.example.largefileupload;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
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
            @Value("${file.upload.allowed-mime-list}") List<String> allowedMimeList,
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

    /**
     * 获取已上传的分片索引列表
     *
     * @param saveFileName
     * @param totalChunks
     * @return
     */
    public List<Integer> getUploadedChunks(String saveFileName, int totalChunks){
        List<Integer> uploaded = new ArrayList<>();
        // 存放分片的临时目录
        String chunkDir = Paths.get(tempDir, saveFileName).toString();
        // 此时仅仅是在内存中创建了几个文件/目录的抽象标识，并没有在磁盘上实际创建该目录
        File dir = new File(chunkDir);
        // 如果该文件的临时目录根本不存在，说明没有任何分片上传过，直接返回空的已经上传列表uploaded.这是一种快速失败（Fail-fast）机制，避免后续无效的循环检查
        if (!dir.exists()) {
            return uploaded;
        }
        // 假设分片索引从0到totalChunks-1，代码假设分片名为其索引值（例如：0、1、2、3...），且没有后缀名
        // 逐个检查这些文件是否存在，若存在，则将索引加入到uploaded，uploaded是已经上传的文件分片列表
        for (int i = 0; i < totalChunks; i++) {
            if (new File(dir, String.valueOf(i)).exists()){
                uploaded.add(i);
            }
        }
        return uploaded;
    }

    /**
     * 检查文件是否完整存在（用于秒传）
     *
     * @param saveFileName
     * @param fileSize
     * @return
     */
    public boolean isFileComplete(String saveFileName, long fileSize){
        // saveFileName是最终保存的文件名，采用fileName+"_"+md5
        String finalFilePath = Paths.get(uploadDir, saveFileName).toString();
        File finalFile = new File(finalFilePath);
        // 如果目标文件已经存在并且大小和请求中文件大小相同，则说明文件已经传输结束
        return finalFile.exists() && (finalFile.length() == fileSize);
    }

    /**
     * 合并分片，采用流式合并方式，合并一个分片将一个分片加载到内存，防止将所有的文件分片一次性加载到内存
     *
     * @param md5
     * @param fileName
     * @param fileSize
     * @param totalChunks
     * @throws IOException
     */
    public void mergeChunks(String md5, String fileName, long fileSize, int totalChunks) throws IOException {
        // 最终保存的文件名
        String saveFileName = fileName + "_" + md5;
        // 历史保存合并后的文件目录
        String chunkDir = Paths.get(tempDir, saveFileName).toString();
        // 获取或者创建针对此特定文件的合并锁
        ReentrantLock fileLock = mergeLocks.computeIfAbsent(md5, k-> new ReentrantLock());
        try {
            fileLock.lock();
            // 检查是否已经在合并中或者已经完成（幂等性校验）
            Path finalFilePath = Paths.get(uploadDir, saveFileName);
            File finalFile = new File(finalFilePath.toString());
            // 如果目标文件已经存在且大小相等，则直接返回，实现幂等
            if (finalFile.exists() && (finalFile.length() == fileSize)) {
                return;
            }
            // 确保最终文件目录存在
            Files.createDirectories(finalFilePath.getParent());
            // 流式合并，按照顺序读取分片，追加写入最终文件
            try (OutputStream mergedOutPutStream = new BufferedOutputStream(Files.newOutputStream(finalFilePath.toFile().toPath()))) {
                for (int i = 0; i < totalChunks; i++) {
                    Path chunkPath = Paths.get(chunkDir, String.valueOf(i));
                    if (!Files.exists(chunkPath)) {
                        throw new IOException("Missing chunk:" + chunkPath);
                    }
                    // 使用带缓冲的流进行读写，控制内存占用
                    try (InputStream chunkInputStream = new BufferedInputStream(Files.newInputStream(chunkPath.toFile().toPath()))) {
                        // 创建缓冲区，可以根据实际情况调整
                        byte[] buffer = new byte[bufferSize];
                        int bytesRead;
                        while ((bytesRead = chunkInputStream.read(buffer)) != -1) {
                            mergedOutPutStream.write(buffer, 0, bytesRead);
                        }
                    }
                    Files.delete(chunkPath);
                }
                mergedOutPutStream.flush();
            }
            // 清理空目录
            new File(chunkDir).delete();
        } catch (IOException e) {
            log.error("合并分片失败", e);
        } finally {
            fileLock.unlock();
            // 清理此文件的锁
            mergeLocks.remove(md5);
        }
    }
}
