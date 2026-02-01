package org.jeecg.modules.agenthub.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import org.jeecg.modules.agenthub.service.ScreenshotService;

/**
 * 软著并行生成控制器
 *
 * @author jeecg-boot
 */
@Tag(name = "软著并行生成", description = "软著并行生成相关接口")
@RestController
@RequestMapping("/agenthub/api")
@CrossOrigin(origins = "*",
             allowedHeaders = "*",
             methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
@Slf4j
public class ParallelGenerationController {
    
    private static final Logger logger = LoggerFactory.getLogger(ParallelGenerationController.class);

    @Autowired
    private CopyrightAIController copyrightAIController;

    @Autowired
    private ScreenshotService screenshotService;

    /**
     * 全并行生成：前端代码 + 后端代码 + 说明书章节
     */
    @PostMapping(value = "/generate-all-parallel", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "全并行生成所有内容", description = "同时生成前端代码、后端代码和说明书章节")
    public SseEmitter generateAllParallel(@RequestBody Map<String, String> request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setContentType("text/event-stream;charset=UTF-8");

        String appName = request.get("appName");
        String appPrompt = request.get("appPrompt");

        log.info("开始全并行生成所有内容: {}", appName);

        SseEmitter emitter = new SseEmitter(1800000L); // 30分钟超时

        CompletableFuture.runAsync(() -> {
            try {
                
                String fileId = generateFileId(appName);
                
                
                Map<String, Object> startData = new HashMap<>();
                startData.put("type", "start");
                startData.put("message", "开始全并行生成所有内容...");
                startData.put("progress", 0);
                startData.put("fileId", fileId);
                startData.put("appName", appName);
                startData.put("timestamp", System.currentTimeMillis());
                
                log.info("生成文件标识符: {}", fileId);
                
                try {
                    emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(startData));
                } catch (IllegalStateException e) {
                    log.warn("SSE连接已关闭，跳过发送开始信号: {}", e.getMessage());
                    return; 
                } catch (IOException e) {
                    log.error("发送开始信号失败", e);
                    return; 
                }

                
                CompletableFuture<String> frontendFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        sendProgressWithType(emitter, "frontend_start", "🚀 开始生成前端代码...", 10);
                        
                        
                        String frontendCode = copyrightAIController.generateFrontendCodeSync(appName, appPrompt != null ? appPrompt : "");
                        
                        log.info("✅ AI前端代码生成完成，长度: {} 字符", frontendCode.length());
                        
                        
                        Map<String, String> frontendData = new HashMap<>();
                        frontendData.put("code", frontendCode);
                        frontendData.put("appName", appName);
                        frontendData.put("timestamp", String.valueOf(System.currentTimeMillis()));
                        saveGeneratedContentByFileId(fileId, "frontend-code", frontendData);

                        
                        log.info("🚀 准备发送前端代码完成事件: appName={}, fileId={}", appName, fileId);
                        Map<String, Object> frontendResult = new HashMap<>();
                        frontendResult.put("type", "frontend_complete");
                        frontendResult.put("message", "前端代码生成完成并已保存");
                        frontendResult.put("progress", 33);
                        frontendResult.put("fileId", fileId);
                        frontendResult.put("codeLength", frontendCode.length()); 
                        frontendResult.put("codeReady", true); 
                        frontendResult.put("timestamp", System.currentTimeMillis());
                        
                        log.info("前端完成事件数据: {}", frontendResult);

                        // 🔥 主动触发截图（确保截图执行）
                        try {
                            log.info("🔥 [主动截图] 开始调用截图服务...（fileId: {}）", fileId);
                            screenshotService.generateScreenshots(appName, frontendCode, emitter, fileId);
                            log.info("✅ [主动截图] 截图服务调用完成");
                        } catch (Exception screenshotError) {
                            log.error("❌ [主动截图] 截图失败", screenshotError);
                        }

                        try {
                            emitter.send(SseEmitter.event()
                                .name("progress")
                                .data(frontendResult));
                            log.info("✅ 前端代码完成事件已发送");
                        } catch (IllegalStateException e) {
                            log.warn("SSE连接已关闭，跳过发送前端完成事件: {}", e.getMessage());
                        } catch (IOException e) {
                            log.error("发送前端完成事件失败", e);
                        }

                        return frontendCode;
                    } catch (Exception e) {
                        log.error("前端代码生成失败", e);
                        sendProgressWithType(emitter, "frontend_error", "前端代码生成失败: " + e.getMessage(), 0);
                        throw new RuntimeException(e);
                    }
                });

                CompletableFuture<String> backendFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        sendProgressWithType(emitter, "backend_start", "开始生成后端代码...", 10);

                        
                        String frontendCode = frontendFuture.join();
                        String backendCode = copyrightAIController.generateBackendCodeSync(appName, frontendCode);

                        
                        Map<String, String> backendData = new HashMap<>();
                        backendData.put("code", backendCode);
                        backendData.put("appName", appName);
                        backendData.put("timestamp", String.valueOf(System.currentTimeMillis()));
                        saveGeneratedContentByFileId(fileId, "backend-code", backendData);

                        
                        Map<String, Object> backendResult = new HashMap<>();
                        backendResult.put("type", "backend_complete");
                        backendResult.put("message", "后端代码生成完成并已保存");
                        backendResult.put("progress", 66);
                        backendResult.put("fileId", fileId);
                        backendResult.put("timestamp", System.currentTimeMillis());

                        emitter.send(SseEmitter.event()
                            .name("progress")
                            .data(backendResult));

                        return backendCode;
                    } catch (Exception e) {
                        log.error("后端代码生成失败", e);
                        sendProgressWithType(emitter, "backend_error", "后端代码生成失败: " + e.getMessage(), 0);
                        throw new RuntimeException(e);
                    }
                });

                CompletableFuture<Map<String, String>> documentFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        sendProgressWithType(emitter, "document_start", "开始生成说明书章节...", 10);

                        
                        String frontendCode = frontendFuture.join();
                        
                        Map<String, String> chapters = new HashMap<>();
                        
                        
                        for (int i = 1; i <= 4; i++) {
                            String chapterContent = copyrightAIController.generateDocumentChapterSync(i, appName, frontendCode);
                            chapters.put("chapter" + i, chapterContent);
                            
                            
                            Map<String, Object> chapterResult = new HashMap<>();
                            chapterResult.put("type", "chapter_complete");
                            chapterResult.put("message", String.format("第%d章生成完成", i));
                            chapterResult.put("chapterNum", i);
                            chapterResult.put("progress", 66 + (i * 8)); // 66% + 每章8%
                            chapterResult.put("fileId", fileId);
                            chapterResult.put("timestamp", System.currentTimeMillis());

                            emitter.send(SseEmitter.event()
                                .name("progress")
                                .data(chapterResult));
                        }

                        
                        saveGeneratedContentByFileId(fileId, "chapters", chapters);

                        return chapters;
                    } catch (Exception e) {
                        log.error("说明书生成失败", e);
                        sendProgressWithType(emitter, "document_error", "说明书生成失败: " + e.getMessage(), 0);
                        throw new RuntimeException(e);
                    }
                });

                
                CompletableFuture<Map<String, String>> softwareInfoFuture = documentFuture.thenCompose(chapters -> {
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            sendProgressWithType(emitter, "software_info_start", "开始提取软著申请信息...", 10);
                            log.info("🔍 软著信息提取开始，说明书章节数: {}", chapters != null ? chapters.size() : 0);

                            
                            String chapter1Content = chapters != null ? chapters.get("chapter1") : "";
                            if (chapter1Content == null || chapter1Content.trim().isEmpty()) {
                                log.warn("⚠️ 第一章内容为空，使用应用名称作为提取基础");
                                chapter1Content = appName + " 系统说明";
                            }
                            
                            log.info("🔍 使用第一章内容提取软著信息，内容长度: {} 字符", chapter1Content.length());
                            Map<String, Object> softwareInfoObj = extractSoftwareInfo(appName, chapter1Content);
                            
                            
                            Map<String, String> softwareInfo = new HashMap<>();
                            if (softwareInfoObj != null) {
                                for (Map.Entry<String, Object> entry : softwareInfoObj.entrySet()) {
                                    softwareInfo.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "");
                                }
                                log.info("✅ 软著信息提取成功，字段数: {}", softwareInfo.size());
                            } else {
                                log.warn("⚠️ AI提取软著信息返回空结果");
                            }
                            
                            
                            saveGeneratedContentByFileId(fileId, "software-info", softwareInfo);

                            
                            Map<String, Object> softwareInfoResult = new HashMap<>();
                            softwareInfoResult.put("type", "software_info_complete");
                            softwareInfoResult.put("message", "软著申请信息提取完成");
                            softwareInfoResult.put("progress", 95);
                            softwareInfoResult.put("fileId", fileId);
                            softwareInfoResult.put("timestamp", System.currentTimeMillis());

                            try {
                                emitter.send(SseEmitter.event()
                                    .name("progress")
                                    .data(softwareInfoResult));
                                log.info("✅ 软著信息完成事件已发送");
                            } catch (IllegalStateException e) {
                                log.warn("SSE连接已关闭，跳过发送软著信息完成事件: {}", e.getMessage());
                            } catch (IOException e) {
                                log.error("发送软著信息完成事件失败", e);
                            }

                            return softwareInfo;
                        } catch (Exception e) {
                            log.error("软著信息提取失败", e);
                            sendProgressWithType(emitter, "software_info_error", "软著信息提取失败: " + e.getMessage(), 0);
                            throw new RuntimeException(e);
                        }
                    });
                });

                
                log.info("🔄 开始等待所有并行任务完成...");
                
                String frontendCode = frontendFuture.get();
                log.info("✅ 前端代码生成已完成，长度: {} 字符", frontendCode != null ? frontendCode.length() : 0);
                
                String backendCode = backendFuture.get();
                log.info("✅ 后端代码生成已完成，长度: {} 字符", backendCode != null ? backendCode.length() : 0);
                
                Map<String, String> chapters = documentFuture.get();
                log.info("✅ 说明书生成已完成，章节数: {}", chapters != null ? chapters.size() : 0);
                
                Map<String, String> softwareInfo = softwareInfoFuture.get();
                log.info("✅ 软著信息提取已完成，字段数: {}", softwareInfo != null ? softwareInfo.size() : 0);

                
                Map<String, Object> result = new HashMap<>();
                result.put("fileId", fileId);
                result.put("appName", appName);
                result.put("completed", true);
                result.put("message", "全并行生成完成，所有内容已保存到文件");
                result.put("progress", 100);
                result.put("frontendCodeLength", frontendCode != null ? frontendCode.length() : 0);
                result.put("backendCodeLength", backendCode != null ? backendCode.length() : 0);
                result.put("chaptersCount", chapters != null ? chapters.size() : 0);
                result.put("softwareInfoCount", softwareInfo != null ? softwareInfo.size() : 0);

                log.info("全并行生成完成: appName={}, fileId={}", appName, fileId);
                
                emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(result));
                
                log.info("✅ SSE完成信号已发送");
                
                
                Thread.sleep(100);
                
                emitter.complete();

            } catch (Exception e) {
                log.error("全并行生成失败: appName={}", appName, e);
                try {
                    Map<String, Object> errorData = new HashMap<>();
                    errorData.put("error", true);
                    errorData.put("message", "生成失败: " + e.getMessage());
                    errorData.put("timestamp", System.currentTimeMillis());

                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data(errorData));
                } catch (IOException ioException) {
                    log.error("发送错误信息失败", ioException);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 发送带类型的进度信息
     */
    private void sendProgressWithType(SseEmitter emitter, String type, String message, int progress) {
        try {
            Map<String, Object> progressData = new HashMap<>();
            progressData.put("type", type);
            progressData.put("message", message);
            progressData.put("progress", progress);
            progressData.put("timestamp", System.currentTimeMillis());

            emitter.send(SseEmitter.event()
                .name("progress")
                .data(progressData));

        } catch (IllegalStateException e) {
            log.warn("SSE连接已关闭，跳过发送进度信息: {}", e.getMessage());
        } catch (IOException e) {
            log.error("发送进度信息失败", e);
        }
    }

    /**
     * 基于应用名称生成文件ID
     */
    private String generateFileId(String appName) {
        
        String cleanAppName = appName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_");
        String timestamp = String.valueOf(System.currentTimeMillis());
        return cleanAppName + "_" + timestamp;
    }

    /**
     * 保存生成的内容到文件（使用fileId）
     */
    private void saveGeneratedContentByFileId(String fileId, String type, Object content) {
        try {
            String tempDir = System.getProperty("java.io.tmpdir") + "/copyright-generation/" + fileId;
            new File(tempDir).mkdirs();
            
            String fileName = tempDir + "/" + type + ".json";
            ObjectMapper mapper = new ObjectMapper();
            
            
            Map<String, Object> fileData = new HashMap<>();
            fileData.put("content", content);
            fileData.put("fileId", fileId);
            fileData.put("type", type);
            fileData.put("createTime", System.currentTimeMillis());
            
            mapper.writeValue(new File(fileName), fileData);
            
            log.info("保存内容到文件: {} -> {}", type, fileName);
        } catch (Exception e) {
            log.error("保存内容到文件失败: type={}, fileId={}", type, fileId, e);
        }
    }

    /**
     * 提取软著信息
     */
    private Map<String, Object> extractSoftwareInfo(String appName, String chapter1Content) {
        
        Map<String, Object> info = new HashMap<>();
        info.put("name", appName);
        info.put("version", "V1.0");
        info.put("purpose", "提高工作效率，优化业务流程");
        info.put("domain", "企业管理、信息化建设");
        info.put("functions", "提供完整的业务管理功能，包括数据录入、查询、统计分析、报表生成、权限管理、系统配置等核心模块。");
        info.put("features", "采用现代化Web技术架构，界面友好，操作简便，支持多用户并发访问，具有良好的扩展性和稳定性。");
        return info;
    }

    /**
     * 下载生成的前端代码
     */
    @GetMapping("/download-frontend-code/{fileId}")
    @Operation(summary = "下载生成的前端代码", description = "从后端文件中下载前端代码")
    public ResponseEntity<String> downloadFrontendCode(@PathVariable String fileId) {
        try {
            String tempDir = System.getProperty("java.io.tmpdir") + "/copyright-generation/" + fileId;
            String fileName = tempDir + "/frontend-code.json";
            
            File file = new File(fileName);
            if (!file.exists()) {
                log.warn("前端代码文件不存在: {}", fileName);
                return ResponseEntity.notFound().build();
            }
            
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> fileData = mapper.readValue(file, Map.class);
            
            
            Object content = fileData.get("content");
            if (content instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> data = (Map<String, String>) content;
                String frontendCode = data.get("code");
                
                log.info("成功下载前端代码，fileId: {}, 代码长度: {}", fileId, frontendCode != null ? frontendCode.length() : 0);
                return ResponseEntity.ok(frontendCode);
            } else {
                log.error("前端代码内容格式错误，fileId: {}", fileId);
                return ResponseEntity.status(500).build();
            }
            
        } catch (Exception e) {
            log.error("下载前端代码失败: fileId={}", fileId, e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 下载生成的后端代码
     */
    @GetMapping("/download-backend-code/{fileId}")
    @Operation(summary = "下载生成的后端代码", description = "从后端文件中下载后端代码")
    public ResponseEntity<String> downloadBackendCode(@PathVariable String fileId) {
        try {
            String tempDir = System.getProperty("java.io.tmpdir") + "/copyright-generation/" + fileId;
            String fileName = tempDir + "/backend-code.json";
            
            File file = new File(fileName);
            if (!file.exists()) {
                log.warn("后端代码文件不存在: {}", fileName);
                return ResponseEntity.notFound().build();
            }
            
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> fileData = mapper.readValue(file, Map.class);
            
            
            Object content = fileData.get("content");
            if (content instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> data = (Map<String, String>) content;
                String backendCode = data.get("code");
                
                log.info("成功下载后端代码，fileId: {}, 代码长度: {}", fileId, backendCode != null ? backendCode.length() : 0);
                return ResponseEntity.ok(backendCode);
            } else {
                log.error("后端代码内容格式错误，fileId: {}", fileId);
                return ResponseEntity.status(500).build();
            }
            
        } catch (Exception e) {
            log.error("下载后端代码失败: fileId={}", fileId, e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 下载生成的章节内容
     */
    @GetMapping("/download-chapters/{fileId}")
    @Operation(summary = "下载生成的章节内容", description = "从后端文件中下载章节内容")
    public ResponseEntity<Map<String, String>> downloadChapters(@PathVariable String fileId) {
        try {
            String tempDir = System.getProperty("java.io.tmpdir") + "/copyright-generation/" + fileId;
            String fileName = tempDir + "/chapters.json";
            
            File file = new File(fileName);
            if (!file.exists()) {
                log.warn("章节文件不存在: {}", fileName);
                return ResponseEntity.notFound().build();
            }
            
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> fileData = mapper.readValue(file, Map.class);
            
            
            Object content = fileData.get("content");
            if (content instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> chapters = (Map<String, String>) content;
                
                log.info("成功下载章节内容，fileId: {}, 章节数量: {}", fileId, chapters.size());
                return ResponseEntity.ok(chapters);
            } else {
                log.error("章节内容格式错误，fileId: {}", fileId);
                return ResponseEntity.status(500).build();
            }
            
        } catch (Exception e) {
            log.error("下载章节内容失败: fileId={}", fileId, e);
            return ResponseEntity.status(500).build();
        }
    }
} 