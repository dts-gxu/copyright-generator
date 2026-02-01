package org.jeecg.modules.agenthub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.exception.JeecgBootException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.jeecg.common.system.vo.LoginUser;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jeecg.modules.agenthub.copyright.entity.CopyrightProject;
import org.jeecg.modules.agenthub.copyright.entity.CopyrightFile;
import org.jeecg.modules.agenthub.copyright.service.ICopyrightProjectService;
import org.jeecg.modules.agenthub.copyright.service.ICopyrightFileService;
import org.jeecg.modules.agenthub.util.CrossPlatformUtil;
import org.jeecg.modules.agenthub.service.ScreenshotService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.util.Base64;
import java.util.List;
import org.jeecg.common.system.util.JwtUtil;
import org.apache.shiro.SecurityUtils;


import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.FileWriter;



/**
 * 软件著作权材料生成控制器
 */
@RestController
@RequestMapping("/agenthub/api")
@Slf4j
public class CopyrightAIController {

    @Value("${ai.deepseek.api-key:${DEEPSEEK_API_KEY:}}")
    private String apiKey;

    @Value("${ai.deepseek.base-url:https://api.deepseek.com/v1}")
    private String baseUrl;

    private static final String HIGH_COST_MODEL = "deepseek-reasoner";
    private static final String LOW_COST_MODEL = "deepseek-chat";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Autowired
    private ICopyrightProjectService copyrightProjectService;

    @Autowired
    private ICopyrightFileService copyrightFileService;
    
    @Autowired
    private ScreenshotService screenshotService;
    
    private static final Semaphore generationSemaphore = new Semaphore(3);

    public CopyrightAIController() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(600, TimeUnit.SECONDS))
                            .addHandlerLast(new WriteTimeoutHandler(60, TimeUnit.SECONDS)));
        
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 生成软件名称
     */
    @PostMapping("/copyright/generate-names")
    public Result<Map<String, Object>> generateSoftwareNames(@RequestBody Map<String, String> request) {
        try {
            String domain = request.get("domain");
            String modelId = request.get("modelId");

            log.info("生成软件名称请求 - 领域: {}, 模型: {}", domain, modelId);

            if (domain == null || domain.trim().isEmpty()) {
                return Result.error("请提供专业领域或方向");
            }

            
            String prompt = String.format(
                "请基于'%s'这个领域或专业方向，生成10个适合软件著作权申请的软件名称，" +
                "名字要长要专业一点，名称必须以软件、系统、平台结尾。" +
                "只需要告诉我名称就行，不用告诉我其他东西，不用标序号！不可以标序号。" +
                "每个名称单独一行。", domain
            );

            log.info("开始调用AI生成软件名称: domain={}", domain);
            String result = callDeepSeekAPI(prompt);

            
            String[] lines = result.split("\n");
            List<String> names = new ArrayList<>();

            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() &&
                    (trimmed.endsWith("软件") || trimmed.endsWith("系统") || trimmed.endsWith("平台"))) {
                    
                    trimmed = trimmed.replaceAll("^\\d+[.、]\\s*", "");
                    names.add(trimmed);
                }
            }

            
            if (names.size() < 5) {
                names.add(domain + "智能管理系统");
                names.add(domain + "数据分析平台");
                names.add(domain + "云服务软件");
                names.add(domain + "协同办公系统");
                names.add(domain + "自动化控制系统");
            }

            log.info("AI软件名称生成完成: domain={}, 生成数量={}", domain, names.size());

            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("success", true);
            resultMap.put("names", names);

            return Result.OK(resultMap);
        } catch (Exception e) {
            log.error("生成软件名称失败", e);
            return Result.error("生成软件名称失败: " + e.getMessage());
        }
    }

    /**
     * 创建版权项目
     */
    @PostMapping("/copyright/projects")
    public Result<Map<String, Object>> createProject(@RequestBody Map<String, Object> params) {
        try {
            String appName = (String) params.get("appName");
            String appPrompt = (String) params.get("appPrompt");
            String modelId = (String) params.get("modelId");
            String domain = (String) params.get("domain");
            String currentUserId = getCurrentUserId();
            String currentUserInfo = getCurrentUserInfo();
            
            
            String projectName = appName;

            log.info("创建版权项目: {}, 软著名称: {}, 用户: {}", appName, projectName, currentUserInfo);

            
            CopyrightProject project = copyrightProjectService.createProject(projectName, appName, domain, appPrompt, modelId, currentUserId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("projectId", project.getId());
            result.put("appName", appName);
            result.put("status", "created");
            result.put("message", "项目创建成功");
            
            return Result.OK("项目创建成功", result);
        } catch (Exception e) {
            log.error("创建项目失败", e);
            return Result.error("创建项目失败: " + e.getMessage());
        }
    }

    /**
     * 获取版权项目列表
     */
    @GetMapping("/copyright/projects")
    public Result<List<Map<String, Object>>> getProjects() {
        try {
            String currentUserId = getCurrentUserId();
            String currentUserInfo = getCurrentUserInfo();
            // log.info("获取版权项目列表，用户: {}", currentUserInfo);
            
            
            List<CopyrightProject> projects = copyrightProjectService.getProjectsByUser(currentUserId);
            // log.info("用户 {} 获取自己的项目，找到 {} 个项目", currentUserInfo, projects.size());
            
            
            List<Map<String, Object>> result = projects.stream().map(project -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", project.getId());
                map.put("projectName", project.getProjectName());
                map.put("appName", project.getAppName());
                map.put("domain", project.getDomain());
                map.put("appPrompt", project.getAppPrompt() != null ? project.getAppPrompt() : "");
                map.put("modelId", project.getModelId());
    
                map.put("status", project.getStatus());
                map.put("progress", project.getProgress());
                map.put("currentStep", project.getCurrentStep());
                map.put("completedFiles", project.getCompletedFiles());
                map.put("generatingFiles", project.getGeneratingFiles());
                map.put("startTime", project.getStartTime());
                map.put("endTime", project.getEndTime());
                map.put("createBy", project.getCreateBy());
                map.put("createTime", project.getCreateTime());
                map.put("updateBy", project.getUpdateBy());
                map.put("updateTime", project.getUpdateTime());
                return map;
            }).collect(Collectors.toList());
            
            return Result.OK(result);
        } catch (Exception e) {
            log.error("获取项目列表失败", e);
            return Result.error("获取项目列表失败: " + e.getMessage());
        }
    }

    /**
     * 启动项目生成
     */
    @PostMapping("/copyright/projects/{projectId}/generate")
    public Result<Map<String, Object>> startProjectGeneration(@PathVariable String projectId) {
        try {
            String currentUserId = getCurrentUserId();
            String currentUserInfo = getCurrentUserInfo();
            log.info("启动项目生成，项目ID: {}, 用户: {}", projectId, currentUserInfo);
            
            
            CopyrightProject project = copyrightProjectService.getById(projectId);
            if (project == null) {
                return Result.error("项目不存在");
            }
            
            
            if ("generating".equals(project.getStatus())) {
                return Result.error("项目正在生成中，请勿重复启动");
            }
            
            
            String currentStatus = project.getStatus();
            boolean updated = false;
            
            if ("pending".equals(currentStatus) || "error".equals(currentStatus) || "completed".equals(currentStatus)) {
                
                updated = copyrightProjectService.checkAndUpdateStatus(projectId, currentStatus, "generating");
                
                if (!updated) {
                    
                    CopyrightProject latestProject = copyrightProjectService.getById(projectId);
                    if ("generating".equals(latestProject.getStatus())) {
                        return Result.error("项目正在生成中，请勿重复启动");
                    } else {
                        return Result.error("项目状态更新失败，请稍后重试");
                    }
                }
            } else {
                return Result.error("项目状态异常，无法启动生成，当前状态: " + currentStatus);
            }
            
            
            copyrightProjectService.updateCurrentStep(projectId, "开始生成软著材料...");
            
            
            CompletableFuture.runAsync(() -> {
                boolean acquired = false;
                try {
                    
                    generationSemaphore.acquire();
                    acquired = true;
                    log.info("项目 {} 获取到生成许可，当前可用许可: {}", projectId, generationSemaphore.availablePermits());
                    
                    executeFullGenerationProcess(project, currentUserInfo);
                } catch (InterruptedException e) {
                    log.error("等待生成许可被中断", e);
                    Thread.currentThread().interrupt();
                    copyrightProjectService.updateStatus(projectId, "error");
                    copyrightProjectService.updateCurrentStep(projectId, "生成失败: 系统繁忙");
                } catch (Exception e) {
                    log.error("生成流程执行失败", e);
                    copyrightProjectService.updateStatus(projectId, "error");
                    copyrightProjectService.updateCurrentStep(projectId, "生成失败: " + e.getMessage());
                } finally {
                    if (acquired) {
                        generationSemaphore.release();
                        log.info("项目 {} 释放生成许可，当前可用许可: {}", projectId, generationSemaphore.availablePermits());
                    }
                }
            });
            
            Map<String, Object> result = new HashMap<>();
            result.put("projectId", projectId);
            result.put("status", "generating");
            result.put("message", "项目生成已启动");
            
            return Result.OK("项目生成已启动", result);
        } catch (Exception e) {
            log.error("启动项目生成失败", e);
            return Result.error("启动项目生成失败: " + e.getMessage());
        }
    }
    
    /**
     * 执行完整的生成流程
     */
    private void executeFullGenerationProcess(CopyrightProject project, String currentUser) {
        String projectId = project.getId();
        String appName = project.getAppName();
        String appPrompt = project.getAppPrompt();
        
        log.info("开始执行完整生成流程，项目ID: {}, 项目名称: {}", projectId, appName);
        
        
        startProgressSimulation(projectId);
        
        try {
            
            CopyrightProject currentProject = copyrightProjectService.getById(projectId);
            if (currentProject == null) {
                log.error("项目不存在，停止生成流程，项目ID: {}", projectId);
                return;
            }
            if (!"generating".equals(currentProject.getStatus())) {
                log.error("项目状态不正确，停止生成流程，项目ID: {}, 当前状态: {}", projectId, currentProject.getStatus());
                return;
            }
            
            // 1. 生成前端代码
            copyrightProjectService.updateCurrentStep(projectId, "正在生成前端代码...");
            String frontendCode = generateFrontendCodeSync(appName, appPrompt);
            copyrightFileService.saveGeneratedFile(projectId, "frontend_code", "前端界面代码.txt", "前端界面代码", frontendCode, currentUser);
            log.info("项目 {} 前端代码生成完成", projectId);
            
            // 2. 生成后端代码（分三次生成并分别保存）
            copyrightProjectService.updateCurrentStep(projectId, "正在生成后端代码...");
            
            
            log.info("项目 {} 开始生成Part1：基础架构层", projectId);
            String part1 = generateBackendPart1(appName, frontendCode);
            copyrightFileService.saveGeneratedFile(projectId, "backend_part1", "后端Part1代码.txt", "基础架构层代码", part1, currentUser);
            log.info("项目 {} Part1代码生成完成", projectId);
            
            
            log.info("项目 {} 开始生成Part2：中间件和认证层", projectId);
            String part2 = generateBackendPart2(appName, frontendCode, part1);
            copyrightFileService.saveGeneratedFile(projectId, "backend_part2", "后端Part2代码.txt", "中间件和认证层代码", part2, currentUser);
            log.info("项目 {} Part2代码生成完成", projectId);
            
            
            log.info("项目 {} 开始生成Part3：业务和API层", projectId);
            String part3 = generateBackendPart3(appName, frontendCode, part1);
            copyrightFileService.saveGeneratedFile(projectId, "backend_part3", "后端Part3代码.txt", "业务和API层代码", part3, currentUser);
            log.info("项目 {} Part3代码生成完成", projectId);
            
            
            String backendCode = mergeBackendParts(part1, part2, part3);
            copyrightFileService.saveGeneratedFile(projectId, "backend_code", "后端服务代码.txt", "后端服务代码（合并）", backendCode, currentUser);
            log.info("项目 {} 后端代码生成完成（三次生成已全部保存）", projectId);
            
            // 3. 生成文档章节
            String combinedCode = frontendCode + "\n\n" + backendCode;
            
            
            copyrightProjectService.updateCurrentStep(projectId, "正在生成第一章...");
            String chapter1 = generateChapterSync(1, appName, combinedCode);
            copyrightFileService.saveGeneratedFile(projectId, "chapter1", "第一章_系统概述.txt", "第一章 系统概述", chapter1, currentUser);
            log.info("项目 {} 第一章生成完成", projectId);
            
            
            copyrightProjectService.updateCurrentStep(projectId, "正在生成第二章...");
            String chapter2 = generateChapterSync(2, appName, combinedCode);
            copyrightFileService.saveGeneratedFile(projectId, "chapter2", "第二章_程序建立过程.txt", "第二章 程序建立过程", chapter2, currentUser);
            log.info("项目 {} 第二章生成完成", projectId);
            
            
            copyrightProjectService.updateCurrentStep(projectId, "正在生成第三章...");
            String chapter3 = generateChapterSync(3, appName, combinedCode);
            copyrightFileService.saveGeneratedFile(projectId, "chapter3", "第三章_程序功能介绍.txt", "第三章 程序功能介绍", chapter3, currentUser);
            log.info("项目 {} 第三章生成完成", projectId);
            
            
            copyrightProjectService.updateCurrentStep(projectId, "正在生成第四章...");
            String chapter4 = generateChapterSync(4, appName, combinedCode);
            copyrightFileService.saveGeneratedFile(projectId, "chapter4", "第四章_总结与展望.txt", "第四章 总结与展望", chapter4, currentUser);
            log.info("项目 {} 第四章生成完成", projectId);
            
            log.info("项目 {} 所有章节生成完成", projectId);
            
            
            log.info("项目 {} 开始生成界面截图...", projectId);
            try {
                
                screenshotService.generateScreenshots(appName, frontendCode, null, projectId);
                log.info("项目 {} 界面截图生成已启动", projectId);
                
                
                log.info("项目 {} 等待截图生成完成...", projectId);
                int maxWaitSeconds = 300; // 5分钟
                int waitedSeconds = 0;
                boolean screenshotsCompleted = false;
                
                while (waitedSeconds < maxWaitSeconds && !screenshotsCompleted) {
                    Thread.sleep(5000); 
                    waitedSeconds += 5;
                    
                    
                    List<CopyrightFile> screenshots = copyrightFileService.lambdaQuery()
                        .eq(CopyrightFile::getProjectId, projectId)
                        .eq(CopyrightFile::getFileType, "screenshot")
                        .list();
                    
                    if (screenshots.size() >= 5) { 
                        screenshotsCompleted = true;
                        log.info("项目 {} 截图生成完成，共生成 {} 张截图", projectId, screenshots.size());
                    } else {
                        log.info("项目 {} 截图生成中... 当前已生成 {} 张截图", projectId, screenshots.size());
                    }
                }
                
                if (!screenshotsCompleted) {
                    log.warn("项目 {} 截图生成超时，继续完成项目", projectId);
                }
                
            } catch (Exception e) {
                log.error("项目 {} 生成界面截图失败", projectId, e);
                
            }
            
            // 4. 生成并裁剪源代码Word文档，保存到数据库
            try {
                copyrightProjectService.updateCurrentStep(projectId, "正在生成源代码文档...");
                log.info("项目 {} 开始生成并裁剪源代码Word文档", projectId);
                
                
                StringBuilder completeSourceCode = new StringBuilder();
                completeSourceCode.append(frontendCode).append("\n\n");
                completeSourceCode.append(backendCode).append("\n");
                
                
                String tempDir = System.getProperty("java.io.tmpdir");
                String tempWordPath = tempDir + File.separator + "source_code_" + projectId + ".docx";
                
                byte[] sourceDoc = generateWordDocumentWithType(appName, 
                    completeSourceCode.toString(), "source");
                Files.write(Paths.get(tempWordPath), sourceDoc);
                log.info("项目 {} 初始Word文档已生成", projectId);
                
                
                byte[] sourcePdf = null;
                try {
                    trimWordToMaxPages(tempWordPath, 60);
                    log.info("项目 {} Word文档已裁剪到60页以内", projectId);
                    sourceDoc = Files.readAllBytes(Paths.get(tempWordPath));
                    
                    
                    String tempPdfPath = tempWordPath.replace(".docx", ".pdf");
                    try {
                        if (Files.exists(Paths.get(tempPdfPath))) {
                            sourcePdf = Files.readAllBytes(Paths.get(tempPdfPath));
                            log.info("项目 {} 源代码PDF文档已读取", projectId);
                        } else {
                            log.warn("项目 {} 未找到PDF文件: {}", projectId, tempPdfPath);
                        }
                    } catch (Exception pdfEx) {
                        log.warn("项目 {} 读取PDF文件失败: {}", projectId, pdfEx.getMessage());
                    }
                    
                } catch (Exception trimEx) {
                    log.warn("项目 {} Word裁剪失败，使用原文档: {}", projectId, trimEx.getMessage());
                } finally {
                    
                    try {
                        Files.deleteIfExists(Paths.get(tempWordPath));
                        
                        String tempPdfPath = tempWordPath.replace(".docx", ".pdf");
                        Files.deleteIfExists(Paths.get(tempPdfPath));
                    } catch (Exception ignored) {}
                }
                
                
                if (sourcePdf != null) {
                    String base64Pdf = java.util.Base64.getEncoder().encodeToString(sourcePdf);
                    copyrightFileService.saveGeneratedFile(projectId, "source_code_pdf", 
                        appName + "-源代码文档.pdf", "源代码PDF文档", base64Pdf, currentUser);
                    log.info("项目 {} 源代码PDF文档已保存到数据库", projectId);
                } else {
                    log.warn("项目 {} PDF文档生成失败，未保存", projectId);
                }
                
            } catch (Exception e) {
                log.error("项目 {} 生成源代码Word文档失败", projectId, e);
                
            }
            
            // 5. 生成软著申请表（在完成前生成）
            try {
                log.info("项目 {} 开始生成软著申请表...", projectId);
                copyrightProjectService.updateCurrentStep(projectId, "正在生成软著申请表...");
                
                
                generateAndSaveCopyrightApplicationForm(projectId, appName, currentUser);
                
                log.info("项目 {} 软著申请表生成完成", projectId);
            } catch (Exception e) {
                log.error("项目 {} 生成软著申请表失败", projectId, e);
                
            }
            
            // 6. 更新项目状态为完成（进度会自动更新为100%）
            copyrightProjectService.updateStatus(projectId, "completed");
            copyrightProjectService.updateCurrentStep(projectId, "生成完成");
            copyrightProjectService.updateProgress(projectId, 100);
            
            log.info("项目 {} 生成流程完成", projectId);
            
        } catch (Exception e) {
            log.error("项目 {} 生成流程执行失败", projectId, e);
            copyrightProjectService.updateStatus(projectId, "error");
            copyrightProjectService.updateCurrentStep(projectId, "生成失败: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * 启动进度模拟任务（15分钟内从0%到99%）
     */
    private void startProgressSimulation(String projectId) {
        log.info("启动进度模拟任务，项目ID: {}", projectId);
        CompletableFuture.runAsync(() -> {
            try {
                int totalDurationMs = 15 * 60 * 1000; // 15分钟
                int updateIntervalMs = 5 * 1000; 
                int totalUpdates = totalDurationMs / updateIntervalMs; 
                
                log.info("进度模拟参数 - 总时长: {}ms, 更新间隔: {}ms, 总更新次数: {}", 
                    totalDurationMs, updateIntervalMs, totalUpdates);
                
                for (int i = 1; i <= totalUpdates; i++) {
                    Thread.sleep(updateIntervalMs);
                    
                    
                    CopyrightProject project = copyrightProjectService.getById(projectId);
                    if (project == null) {
                        log.warn("项目 {} 不存在，停止进度模拟", projectId);
                        break;
                    }
                    if (!"generating".equals(project.getStatus())) {
                        log.info("项目 {} 状态已变更为 {}，停止进度模拟", projectId, project.getStatus());
                        break; 
                    }
                    
                    
                    int progress = Math.min(99, (i * 99) / totalUpdates);
                    
                    
                    copyrightProjectService.updateProgress(projectId, progress);
                    log.debug("项目 {} 进度更新: {}% (第{}次更新)", projectId, progress, i);
                    
                    
                    if (progress >= 99) {
                        log.info("项目 {} 进度模拟已达到99%，等待实际生成完成", projectId);
                        break;
                    }
                }
            } catch (InterruptedException e) {
                log.warn("项目 {} 进度模拟任务被中断: {}", projectId, e.getMessage());
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("项目 {} 进度模拟任务异常: {}", projectId, e.getMessage(), e);
            }
        });
    }
    
    /**
     * 同步生成前端代码
     */
    public String generateFrontendCodeSync(String appName, String appPrompt) {
        log.info("开始同步生成前端代码: appName={}", appName);
        
        String prompt = String.format(
            "请为我创建一个名为%s的完整前端HTML界面，要满足以下条件：\n" +
            "1. 必须包含完整的HTML文档结构：<!DOCTYPE html><html lang=\"zh-CN\"><head>...</head><body>...</body></html>\n" +
            "2. 使用Bootstrap 5 CSS框架：<link href=\"https://cdnjs.cloudflare.com/ajax/libs/bootstrap/5.3.0/css/bootstrap.min.css\" rel=\"stylesheet\">\n" +
            "3. 使用Font Awesome图标：<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css\">\n" +
            "4. 使用Bootstrap JS：<script src=\"https://cdnjs.cloudflare.com/ajax/libs/bootstrap/5.3.0/js/bootstrap.bundle.min.js\"></script>\n" +
            "4. 包含完整的CSS样式定义在<style>标签中，实现现代化美观界面\n" +
            "5. **必须包含导航栏和5个主要功能区域**：首页、用户管理、数据分析、系统设置、消息中心\n" +
            "6. **每个功能必须可点击切换**：使用JavaScript实现功能切换，每个功能显示不同的内容\n" +
            "7. **功能按钮要求**：每个功能按钮必须有唯一的id属性，格式为：home-btn, user-btn, data-btn, settings-btn, message-btn\n" +
            "8. **内容区域要求**：每个功能对应的内容区域必须有唯一的id属性，格式为：home-content, user-content, data-content, settings-content, message-content\n" +
            "6. 每个功能区域都要有3-4个可视化的子功能，使用Bootstrap组件（卡片、表格、按钮等）\n" +
            "7. 必须包含这一行：<h4 class='text-center mb-4'><i class='fas fa-industry'></i> %s</h4>\n" +
            "8. 界面要美观、响应式，使用现代化设计风格和合理的色彩搭配\n" +
            "9. 数据分析页面使用纯数字展示，不要图表，用数字和进度条表示\n" +
            "10. 所有文本和注释使用中文\n" +
            "11. **必须包含JavaScript代码**：实现功能切换，包含以下函数：\n" +
            "    - showContent(contentId): 切换显示指定的功能内容\n" +
            "    - 每个功能按钮的点击事件处理\n" +
            "    - 确保默认显示首页内容\n" +
            "12. 确保HTML结构完整且有效，可以直接在浏览器中渲染\n" +
            "13. 控制在合理长度内，但要内容丰富\n" +
            "14. **重要约束**：\n" +
            "    - 直接以<!DOCTYPE html>开头，不要有任何中文说明\n" +
            "    - 直接以</html>结尾，不要有任何中文说明\n" +
            "    - 不要使用```html或```代码块标记\n" +
            "    - 不要在HTML代码前后添加任何解释文字\n" +
            "    - 只返回纯HTML代码，不要任何其他内容\n" +
            "请提供完整的HTML代码。",
            appName, appName
        );
        
        
        String completeCode = callDeepSeekAPIWithHighTokens(prompt, 60000);
        log.info("前端代码同步生成完成: {}", appName);
        
        
        if (!validateCompleteHtml(completeCode)) {
            log.warn("生成的HTML验证失败，尝试修复");
            completeCode = fixCompleteHtml(completeCode, appName);
            
            
            if (!validateCompleteHtml(completeCode)) {
                log.warn("修复失败，使用默认HTML");
                completeCode = generateDefaultCompleteHtml(appName);
            }
        }
        
        return completeCode;
    }
    
    /**
     * 同步生成后端代码 - 分三次生成策略
     */
    public String generateBackendCodeSync(String appName, String frontendCode) {
        log.info("开始分三次生成后端代码: appName={}", appName);
        
        
        log.info("第1次生成：基础架构层（config, models, utils）");
        String part1 = generateBackendPart1(appName, frontendCode);
        log.info("第1次生成完成，长度: {} 字符", part1.length());
        
        
        log.info("第2次生成：中间件和认证层（middleware, validators, auth）");
        String part2 = generateBackendPart2(appName, frontendCode, part1);
        log.info("第2次生成完成，长度: {} 字符", part2.length());
        
        
        log.info("第3次生成：业务和API层（services, api_routes, app）");
        String part3 = generateBackendPart3(appName, frontendCode, part1);
        log.info("第3次生成完成，长度: {} 字符", part3.length());
        
        
        String mergedCode = mergeBackendParts(part1, part2, part3);
        log.info("后端代码合并完成，总长度: {} 字符（约{}行）", mergedCode.length(), mergedCode.split("\n").length);
        
        return mergedCode;
    }
    
    /**
     * 第1次生成：基础架构层（config, models, utils）
     */
    private String generateBackendPart1(String appName, String frontendCode) {
        String prompt = String.format(
            "为%s生成Python Flask后端的基础架构层代码（第1/2部分）。\n\n" +
            "【生成内容】：\n" +
            "1. config.py - 配置管理（200行）\n" +
            "2. models.py - SQLAlchemy数据模型，12-15个表（650行）\n" +
            "3. utils.py - 工具函数（250行）\n\n" +
            "【代码规模】：1000-1100行\n\n" +
            "【重要约束】：\n" +
            "- 使用文件分隔符：# === filename.py ===\n" +
            "- 不生成API路由（下次生成）\n" +
            "- 不生成启动代码（下次生成）\n" +
            "- 所有注释用中文\n" +
            "- 不使用```python标记\n" +
            "- 直接输出代码，不要任何解释\n\n" +
            "【强制格式要求】（必须严格遵守）：\n" +
            "1. **每行代码严格不超过70个字符（包括空格、缩进）**\n" +
            "2. 超过70字符的代码必须使用以下方式换行：\n" +
            "   - 函数定义：参数换行，每个参数一行\n" +
            "   - 函数调用：参数换行，每个参数一行\n" +
            "   - 长字符串：使用括号分行拼接\n" +
            "   - 字典/列表：每个元素一行\n" +
            "3. 示例对比：\n" +
            "   ❌ 错误（85字符）：def create_user_account_with_profile(username, email, phone, address):\n" +
            "   ✓ 正确：def create_user(\n" +
            "            username, \n" +
            "            email):\n" +
            "   ❌ 错误（78字符）：result = some_service.get_user_info_with_details(user_id, include=True)\n" +
            "   ✓ 正确：result = (\n" +
            "            service.get(\n" +
            "            user_id))\n" +
            "4. **检查每一行，确保没有任何一行超过70字符**\n\n" +
            "前端代码参考：\n%s",
            appName,
            frontendCode.length() > 8000 ? frontendCode.substring(0, 8000) : frontendCode
        );
        
        
        return callDeepSeekAPIWithHighTokens(prompt, 16000);
    }
    
    /**
     * 第2次生成：中间件和认证层（middleware, validators, auth）
     */
    private String generateBackendPart2(String appName, String frontendCode, String part1Code) {
        
        String modelsCode = extractModelsFromPart1(part1Code);
        
        String prompt = String.format(
            "基于以下数据模型，为%s生成Python Flask后端的中间件和认证层代码（第2/3部分）。\n\n" +
            "【已有的数据模型】：\n%s\n\n" +
            "【生成内容】：\n" +
            "1. middleware.py - 中间件层（日志记录、异常处理、CORS、请求限流、性能监控、缓存）（300行）\n" +
            "2. validators.py - 数据验证器（所有模型的输入验证、业务规则验证、表单验证）（250行）\n" +
            "3. auth.py - JWT认证和权限管理（登录、注册、令牌刷新、权限装饰器、角色管理）（300行）\n\n" +
            "【代码规模】：800-900行\n\n" +
            "【关键要求】：\n" +
            "- 使用文件分隔符：# === filename.py ===\n" +
            "- middleware要包含完整的日志、异常、CORS、限流中间件\n" +
            "- validators要为每个数据模型提供验证规则\n" +
            "- auth要实现完整的JWT认证流程\n" +
            "- 所有注释用中文\n" +
            "- 不使用```python标记\n" +
            "- 直接输出代码，不要任何解释\n\n" +
            "【强制格式要求】（必须严格遵守）：\n" +
            "1. **每行代码严格不超过70个字符（包括空格、缩进）**\n" +
            "2. 超过70字符的代码必须使用以下方式换行：\n" +
            "   - 函数定义：参数换行，每个参数一行\n" +
            "   - 函数调用：参数换行，每个参数一行\n" +
            "   - 长字符串：使用括号分行拼接\n" +
            "   - 字典/列表：每个元素一行\n" +
            "3. 示例对比：\n" +
            "   ❌ 错误（82字符）：def verify_token_and_permissions(token, secret_key, user_id):\n" +
            "   ✓ 正确：def verify(\n" +
            "            token):\n" +
            "   ❌ 错误（77字符）：logger.info(f\"User {user_id} logged in successfully at {timestamp}\")\n" +
            "   ✓ 正确：logger.info(\n" +
            "            f\"User {id}\")\n" +
            "4. **检查每一行，确保没有任何一行超过70字符**\n\n" +
            "前端代码参考：\n%s",
            appName,
            modelsCode.length() > 2000 ? modelsCode.substring(0, 2000) : modelsCode,
            frontendCode.length() > 6000 ? frontendCode.substring(0, 6000) : frontendCode
        );
        
        
        return callDeepSeekAPIWithHighTokens(prompt, 16000);
    }
    
    /**
     * 第3次生成：业务和API层（services, api_routes, app）
     */
    private String generateBackendPart3(String appName, String frontendCode, String part1Code) {
        
        String modelsCode = extractModelsFromPart1(part1Code);
        
        String prompt = String.format(
            "基于以下数据模型，为%s生成Python Flask后端的业务和API层代码（第3/3部分）。\n\n" +
            "【已有的数据模型】：\n%s\n\n" +
            "【生成内容】：\n" +
            "1. services.py - 业务逻辑层（完整的CRUD操作、业务规则、数据处理、事务管理）（400行）\n" +
            "2. api_routes.py - RESTful API，60-70个端点（包含所有CRUD操作、搜索、统计、导出）（900行）\n" +
            "3. app.py - Flask应用主入口（初始化、路由注册、启动代码、错误处理）（250行）\n\n" +
            "【代码规模】：1400-1550行\n\n" +
            "【关键要求】：\n" +
            "- 使用文件分隔符：# === filename.py ===\n" +
            "- API要与前端功能完全匹配，包含所有CRUD端点\n" +
            "- services要包含完整的业务逻辑\n" +
            "- app.py必须以if __name__ == '__main__': app.run(debug=True)结尾\n" +
            "- 所有注释用中文\n" +
            "- 不使用```python标记\n" +
            "- 直接输出代码，不要任何解释\n\n" +
            "【强制格式要求】（必须严格遵守）：\n" +
            "1. **每行代码严格不超过70个字符（包括空格、缩进）**\n" +
            "2. 超过70字符的代码必须使用以下方式换行：\n" +
            "   - 函数定义：参数换行，每个参数一行\n" +
            "   - 函数调用：参数换行，每个参数一行\n" +
            "   - 装饰器路由：@app.route分行\n" +
            "   - 长字符串：使用括号分行拼接\n" +
            "3. 示例对比：\n" +
            "   ❌ 错误（92字符）：@app.route('/api/users/<int:user_id>/details', methods=['GET', 'POST', 'PUT'])\n" +
            "   ✓ 正确：@app.route(\n" +
            "            '/api/users',\n" +
            "            methods=['GET'])\n" +
            "   ❌ 错误（83字符）：result = UserService.get_user_by_id_with_full_details(user_id, include_all)\n" +
            "   ✓ 正确：result = (\n" +
            "            Service\n" +
            "            .get(id))\n" +
            "4. **特别注意：API路由装饰器也必须遵守70字符限制**\n" +
            "5. **检查每一行，确保没有任何一行超过70字符**\n\n" +
            "前端代码参考：\n%s",
            appName,
            modelsCode.length() > 2000 ? modelsCode.substring(0, 2000) : modelsCode,
            frontendCode.length() > 6000 ? frontendCode.substring(0, 6000) : frontendCode
        );
        
        
        return callDeepSeekAPIWithHighTokens(prompt, 16000);
    }
    
    /**
     * 从第1次生成的代码中提取models.py
     */
    private String extractModelsFromPart1(String part1Code) {
        String modelsMarker = "# === models.py ===";
        int startIdx = part1Code.indexOf(modelsMarker);
        if (startIdx == -1) {
            log.warn("未找到models.py标记，返回空字符串");
            return "";
        }
        
        int nextFileIdx = part1Code.indexOf("# ===", startIdx + modelsMarker.length());
        if (nextFileIdx == -1) {
            return part1Code.substring(startIdx);
        }
        return part1Code.substring(startIdx, nextFileIdx);
    }
    
    /**
     * 合并三部分后端代码
     */
    private String mergeBackendParts(String part1, String part2, String part3) {
        StringBuilder merged = new StringBuilder();
        
        
        merged.append(extractFileFromCode(part1, "config.py"));
        merged.append("\n\n");
        merged.append(extractFileFromCode(part1, "models.py"));
        merged.append("\n\n");
        merged.append(extractFileFromCode(part1, "utils.py"));
        merged.append("\n\n");
        
        
        merged.append(extractFileFromCode(part2, "middleware.py"));
        merged.append("\n\n");
        merged.append(extractFileFromCode(part2, "validators.py"));
        merged.append("\n\n");
        merged.append(extractFileFromCode(part2, "auth.py"));
        merged.append("\n\n");
        
        
        merged.append(extractFileFromCode(part3, "services.py"));
        merged.append("\n\n");
        merged.append(extractFileFromCode(part3, "api_routes.py"));
        merged.append("\n\n");
        
        // ⭐ app.py必须在最后，确保启动代码在末尾
        String appPy = extractFileFromCode(part3, "app.py");
        merged.append(appPy);
        
        // ⭐ 清理多余的if __name__ == '__main__':，确保只有一个
        String finalCode = merged.toString();
        
        
        int mainCount = countOccurrences(finalCode, "if __name__ == '__main__':");
        
        if (mainCount == 0) {
            
            log.warn("⚠️ 警告：后端代码缺少启动代码，添加默认启动代码");
            merged.append("\n\nif __name__ == '__main__':\n    app.run(debug=True)\n");
        } else if (mainCount > 1) {
            
            log.warn("⚠️ 警告：后端代码包含{}个if __name__ == '__main__':，正在清理...", mainCount);
            finalCode = removeRedundantMainBlocks(finalCode);
            merged = new StringBuilder(finalCode);
            log.info("✅ 已清理多余的if __name__ == '__main__':，保留最后一个");
        } else {
            log.info("✅ 后端代码包含正确的启动代码");
        }
        
        return merged.toString();
    }
    
    /**
     * 统计子字符串出现次数
     */
    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
    
    /**
     * 移除多余的if __name__ == '__main__':块，只保留最后一个
     */
    private String removeRedundantMainBlocks(String code) {
        String mainPattern = "if __name__ == '__main__':";
        
        
        java.util.List<Integer> positions = new java.util.ArrayList<>();
        int index = 0;
        while ((index = code.indexOf(mainPattern, index)) != -1) {
            positions.add(index);
            index += mainPattern.length();
        }
        
        if (positions.size() <= 1) {
            return code; 
        }
        
        
        StringBuilder result = new StringBuilder();
        int lastPos = 0;
        
        for (int i = 0; i < positions.size() - 1; i++) {
            int startPos = positions.get(i);
            
            result.append(code, lastPos, startPos);
            
            
            int endPos;
            if (i + 1 < positions.size()) {
                
                endPos = positions.get(i + 1);
                
                while (endPos > startPos && code.charAt(endPos - 1) == '\n') {
                    endPos--;
                }
            } else {
                endPos = code.length();
            }
            
            
            lastPos = endPos;
        }
        
        
        result.append(code.substring(lastPos));
        
        return result.toString();
    }
    
    /**
     * 从代码中提取指定文件
     */
    private String extractFileFromCode(String code, String filename) {
        String marker = "# === " + filename + " ===";
        int startIdx = code.indexOf(marker);
        if (startIdx == -1) {
            log.warn("未找到文件标记: {}", filename);
            return "";
        }
        
        int nextMarker = code.indexOf("# ===", startIdx + marker.length());
        String fileContent = nextMarker == -1 
            ? code.substring(startIdx) 
            : code.substring(startIdx, nextMarker);
        
        return fileContent.trim();
    }
    
    /**
     * 同步生成章节内容
     */
    private String generateChapterSync(int chapterNumber, String appName, String code) {
        log.info("开始同步生成第{}章: appName={}", chapterNumber, appName);
        
        String prompt = generateChapterPrompt(chapterNumber, appName, code);
        
        
        String chapterContent = callDeepSeekChatAPI(prompt, 8000);
        log.info("第{}章同步生成完成: {}, 长度: {}", chapterNumber, appName, chapterContent.length());
        
        return chapterContent;
    }

    /**
     * 获取项目状态
     */
    @GetMapping("/copyright/projects/{projectId}/status")
    public Result<Map<String, Object>> getProjectStatus(@PathVariable String projectId) {
        try {
            String currentUserId = getCurrentUserId();
            String currentUserInfo = getCurrentUserInfo();
            log.info("获取项目状态，项目ID: {}, 用户: {}", projectId, currentUserInfo);
            
            
            CopyrightProject project = copyrightProjectService.getById(projectId);
            if (project == null) {
                return Result.error("项目不存在");
            }
            
            
            if (!"system".equals(currentUserId) && !currentUserId.equals(project.getCreateBy())) {
                log.warn("用户 {} 尝试查看不属于自己的项目状态 {}, 项目创建者: {}", currentUserInfo, projectId, project.getCreateBy());
                return Result.error("无权限查看此项目状态");
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("projectId", projectId);
            result.put("status", project.getStatus());
            result.put("progress", project.getProgress());
            result.put("currentStep", project.getCurrentStep());
            result.put("completedFiles", project.getCompletedFiles());
            result.put("generatingFiles", project.getGeneratingFiles());
            result.put("startTime", project.getStartTime());
            result.put("endTime", project.getEndTime());
            
            return Result.OK("获取项目状态成功", result);
        } catch (Exception e) {
            log.error("获取项目状态失败", e);
            return Result.error("获取项目状态失败: " + e.getMessage());
        }
    }

    /**
     * 删除项目
     */
    @DeleteMapping("/copyright/projects/{projectId}")
    public Result<String> deleteProject(@PathVariable String projectId) {
        try {
            String currentUserId = getCurrentUserId();
            String currentUserInfo = getCurrentUserInfo();
            log.info("删除项目，项目ID: {}, 用户: {}", projectId, currentUserInfo);
            
            
            CopyrightProject project = copyrightProjectService.getById(projectId);
            if (project == null) {
                return Result.error("项目不存在");
            }
            
            
            if (!"system".equals(currentUserId) && !currentUserId.equals(project.getCreateBy())) {
                log.warn("用户 {} 尝试删除不属于自己的项目 {}, 项目创建者: {}", currentUserInfo, projectId, project.getCreateBy());
                return Result.error("无权限删除此项目");
            }
            
            
            copyrightFileService.deleteByProjectId(projectId);
            
            
            copyrightProjectService.removeById(projectId);
            
            return Result.OK("项目删除成功");
        } catch (Exception e) {
            log.error("删除项目失败", e);
            return Result.error("删除项目失败: " + e.getMessage());
        }
    }

    /**
     * 流式生成前端代码 - 使用DeepSeek Reasoner
     */
    @PostMapping(value = "/copyright/generate-frontend-code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateFrontendCode(@RequestBody Map<String, String> request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setContentType("text/event-stream;charset=UTF-8");

        SseEmitter emitter = new SseEmitter(900000L); // 15分钟超时

        CompletableFuture.runAsync(() -> {
            try {
                String appName = request.get("appName");
                String appPrompt = request.get("appPrompt");
                String projectId = request.get("projectId");

                log.info("开始流式一次性生成完整前端代码: appName={}", appName);

                
                sendProgress(emitter, 10, "正在生成完整前端代码...");

                
                String prompt = String.format(
                    "请为我创建一个名为%s的完整前端HTML界面，要满足以下条件：\n" +
                    "1. 必须包含完整的HTML文档结构：<!DOCTYPE html><html lang=\"zh-CN\"><head>...</head><body>...</body></html>\n" +
                    "2. 使用Bootstrap 5 CSS框架：<link href=\"https://cdnjs.cloudflare.com/ajax/libs/bootstrap/5.3.0/css/bootstrap.min.css\" rel=\"stylesheet\">\n" +
                    "3. 使用Font Awesome图标：<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css\">\n" +
                    "4. 使用Bootstrap JS：<script src=\"https://cdnjs.cloudflare.com/ajax/libs/bootstrap/5.3.0/js/bootstrap.bundle.min.js\"></script>\n" +
                    "5. 包含完整的CSS样式定义在<style>标签中，实现现代化美观界面\n" +
                    "6. **必须包含导航栏和5个主要功能区域**：首页、用户管理、数据分析、系统设置、消息中心\n" +
                    "7. **每个功能必须可点击切换**：使用JavaScript实现功能切换，每个功能显示不同的内容\n" +
                    "8. **功能按钮要求**：每个功能按钮必须有唯一的id属性，格式为：home-btn, user-btn, data-btn, settings-btn, message-btn\n" +
                    "9. **内容区域要求**：每个功能对应的内容区域必须有唯一的id属性，格式为：home-content, user-content, data-content, settings-content, message-content\n" +
                    "10. 每个功能区域都要有3-4个可视化的子功能，使用Bootstrap组件（卡片、表格、按钮等）\n" +
                    "11. 必须包含这一行：<h4 class='text-center mb-4'><i class='fas fa-industry'></i> %s</h4>\n" +
                    "12. 界面要美观、响应式，使用现代化设计风格和合理的色彩搭配\n" +
                    "13. 数据分析页面使用纯数字展示，不要图表，用数字和进度条表示\n" +
                    "14. 所有文本和注释使用中文\n" +
                    "15. **必须包含JavaScript代码**：实现功能切换，包含以下函数：\n" +
                    "    - showContent(contentId): 切换显示指定的功能内容\n" +
                    "    - 每个功能按钮的点击事件处理\n" +
                    "    - 确保默认显示首页内容\n" +
                    "16. 确保HTML结构完整且有效，可以直接在浏览器中渲染\n" +
                    "17. 控制在合理长度内，但要内容丰富\n" +
                    "18. **重要约束**：\n" +
                    "    - 直接以<!DOCTYPE html>开头，不要有任何中文说明\n" +
                    "    - 直接以</html>结尾，不要有任何中文说明\n" +
                    "    - 不要使用```html或```代码块标记\n" +
                    "    - 不要在HTML代码前后添加任何解释文字\n" +
                    "    - 只返回纯HTML代码，不要任何其他内容\n" +
                    "请提供完整的HTML代码。",
                    appName, appName
                );

                
                String completeCode = callDeepSeekAPIWithHighTokens(prompt, 60000);
                // log.info("前端代码一次性生成完成: {}, 长度: {}", appName, completeCode.length());
                log.info("前端代码一次性生成完成: {}", appName);

                
                if (!validateCompleteHtml(completeCode)) {
                    log.warn("生成的HTML验证失败，尝试修复");
                    completeCode = fixCompleteHtml(completeCode, appName);

                    
                    if (!validateCompleteHtml(completeCode)) {
                        log.warn("修复失败，使用默认HTML");
                        completeCode = generateDefaultCompleteHtml(appName);
                    }
                }

                
                if (projectId != null && !projectId.isEmpty()) {
                    copyrightFileService.saveGeneratedFile(
                        projectId, 
                        "frontend_code", 
                        "前端界面代码.html", 
                        "前端界面代码", 
                        completeCode, 
                        getCurrentUserId()
                    );
                    log.info("前端代码已保存到数据库: projectId={}", projectId);
                }

                
                sendProgress(emitter, 90, "前端代码生成完成，正在验证...");

                
                Map<String, Object> completeData = new HashMap<>();
                completeData.put("progress", 100);
                completeData.put("completed", true);
                completeData.put("fullCode", completeCode);
                completeData.put("stage", "前端代码生成完成");
                emitter.send(SseEmitter.event().name("data").data(completeData));
                emitter.complete();

                                        // log.info("流式前端代码生成完成: appName={}, 总长度={}", appName, completeCode.length());
                log.info("流式前端代码生成完成: appName={}", appName);

            } catch (Exception e) {
                log.error("流式生成前端代码失败", e);
                sendError(emitter, "生成前端代码失败: " + e.getMessage());
            }
        });

        return emitter;
    }

    /**
     * 流式生成后端代码 - 使用DeepSeek Chat
     */
    @PostMapping(value = "/copyright/generate-backend-code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateBackendCode(@RequestBody Map<String, String> request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setContentType("text/event-stream;charset=UTF-8");

        SseEmitter emitter = new SseEmitter(900000L); // 15分钟超时

        CompletableFuture.runAsync(() -> {
            try {
                String appName = request.get("appName");
                String frontendCode = request.get("code");
                String projectId = request.get("projectId");

                log.info("开始流式调用AI生成后端代码: appName={}", appName);

                
                sendProgress(emitter, 10, "正在生成后端代码...");

                
                String prompt = String.format(
                    "要写一个%s的后端代码，请你根据以下前端代码设计功能界面，提供给我后端的python代码，" +
                    "代码要丰富详细，至少1000行以上，包含完整的项目结构。" +
                    "不要在开头有任何的说明或评论，直接开始提供代码。不要在结尾有任何的代码说明或运行方式的解释。" +
                    "不要在开头有任何的说明或评论，直接开始提供代码。不要在结尾有任何的代码说明或运行方式的解释！这一点优先级很高，非常重要" +
                    "一定要与前端的功能进行匹配。前端代码使用了Bootstrap和JavaScript，后端应该支持这些功能。\n\n" +
                    "要求包含以下内容：\n" +
                    "1. 使用Flask框架，包含完整的项目结构\n" +
                    "2. 包含数据库模型（使用SQLAlchemy），至少5-8个数据表模型\n" +
                    "3. 包含完整的RESTful API接口，支持CRUD操作，至少20个API端点\n" +
                    "4. 包含用户认证和权限管理，JWT token认证\n" +
                    "5. 包含数据验证和错误处理，使用marshmallow进行数据序列化\n" +
                    "6. 包含配置文件和启动脚本\n" +
                    "7. 包含中间件：CORS、日志记录、异常处理\n" +
                    "8. 包含业务逻辑层，服务层代码\n" +
                    "9. 包含数据库迁移脚本\n" +
                    "10. 包含API文档生成（使用Flask-RESTX）\n" +
                    "11. 包含缓存机制（Redis）\n" +
                    "12. 包含文件上传处理\n" +
                    "前端代码：\n%s\n\n" +
                    "**重要约束**：\n" +
                    "- 直接以import开头，不要有任何中文说明\n" +
                    "- 直接以if __name__ == '__main__': app.run(debug=True)结尾，不要有任何中文说明\n" +
                    "- 不要使用```python或```代码块标记\n" +
                    "- 不要在Python代码前后添加任何解释文字\n" +
                    "- 只返回纯Python代码，不要任何其他内容\n" +
                    "使用Python和Flask框架创建后端，确保支持前端所有功能。代码应该完整可运行。" +
                    "重要要求：所有代码注释必须使用中文，包括函数说明、变量注释、逻辑说明等。",
                    appName, frontendCode != null && frontendCode.length() > 3000 ? frontendCode.substring(0, 3000) + "..." : frontendCode
                );

                
                String result = callDeepSeekAPI(prompt);
                // log.info("AI后端代码生成完成: {}, 长度: {}", appName, result.length());
                log.info("AI后端代码生成完成: {}", appName);

                
                if (projectId != null && !projectId.isEmpty()) {
                    copyrightFileService.saveGeneratedFile(
                        projectId, 
                        "backend_code", 
                        "后端服务代码.py", 
                        "后端服务代码", 
                        result, 
                        getCurrentUserId()
                    );
                    log.info("后端代码已保存到数据库: projectId={}", projectId);
                }

                
                Map<String, Object> completeData = new HashMap<>();
                completeData.put("progress", 100);
                completeData.put("completed", true);
                completeData.put("fullCode", result);
                completeData.put("stage", "后端代码生成完成");
                emitter.send(SseEmitter.event().name("data").data(completeData));
                emitter.complete();

            } catch (Exception e) {
                log.error("流式生成后端代码失败", e);
                sendError(emitter, "生成后端代码失败: " + e.getMessage());
            }
        });

        return emitter;
    }

    /**
     * 流式生成文档章节 - 使用DeepSeek Chat
     */
    @PostMapping(value = "/copyright/generate-chapter", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateChapter(@RequestBody Map<String, String> request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setContentType("text/event-stream;charset=UTF-8");

        SseEmitter emitter = new SseEmitter(900000L); // 15分钟超时

        CompletableFuture.runAsync(() -> {
            try {
                String appName = request.get("appName");
                String chapterNum = request.get("chapterNum");
                String code = request.get("code");
                String projectId = request.get("projectId");

                int chapterNumber = Integer.parseInt(chapterNum);
                String chapterName = getChapterTitle(chapterNum);

                log.info("开始流式调用AI生成文档章节: appName={}, chapter={}", appName, chapterNumber);

                
                sendProgress(emitter, 10, "正在生成" + chapterName + "...");

                String prompt = generateChapterPrompt(chapterNumber, appName, code);
                String result = callDeepSeekAPI(prompt);
                
                log.info("AI说明书第{}章生成完成: {}, 长度: {}", chapterNumber, appName, result.length());

                
                if (projectId != null && !projectId.isEmpty()) {
                    copyrightFileService.saveGeneratedFile(
                        projectId, 
                        "chapter" + chapterNum, 
                        generateChapterFileName(chapterNum), 
                        chapterName, 
                        result, 
                        getCurrentUserId()
                    );
                    log.info("第{}章已保存到数据库: projectId={}", chapterNumber, projectId);
                }

                
                Map<String, Object> completeData = new HashMap<>();
                completeData.put("progress", 100);
                completeData.put("completed", true);
                completeData.put("chapterContent", result);
                completeData.put("stage", chapterName + "生成完成");
                emitter.send(SseEmitter.event().name("data").data(completeData));
                emitter.complete();

            } catch (Exception e) {
                log.error("流式生成文档章节失败", e);
                sendError(emitter, "生成文档章节失败: " + e.getMessage());
            }
        });

        return emitter;
    }

    /**
     * 下载项目的所有材料
     */
    @GetMapping("/copyright/download-materials/{projectId}")
    public void downloadProjectMaterials(@PathVariable String projectId, HttpServletResponse response) {
        try {
            String currentUserId = getCurrentUserId();
            String currentUserInfo = getCurrentUserInfo();
            log.info("开始下载项目材料，项目ID: {}, 用户: {}", projectId, currentUserInfo);
            
            
            CopyrightProject project = copyrightProjectService.getById(projectId);
            if (project == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "项目不存在");
                return;
            }
            
            
            if (!"system".equals(currentUserId) && !currentUserId.equals(project.getCreateBy())) {
                log.warn("用户 {} 尝试下载不属于自己的项目材料 {}, 项目创建者: {}", currentUserInfo, projectId, project.getCreateBy());
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "无权限下载此项目材料");
                return;
            }
            
            if (!"completed".equals(project.getStatus())) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "项目尚未完成生成");
                return;
            }
            
            
            List<CopyrightFile> files = copyrightFileService.list(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<CopyrightFile>()
                    .eq("project_id", projectId)
                    .eq("status", "completed")
            );
            
            if (files.isEmpty()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "没有找到已完成的文件");
                return;
            }
            
            
            response.setContentType("application/zip");
            response.setCharacterEncoding("UTF-8");
            String fileName = project.getAppName() + "-软著申请材料.zip";
            response.setHeader("Content-Disposition", "attachment; filename=\"" + 
                URLEncoder.encode(fileName, StandardCharsets.UTF_8) + "\"");
            
            
            generateCompleteZipPackage(project, files, response);
            
            log.info("项目材料下载完成: {}", projectId);
            
        } catch (Exception e) {
            log.error("下载项目材料失败: projectId={}", projectId, e);
            try {
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "下载失败: " + e.getMessage());
                }
            } catch (IOException ioException) {
                log.error("发送错误响应失败", ioException);
            }
        }
    }
    
    /**
     * 生成完整的ZIP压缩包 - 包含3个Word文档
     */
    private void generateCompleteZipPackage(CopyrightProject project, List<CopyrightFile> files, HttpServletResponse response) throws IOException {
        try {
            
            Map<String, List<CopyrightFile>> filesByType = files.stream()
                .collect(Collectors.groupingBy(CopyrightFile::getFileType));
            
            try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8)) {
                
                // 1. 生成软件说明书文档 - 直接使用数据库内容
                StringBuilder manualContent = new StringBuilder();
                
                
                List<String> chapterTypes = Arrays.asList("chapter1", "chapter2", "chapter3", "chapter4");
                boolean hasContent = false;
                for (String chapterType : chapterTypes) {
                    List<CopyrightFile> chapterFiles = filesByType.get(chapterType);
                    if (chapterFiles != null && !chapterFiles.isEmpty()) {
                        CopyrightFile chapterFile = chapterFiles.get(0);
                        manualContent.append(chapterFile.getTitle()).append("\n\n");
                        manualContent.append(chapterFile.getContent()).append("\n\n");
                        hasContent = true;
                    }
                }
                
                if (hasContent) {
                    
                    String tempScreenshotDir = prepareScreenshotsForPython(filesByType, project.getAppName());
                    
                    // log.info("开始生成软件说明书，内容长度: {} 字符", manualContent.length());
                    log.info("开始生成软件说明书");
                    
                    byte[] manualDoc = generateWordDocumentWithScreenshots(project.getAppName(), 
                        manualContent.toString(), "manual", tempScreenshotDir);
                    addFileToZip(zos, project.getAppName() + "-软件说明书.docx", manualDoc);
                    log.info("软件说明书生成完成");
                    
                    
                    cleanupTempScreenshots(tempScreenshotDir);
                } else {
                    log.warn("未找到章节内容，跳过软件说明书生成");
                }
                
                // 2. 添加数据库中生成的源代码文件
                addGeneratedSourceCodeToZip(zos, filesByType, project.getAppName(), project.getId());
                
                // 3. 添加软著申请表 - 优先从数据库读取已生成的文档
                List<CopyrightFile> applicationFiles = filesByType.get("copyright_application");
                if (applicationFiles != null && !applicationFiles.isEmpty()) {
                    
                    log.info("从数据库读取已生成的软著申请表");
                    CopyrightFile appFile = applicationFiles.get(0);
                    String base64Doc = appFile.getContent();
                    byte[] infoDoc = java.util.Base64.getDecoder().decode(base64Doc);
                    addFileToZip(zos, project.getAppName() + "-软著申请表.docx", infoDoc);
                    log.info("软著申请表已添加（从数据库读取）");
                } else {
                    
                    log.warn("数据库中未找到已生成的软著申请表，执行实时生成（降级方案）");
                    
                    /* ========== 以下是原来的实时生成逻辑（降级方案） ========== */
                    StringBuilder infoContent = new StringBuilder();
                    infoContent.append("软件全称：").append(project.getAppName()).append("\n");
                    infoContent.append("软件简称：").append(project.getAppName()).append("\n");
                    infoContent.append("版本号：V1.0\n");
                    infoContent.append("开发完成日期：").append(project.getCreateTime() != null ? 
                        project.getCreateTime().toString().substring(0, 10) : "2025-01-01").append("\n");
                    infoContent.append("首次发表日期：").append(project.getCreateTime() != null ? 
                        project.getCreateTime().toString().substring(0, 10) : "2025-01-01").append("\n");
                    
                    
                    Map<String, Object> extractedInfo = getSoftwareInfoFromDatabase(filesByType);
                    
                    
                    if (extractedInfo.get("purpose") == null || extractedInfo.get("purpose").toString().trim().isEmpty()) {
                        log.info("数据库中无软著信息，开始AI生成...");
                        String chapter1Content = extractChapter1Content(filesByType);
                        Map<String, String> aiGeneratedInfo = extractSoftwareInfoFromChapter1(chapter1Content, project.getAppName());
                        
                        
                        extractedInfo.putAll(aiGeneratedInfo);
                        
                        
                        saveSoftwareInfoToDatabase(filesByType, aiGeneratedInfo);
                    } else {
                        log.info("从数据库中获取软著信息成功，跳过AI生成");
                    }
                    
                    infoContent.append("软件用途：").append(extractedInfo.get("purpose")).append("\n");
                    infoContent.append("面向领域/行业：").append(extractedInfo.get("domain")).append("\n");
                    infoContent.append("软件主要功能：").append(extractedInfo.get("functions")).append("\n");
                    infoContent.append("软件技术特点：").append(extractedInfo.get("features")).append("\n");
                    
                    // 🎲 每次都生成随机源代码行数（8000-30000之间）
                    int totalLines;
                    
                    /*
                    Integer dbSourceCodeLines = (Integer) extractedInfo.get("sourceCodeLines");
                    if (dbSourceCodeLines != null && dbSourceCodeLines > 0) {
                        totalLines = dbSourceCodeLines;
                        log.info("使用数据库中的源代码行数: {}", totalLines);
                    } else {
                    */
                        
                        java.util.Random random = new java.util.Random();
                        totalLines = 8000 + random.nextInt(22001);
                        log.info("生成随机源代码行数: {}", totalLines);
                        
                        
                        // saveSourceCodeLinesToDatabase(filesByType, totalLines);
                    // }
                    
                    infoContent.append("源程序量：").append(totalLines).append("行\n");
                    infoContent.append("开发语言：JavaScript、Python、HTML\n");
                    
                    log.info("开始生成软著申请表");
                    
                    byte[] infoDoc = generateWordDocumentWithType(project.getAppName() + "-软著申请表", 
                        infoContent.toString(), "info");
                    addFileToZip(zos, project.getAppName() + "-软著申请表.docx", infoDoc);
                    log.info("软著申请表生成完成（降级方案）");
                    /* ========== 降级方案结束 ========== */
                }
                
             
                
                                zos.finish();
        }
        
    } catch (Exception e) {
        log.error("生成ZIP压缩包失败", e);
        throw new IOException("生成ZIP压缩包失败: " + e.getMessage(), e);
    }
}


    

    

    

    


    /**
     * 裁剪Word文档到指定页数
     */
    private void trimWordToMaxPages(String wordFilePath, int maxPages) throws Exception {
        
        String pythonScript = getPythonScriptPath("word_trimmer.py");
        
        
        String pythonCmd = detectPythonCommand();
        
        ProcessBuilder pb = new ProcessBuilder(
            pythonCmd,
            pythonScript,
            wordFilePath,
            String.valueOf(maxPages)
        );
        pb.directory(new java.io.File("."));
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("Word裁剪: {}", line);
            }
        }
        
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Word文档裁剪失败，退出码: " + exitCode);
        }
        
        log.info("Word文档已裁剪到{}页以内", maxPages);
    }
    
    /**
     * 自动检测Python命令（跨平台兼容）
     * Linux优先使用python3，Windows使用python
     */
    private String detectPythonCommand() {
        String os = System.getProperty("os.name").toLowerCase();
        
        // Linux/Unix系统优先尝试python3
        if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
            if (isPythonCommandAvailable("python3")) {
                return "python3";
            }
        }
        
        
        if (isPythonCommandAvailable("python")) {
            return "python";
        }
        
        
        log.warn("未检测到可用的Python命令，将使用默认值 python3");
        return "python3";
    }
    
    /**
     * 检查Python命令是否可用
     */
    private boolean isPythonCommandAvailable(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version")
                .redirectErrorStream(true)
                .start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 智能获取Python脚本路径（兼容多种部署方式）
     * 优先级：
     * 1. Docker容器内提取的脚本路径（/app/max-serve/...）
     * 2. 开发环境相对路径（max-serve/...）
     * 3. 从classpath提取到临时文件
     */
    private String getPythonScriptPath(String scriptName) throws IOException {
        
        String dockerPath = "/app/max-serve/max-module-auth/max-module-auth-start/src/main/resources/scripts/" + scriptName;
        File dockerFile = new File(dockerPath);
        if (dockerFile.exists()) {
            log.info("使用Docker容器内脚本: {}", dockerPath);
            return dockerPath;
        }
        
        
        String devPath = String.join(File.separator, "max-serve", "max-module-auth", 
            "max-module-auth-start", "src", "main", "resources", "scripts", scriptName);
        File devFile = new File(devPath);
        if (devFile.exists()) {
            log.info("使用开发环境脚本: {}", devPath);
            return devPath;
        }
        
        
        log.info("从JAR包提取Python脚本: {}", scriptName);
        try (InputStream is = getClass().getResourceAsStream("/scripts/" + scriptName)) {
            if (is == null) {
                throw new IOException("无法从classpath找到脚本: /scripts/" + scriptName);
            }
            
            
            String tempDir = System.getProperty("java.io.tmpdir");
            File tempScript = new File(tempDir, scriptName);
            
            
            if (tempScript.exists() && (System.currentTimeMillis() - tempScript.lastModified() < 3600000)) {
                log.info("使用缓存的临时脚本: {}", tempScript.getAbsolutePath());
                return tempScript.getAbsolutePath();
            }
            
            
            Files.copy(is, tempScript.toPath(), StandardCopyOption.REPLACE_EXISTING);
            tempScript.setExecutable(true);
            log.info("Python脚本已提取到临时文件: {}", tempScript.getAbsolutePath());
            return tempScript.getAbsolutePath();
        }
    }
    
    /**
     * 根据文档类型生成Word文档
     */
    private byte[] generateWordDocumentWithType(String title, String content, String docType) throws IOException {
        
        if (content.length() > 50000) {
            log.warn("内容过长({} 字符)，进行分段处理", content.length());
            return generateLargeWordDocumentWithType(title, content, docType);
        } else {
            return generateSimpleWordDocumentWithType(title, content, docType);
        }
    }
    


    /**
     * 带类型的大文档处理
     */
    private byte[] generateLargeWordDocumentWithType(String title, String content, String docType) throws IOException {
        try {
            
            // log.info("生成完整文档，内容长度: {} 字符", content.length());
            log.info("生成完整文档");
            return generateSimpleWordDocumentWithType(title, content, docType);
            
        } catch (Exception e) {
            log.error("生成大文档失败，回退到简单处理", e);
            return generateSimpleWordDocumentWithType(title, content, docType);
        }
    }
    
    /**
     * 生成带截图的Word文档
     */
    private byte[] generateWordDocumentWithScreenshots(String title, String content, String docType, String screenshotDir) throws IOException {
        try {
            log.info("生成Word文档 - 标题: {}, 内容长度: {} 字符, 文档类型: {}, 截图目录: {}", title, content.length(), docType, screenshotDir);
            
            
            String timestamp = String.valueOf(System.currentTimeMillis());
            String tempDir = System.getProperty("java.io.tmpdir");
            String contentTempFile = tempDir + File.separator + "content_" + timestamp + ".txt";
            String outputPath = tempDir + File.separator + "word_" + timestamp + ".docx";
            
            
            Files.write(Paths.get(contentTempFile), content.getBytes(StandardCharsets.UTF_8));
            log.info("内容已写入临时文件: {}, 文件大小: {} 字节", contentTempFile, new java.io.File(contentTempFile).length());
            
            
            
            String pythonScript = getPythonScriptPath("word_generator.py");
            
            
            String finalOutputPath = outputPath;
            if ("source".equals(docType)) {
                finalOutputPath = outputPath.replace(".docx", "_source_code.docx");
            } else if ("info".equals(docType)) {
                finalOutputPath = outputPath.replace(".docx", "_software_info.docx");
            }
            
            
            String pythonCmd = CrossPlatformUtil.getPythonCommand();
            ProcessBuilder pb = new ProcessBuilder(pythonCmd, pythonScript, title, contentTempFile, finalOutputPath, screenshotDir);
            
            pb.directory(new java.io.File("."));
            
            // log.info("执行Python脚本: python {} {} {} {} {} (文档类型: {}, 截图目录: {})", 
            //     pythonScript, title, contentTempFile, finalOutputPath, screenshotDir, docType);
            log.info("执行Python脚本生成Word文档: {}", title);
            Process process = pb.start();
            
            
            try (java.io.BufferedReader errorReader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String errorLine;
                StringBuilder errorOutput = new StringBuilder();
                while ((errorLine = errorReader.readLine()) != null) {
                    errorOutput.append(errorLine).append("\n");
                }
                if (errorOutput.length() > 0) {
                    log.error("Python脚本错误输出: {}", errorOutput.toString());
                }
            }
            
            int exitCode = process.waitFor();
            log.info("Python脚本退出码: {}", exitCode);
            
            if (exitCode == 0) {
                
                byte[] docBytes = Files.readAllBytes(Paths.get(finalOutputPath));
                
                
                Files.deleteIfExists(Paths.get(contentTempFile));
                Files.deleteIfExists(Paths.get(finalOutputPath));
                
                return docBytes;
            } else {
                log.warn("Python脚本生成Word文档失败，使用纯文本格式");
                return generateSimpleWordDocumentWithType(title, content, docType);
            }
        } catch (Exception e) {
            log.error("生成Word文档失败: {}", e.getMessage(), e);
            return generateSimpleWordDocumentWithType(title, content, docType);
        }
    }

    /**
     * 带类型的简单Word文档生成
     */
    private byte[] generateSimpleWordDocumentWithType(String title, String content, String docType) throws IOException {
        try {
            // log.info("生成Word文档 - 标题: {}, 内容长度: {} 字符, 文档类型: {}", title, content.length(), docType);
            log.info("生成Word文档 - 标题: {}, 文档类型: {}", title, docType);
            
            
            String timestamp = String.valueOf(System.currentTimeMillis());
            String tempDir = System.getProperty("java.io.tmpdir");
            String contentTempFile = tempDir + File.separator + "content_" + timestamp + ".txt";
            String outputPath = tempDir + File.separator + "word_" + timestamp + ".docx";
            
            
            Files.write(Paths.get(contentTempFile), content.getBytes(StandardCharsets.UTF_8));
            log.info("内容已写入临时文件: {}, 文件大小: {} 字节", contentTempFile, new java.io.File(contentTempFile).length());
            
            
            
            String pythonScript = getPythonScriptPath("word_generator.py");
            
            
            String finalOutputPath = outputPath;
            if ("source".equals(docType)) {
                finalOutputPath = outputPath.replace(".docx", "_source_code.docx");
            } else if ("info".equals(docType)) {
                finalOutputPath = outputPath.replace(".docx", "_software_info.docx");
            }
            
            
            String pythonCmd = CrossPlatformUtil.getPythonCommand();
            ProcessBuilder pb = new ProcessBuilder(pythonCmd, pythonScript, title, contentTempFile, finalOutputPath);
            
            pb.directory(new java.io.File("."));
            
            // log.info("执行Python脚本: python {} {} {} {} (文档类型: {})", pythonScript, title, contentTempFile, finalOutputPath, docType);
            log.info("执行Python脚本生成Word文档: {}", title);
            Process process = pb.start();
            
            
            try (java.io.BufferedReader errorReader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String errorLine;
                StringBuilder errorOutput = new StringBuilder();
                while ((errorLine = errorReader.readLine()) != null) {
                    errorOutput.append(errorLine).append("\n");
                }
                if (errorOutput.length() > 0) {
                    log.error("Python脚本错误输出: {}", errorOutput.toString());
                }
            }
            
            
            int exitCode = process.waitFor();
            log.info("Python脚本退出码: {}", exitCode);
            if (exitCode == 0) {
                
                java.io.File outputFile = new java.io.File(finalOutputPath);
                if (outputFile.exists()) {
                    byte[] docBytes = java.nio.file.Files.readAllBytes(outputFile.toPath());
                    
                    outputFile.delete();
                    new java.io.File(contentTempFile).delete();
                    return docBytes;
                }
            }
            
            
            new java.io.File(contentTempFile).delete();
            
            
            log.warn("Python脚本生成Word文档失败，使用纯文本格式");
            return (title + "\n\n" + content).getBytes(StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            log.error("生成Word文档失败", e);
            return (title + "\n\n" + content).getBytes(StandardCharsets.UTF_8);
        }
    }




    

    
    /**
     * 添加文件到ZIP包（字符串内容）
     */
    private void addFileToZip(ZipOutputStream zos, String fileName, String content) throws IOException {
        
        ZipEntry entry = new ZipEntry(fileName);
        entry.setComment("UTF-8编码文件");
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /**
     * 添加文件到ZIP包（字节数组内容）
     */
    private void addFileToZip(ZipOutputStream zos, String fileName, byte[] content) throws IOException {
        
        ZipEntry entry = new ZipEntry(fileName);
        entry.setComment("UTF-8编码文件");
        zos.putNextEntry(entry);
        zos.write(content);
        zos.closeEntry();
    }

    // ========== 私有方法 - 与AIService.java保持一致 ==========

    /**
     * 调用DeepSeek API - 使用低成本模型
     */
    private String callDeepSeekAPI(String prompt) {
        return callDeepSeekAPIWithModel(prompt, LOW_COST_MODEL, 8192);
    }

    /**
     * 调用DeepSeek API - 支持高Token限制，使用高成本模型（仅前端代码生成专用）
     */
    private String callDeepSeekAPIWithHighTokens(String prompt, int maxTokens) {
        return callDeepSeekAPIWithModel(prompt, HIGH_COST_MODEL, maxTokens);
    }

    /**
     * 调用DeepSeek Chat API (用于后端代码和文档生成)
     */
    public String callDeepSeekChatAPI(String prompt, int maxTokens) {
        return callDeepSeekAPIWithModel(prompt, LOW_COST_MODEL, maxTokens);
    }

    /**
     * 调用DeepSeek API - 通用方法，支持指定模型
     */
    private String callDeepSeekAPIWithModel(String prompt, String model, int maxTokens) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);
            
            requestBody.put("messages", messages);

            
            int finalMaxTokens;
            if (HIGH_COST_MODEL.equals(model)) {
                finalMaxTokens = Math.min(maxTokens, 40000); // DeepSeek Reasoner最大40K tokens
            } else {
                finalMaxTokens = Math.min(maxTokens, 8192);  // DeepSeek Chat最大8K tokens
            }

            requestBody.put("max_tokens", finalMaxTokens);
            requestBody.put("temperature", 0.7);

            log.info("调用DeepSeek API，模型: {}，max_tokens: {}", model, finalMaxTokens);

            
            Duration timeout = HIGH_COST_MODEL.equals(model) ?
                Duration.ofMinutes(15) :  
                Duration.ofMinutes(10);   

            Mono<String> responseMono = webClient.post()
                    .uri(baseUrl + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout);

            String response = responseMono.block();
            log.info("DeepSeek API响应长度: {} 字符", response != null ? response.length() : 0);

            
            JsonNode jsonNode = objectMapper.readTree(response);
            JsonNode choices = jsonNode.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.get("message");
                if (message != null) {
                    JsonNode content = message.get("content");
                    if (content != null) {
                        String result = content.asText();
                        // log.info("生成内容长度: {} 字符", result.length());
                        return result;
                    }
                }
            }

            throw new RuntimeException("无法解析API响应");
        } catch (Exception e) {
            log.error("调用DeepSeek API失败", e);
            throw new RuntimeException("AI服务调用失败: " + e.getMessage());
        }
    }

    /**
     * 同步生成文档章节
     */
    public String generateDocumentChapterSync(int chapterNum, String appName, String code) {
        log.info("开始同步生成第{}章: appName={}", chapterNum, appName);
        String prompt = generateChapterPrompt(chapterNum, appName, code);
        return callDeepSeekChatAPI(prompt, 8000);
    }

    /**
     * 生成章节提示词 - 与AIService.java完全一致
     */
    public String generateChapterPrompt(int chapterNum, String appName, String code) {
        String codeRef = code != null && code.length() > 10000 ? code.substring(0, 10000) : code;
        
        switch (chapterNum) {
            case 1:
                return String.format(
                    "请基于所提供的代码，编写一篇连贯流畅的'第一章 系统概述'，约1000字。这是计算机软件著作权的软件说明书" +
                    "的重要组成部分。请从程序用途、功能概述、运行环境三个方面进行论述，要有第一章，1.1等字眼，要符合论文的写作要求。" +
                    "内容应采用连贯的叙述性段落，而非过多的要点列举，每个段落应当有明确的中心思想并自然过渡到下一段。使用专业但平实的语言，多用中文词汇和表达方式，" +
                    "减少英文术语，必要时提供中文解释。确保内容符合技术文档的专业性，同时便于非技术人员理解。只需要讲说明书内容即可，不用加任何说明，后端是使用python和mysql\n\n" +
                    "以下是代码参考：\n%s",
                    codeRef
                );
            case 2:
                return String.format(
                    "请基于所提供的代码，编写一篇连贯流畅的'第二章 程序建立过程'，约1500字。这是计算机软" +
                    "件著作权的软件说明书的重要组成部分。请包含软件基础、环境配置流程、主程序开发三个小节，要有第二章，2.1等字眼，要符" +
                    "合论文的写作要求。内容应以流畅自然的中文叙述为主，避免过多的分点列举和代码展示。各部分之间要有自然的过渡，每个段落要有明确的中心" +
                    "思想，段落间要有逻辑衔接。重点描述开发思路、模块划分和实现方法，展现设计理念，并且在适当的时候要展示具体代码（可以从代码中摘录，也可以根据系统逻辑编写合理的示例代码或伪代码）。" +
                    "使用丰富的中文表达方式，减少专业术语堆砌，必要时对技术术语进行简明解释。只需要讲说明书内容即可，不用加任何说明，后端是使用python和mysql\n\n" +
                    "以下是代码参考：\n%s",
                    codeRef
                );
            case 3:
                return String.format(
                    "请基于所提供的完整前后端代码，编写'第三章 程序功能介绍'。这是计算机软件著作权的软件说明书的重要组成部分。\n\n" +
                    "【章节结构】：\n" +
                    "第三章 程序功能介绍\n" +
                    "3.1 系统总体功能概述\n" +
                    "3.2 首页功能\n" +
                    "3.3 用户管理功能\n" +
                    "3.4 数据分析功能\n" +
                    "3.5 系统设置功能\n" +
                    "3.6 消息中心功能\n\n" +
                    "【核心原则 - 最重要】：\n" +
                    "**完全根据实际代码编写，不要编造功能！**\n" +
                    "- 仔细阅读代码，理解每个功能的真实实现\n" +
                    "- 3.2-3.6每个小节的内容长度根据功能复杂度自由决定，简单功能可以短一些（100-150字），复杂功能可以长一些（250-300字）\n" +
                    "- **必要时可以分段**，不要强行写成一大段，按照功能逻辑自然分段即可\n" +
                    "- 语言要自然流畅，不要机械化，不要套模板\n\n" +
                    "【内容编写要求】：\n" +
                    "1. **3.1节（系统总体功能概述）**：\n" +
                    "   - 简要概述系统整体定位和核心价值\n" +
                    "   - 列举系统包含的五大功能模块\n\n" +
                    "2. **3.2-3.6节（五大功能模块）**：\n" +
                    "   - 根据代码真实情况编写，不要编造不存在的功能\n" +
                    "   - 可以自然分段（2-3段都可以），不要强制写成一大段\n" +
                    "   - 不要使用\"功能描述\"、\"界面展示\"、\"用户操作\"、\"后端实现\"等固定小标题\n" +
                    "   - 内容包括：功能定位、界面布局（Bootstrap组件）、用户操作流程、后端逻辑、数据存储\n" +
                    "   - 可以展示关键代码片段（5-8行），但必须是代码中真实存在的，或根据真实逻辑合理编写的伪代码\n" +
                    "   - 用简洁自然的语言说明代码作用，不要用\"核心实现\"、\"服务代码\"等套话\n\n" +
                    "3. **特别提示 - 消息中心功能（3.6节）**：\n" +
                    "   - 仔细在代码中查找消息、通知、message、notification等相关功能\n" +
                    "   - 如果代码中有消息列表、消息发送、消息提醒等功能，重点描述这些\n" +
                    "   - 如果没有找到明确的消息中心，就描述代码中实际存在的相关通信/提示功能\n\n" +
                    "4. 前端和后端内容并重，体现界面设计和技术实现\n" +
                    "5. 使用丰富的中文表达，减少英文术语\n" +
                    "6. **不允许加总结性段落**\n\n" +
                    "【前后端代码分析重点】：\n" +
                    "**前端代码分析（重要）**：\n" +
                    "- 仔细查看HTML结构，识别5个功能区域的界面布局和组件使用\n" +
                    "- 查看功能按钮的id属性（如home-btn, user-btn等），了解功能切换机制\n" +
                    "- 查看每个功能区域的内容（如home-content, user-content等），了解展示的具体内容\n" +
                    "- 查看使用的Bootstrap组件（卡片、表格、表单、按钮、模态框等），体现界面美观性\n" +
                    "- 查看JavaScript代码中的交互逻辑，了解用户操作流程\n\n" +
                    "**后端代码分析**：\n" +
                    "- 查找与5个功能对应的@app.route路由定义和核心业务逻辑\n" +
                    "- 分析函数实现，理解数据处理流程和业务规则\n" +
                    "- 查找数据库模型定义和CRUD操作\n" +
                    "- 如果代码中有合适的片段就摘录（5-8行），如果没有就根据功能逻辑编写合理的示例代码或伪代码，自然地融入段落描述中，避免\"核心实现\"、\"服务代码\"等套话\n\n" +
                    "【重要】：整个功能描述要像一篇连贯的技术文章，不要分成\"功能描述\"、\"界面展示\"、\"用户操作\"、\"后端实现\"、\"代码示例\"等机械化的小节。所有内容（包括代码）自然地融入段落中，保持流畅性和可读性。代码可以是真实摘录，也可以是根据功能合理编写的示例代码。\n\n" +
                    "只需要讲说明书内容即可，不用加任何说明。前端使用Bootstrap界面，后端使用Python Flask + MySQL。\n\n" +
                    "以下是完整的前后端代码：\n%s",
                    code
                );
            case 4:
                return String.format(
                    "请基于前三章的内容，编写一篇简洁而有深度的'第四章 总结与展望'，约200-300字。这" +
                    "是计算机软件著作权的软件说明书的结尾部分。内容应为一篇连贯的短文，不使用分点或子标题，保持思路和语言的流畅性。首先对" +
                    "系统当前的主要特点和价值进行简要总结，点明系统的核心优势（包括前端使用Bootstrap界面、后端使用Python Flask + MySQL的技术优势），然后自然过渡到系统目前可能存在的不足或有待改进之处，" +
                    "最后展望系统的未来发展方向和潜在的功能扩展可能性。整篇文字应当语言精练、内容充实，避免空泛的表述。使用丰富的中文表达，避免使用英文术语或专业缩写。" +
                    "结尾应当给人以启发和期待，而非简单的总结陈词。只需要讲说明书内容即可，不用加任何说明\n\n" +
                    "以下是代码概要参考（仅供了解系统概况）：\n%s",
                    code != null && code.length() > 10000 ? code.substring(0, 10000) : code
                );
            default:
                return String.format(
                    "请基于所提供的代码，编写一篇连贯流畅的'第%d章'，约1000字。这是计算机软件著作权的软件说明书的重要组成部分。" +
                    "内容应采用连贯的叙述性段落，使用专业但平实的语言。只需要讲说明书内容即可，不用加任何说明。\n\n" +
                    "以下是代码参考：\n%s",
                    chapterNum, codeRef
                );
        }
    }

    /**
     * 获取章节标题
     */
    private String getChapterTitle(String chapterNum) {
        switch (chapterNum) {
            case "1": return "第一章 系统概述";
            case "2": return "第二章 程序建立过程";
            case "3": return "第三章 程序功能介绍";
            case "4": return "第四章 总结与展望";
            default: return "第" + chapterNum + "章";
        }
    }

    /**
     * 生成章节文件名
     */
    private String generateChapterFileName(String chapterNum) {
        switch (chapterNum) {
            case "1": return "第一章_系统概述.md";
            case "2": return "第二章_程序建立过程.md";
            case "3": return "第三章_程序功能介绍.md";
            case "4": return "第四章_总结与展望.md";
            default: return "第" + chapterNum + "章.md";
        }
    }

    /**
     * 验证完整HTML - 与AIService.java一致
     */
    private boolean validateCompleteHtml(String html) {
        if (html == null || html.trim().isEmpty()) {
            return false;
        }

        String lowerHtml = html.toLowerCase();
        return lowerHtml.contains("<!doctype html>") &&
               lowerHtml.contains("<html") &&
               lowerHtml.contains("<head>") &&
               lowerHtml.contains("<body>") &&
               lowerHtml.contains("</body>") &&
               lowerHtml.contains("</html>") &&
               lowerHtml.contains("bootstrap");
    }

    /**
     * 修复完整HTML - 与AIService.java一致
     */
    private String fixCompleteHtml(String html, String appName) {
        if (html == null || html.trim().isEmpty()) {
            return generateDefaultCompleteHtml(appName);
        }

        StringBuilder fixed = new StringBuilder();
        String lowerHtml = html.toLowerCase();

        
        if (!lowerHtml.contains("<!doctype")) {
            fixed.append("<!DOCTYPE html>\n");
        }

        
        if (!lowerHtml.contains("<html")) {
            fixed.append("<html lang=\"zh-CN\">\n");
        }

        
        if (!lowerHtml.contains("<head>")) {
            fixed.append("<head>\n");
            fixed.append("<meta charset=\"UTF-8\">\n");
            fixed.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
            fixed.append("<title>").append(appName).append(" - 管理系统</title>\n");
            fixed.append("<link href=\"https://cdn.jsdelivr.net/npm/bootstrap@5.3.0-alpha1/dist/css/bootstrap.min.css\" rel=\"stylesheet\">\n");
            fixed.append("<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css\">\n");
            fixed.append("</head>\n");
        }

        
        if (!lowerHtml.contains("<body")) {
            fixed.append("<body>\n");
        }

        fixed.append(html);

        
        if (!lowerHtml.contains("</body>")) {
            fixed.append("\n</body>");
        }
        if (!lowerHtml.contains("</html>")) {
            fixed.append("\n</html>");
        }

        return fixed.toString();
    }

    /**
     * 生成默认完整HTML - 与AIService.java一致
     */
    private String generateDefaultCompleteHtml(String appName) {
        return String.format("""
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s - 管理系统</title>
                <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0-alpha1/dist/css/bootstrap.min.css" rel="stylesheet">
                <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css">
                <style>
                    body { font-family: 'Microsoft YaHei', sans-serif; background-color: #f8f9fa; }
                    .card { border-radius: 10px; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1); margin-bottom: 20px; }
                    .navbar-brand { font-weight: bold; }
                </style>
            </head>
            <body>
                <nav class="navbar navbar-expand-lg navbar-dark bg-primary">
                    <div class="container">
                        <a class="navbar-brand" href="#"><i class="fas fa-university me-2"></i>%s</a>
                    </div>
                </nav>
                <div class="container my-5">
                    <h4 class='text-center mb-4'><i class='fas fa-industry'></i> %s</h4>
                    <div class="row">
                        <div class="col-md-6">
                            <div class="card">
                                <div class="card-header bg-primary text-white">
                                    <i class="fas fa-home me-2"></i>系统首页
                                </div>
                                <div class="card-body">
                                    <p>欢迎使用%s管理系统</p>
                                </div>
                            </div>
                        </div>
                        <div class="col-md-6">
                            <div class="card">
                                <div class="card-header bg-success text-white">
                                    <i class="fas fa-users me-2"></i>用户管理
                                </div>
                                <div class="card-body">
                                    <p>管理系统用户信息</p>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </body>
            </html>
            """, appName, appName, appName, appName);
    }

    /**
     * 发送进度信息
     */
    private void sendProgress(SseEmitter emitter, int progress, String message) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("progress", progress);
            data.put("message", message);

            emitter.send(SseEmitter.event()
                .name("data")
                .data(data));
        } catch (Exception e) {
            log.error("发送进度失败", e);
        }
    }

    /**
     * 发送错误信息
     */
    private void sendError(SseEmitter emitter, String error) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("error", error);

            emitter.send(SseEmitter.event()
                .name("error")
                .data(data));
            emitter.complete();
        } catch (Exception e) {
            log.error("发送错误信息失败", e);
        }
    }

    /**
     * 获取当前用户
     */
    /**
     * 获取当前登录用户的ID
     */
    private String getCurrentUserId() {
        try {
            Object principal = SecurityUtils.getSubject().getPrincipal();
            // log.info("获取到的principal对象: {}, 类型: {}", principal, principal != null ? principal.getClass().getName() : "null");

            if (principal instanceof LoginUser) {
                LoginUser loginUser = (LoginUser) principal;
                String userId = loginUser.getId();
                String username = loginUser.getUsername();
                // log.info("LoginUser对象 - ID: {}, Username: {}", userId, username);

                if (userId != null && !userId.trim().isEmpty()) {
                    return userId;
                }
                
                if (username != null && !username.trim().isEmpty()) {
                    // log.info("用户ID为空，使用username作为用户标识: {}", username);
                    return username;
                }
            }
            
            
            throw new JeecgBootException("用户未登录或登录已过期");
            
        } catch (Exception e) {
            log.error("获取当前用户ID失败: {}", e.getMessage());
            throw new JeecgBootException("用户身份验证失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取当前登录用户信息（用于日志显示）
     */
    private String getCurrentUserInfo() {
        try {
            Object principal = SecurityUtils.getSubject().getPrincipal();
            if (principal instanceof LoginUser) {
                LoginUser loginUser = (LoginUser) principal;
                return String.format("%s(%s)", loginUser.getRealname(), loginUser.getUsername());
            }
            return "系统用户";
        } catch (Exception e) {
            return "未知用户";
        }
    }
    
    /**
     * 添加数据库中生成的源代码文件到ZIP（按照用户要求：HTML文件、Python文件、源代码Word文档）
     */
    private void addGeneratedSourceCodeToZip(ZipOutputStream zos, Map<String, List<CopyrightFile>> filesByType, String appName, String projectId) throws IOException {
        try {
            log.info("开始添加数据库中生成的源代码文件到ZIP");
            
            // 1. 添加前端代码为可运行的HTML文件
            List<CopyrightFile> frontendFiles = filesByType.get("frontend_code");
            if (frontendFiles != null && !frontendFiles.isEmpty()) {
                CopyrightFile frontendFile = frontendFiles.get(0);
                String htmlContent = frontendFile.getContent();
                if (htmlContent != null && !htmlContent.trim().isEmpty()) {
                    
                    addFileToZip(zos, "源代码.html", htmlContent);
                    // log.info("已添加前端HTML文件: 前端代码.html, 内容长度: {} 字符", htmlContent.length());
                    log.info("已添加前端HTML文件");
                } else {
                    log.warn("前端代码内容为空");
                }
            } else {
                log.warn("未找到前端代码文件");
            }
            
            // 2. 添加后端代码为可运行的Python文件（合并三次生成的所有代码）
            StringBuilder completeBackendCode = new StringBuilder();
            
            
            List<CopyrightFile> part1Files = filesByType.get("backend_part1");
            if (part1Files != null && !part1Files.isEmpty()) {
                String part1Code = part1Files.get(0).getContent();
                if (part1Code != null && !part1Code.trim().isEmpty()) {
                    completeBackendCode.append("# ========== Part1: 基础架构层 ==========\n");
                    completeBackendCode.append(part1Code).append("\n\n");
                    log.info("已添加后端Part1代码");
                }
            }
            
            
            List<CopyrightFile> part2Files = filesByType.get("backend_part2");
            if (part2Files != null && !part2Files.isEmpty()) {
                String part2Code = part2Files.get(0).getContent();
                if (part2Code != null && !part2Code.trim().isEmpty()) {
                    completeBackendCode.append("# ========== Part2: 中间件和认证层 ==========\n");
                    completeBackendCode.append(part2Code).append("\n\n");
                    log.info("已添加后端Part2代码");
                }
            }
            
            
            List<CopyrightFile> part3Files = filesByType.get("backend_part3");
            if (part3Files != null && !part3Files.isEmpty()) {
                String part3Code = part3Files.get(0).getContent();
                if (part3Code != null && !part3Code.trim().isEmpty()) {
                    completeBackendCode.append("# ========== Part3: 业务和API层 ==========\n");
                    completeBackendCode.append(part3Code).append("\n\n");
                    log.info("已添加后端Part3代码");
                }
            }
            
            
            if (completeBackendCode.length() == 0) {
                List<CopyrightFile> backendFiles = filesByType.get("backend_code");
                if (backendFiles != null && !backendFiles.isEmpty()) {
                    CopyrightFile backendFile = backendFiles.get(0);
                    String pythonContent = backendFile.getContent();
                    if (pythonContent != null && !pythonContent.trim().isEmpty()) {
                        completeBackendCode.append(pythonContent);
                        log.info("已添加后端合并代码（降级方案）");
                    }
                }
            }
            
            
            if (completeBackendCode.length() > 0) {
                addFileToZip(zos, "源代码.py", completeBackendCode.toString());
                log.info("已添加完整后端Python文件（包含所有三次生成）");
            } else {
                log.warn("未找到任何后端代码文件");
            }
            
            // 3. 添加源代码Word文档 - 优先从数据库读取已裁剪的文档
            
            List<CopyrightFile> sourceCodePdfFiles = filesByType.get("source_code_pdf");
            if (sourceCodePdfFiles != null && !sourceCodePdfFiles.isEmpty()) {
                log.info("从数据库读取已生成的源代码PDF文档");
                CopyrightFile pdfFile = sourceCodePdfFiles.get(0);
                String base64Pdf = pdfFile.getContent();
                byte[] sourcePdf = java.util.Base64.getDecoder().decode(base64Pdf);
                addFileToZip(zos, appName + "-源代码文档.pdf", sourcePdf);
                log.info("源代码PDF文档已添加（从数据库读取）");
            } else if ((frontendFiles != null && !frontendFiles.isEmpty()) || 
                       (part1Files != null && !part1Files.isEmpty()) ||
                       (part2Files != null && !part2Files.isEmpty()) ||
                       (part3Files != null && !part3Files.isEmpty())) {
                
                log.warn("数据库中未找到已生成的PDF文档，执行实时生成（降级方案）");
                
                StringBuilder completeSourceCode = new StringBuilder();
                
                
                if (frontendFiles != null && !frontendFiles.isEmpty()) {
                    String frontendCode = frontendFiles.get(0).getContent();
                    String pureFrontendCode = extractPureHtmlCode(frontendCode);
                    completeSourceCode.append(pureFrontendCode).append("\n\n");
                }
                
                
                if (part1Files != null && !part1Files.isEmpty()) {
                    String part1Code = part1Files.get(0).getContent();
                    String purePart1Code = extractPurePythonCode(part1Code);
                    completeSourceCode.append(purePart1Code).append("\n\n");
                }
                if (part2Files != null && !part2Files.isEmpty()) {
                    String part2Code = part2Files.get(0).getContent();
                    String purePart2Code = extractPurePythonCode(part2Code);
                    completeSourceCode.append(purePart2Code).append("\n\n");
                }
                if (part3Files != null && !part3Files.isEmpty()) {
                    String part3Code = part3Files.get(0).getContent();
                    String purePart3Code = extractPurePythonCode(part3Code);
                    completeSourceCode.append(purePart3Code).append("\n");
                } else {
                    
                    List<CopyrightFile> backendFiles = filesByType.get("backend_code");
                    if (backendFiles != null && !backendFiles.isEmpty()) {
                        String backendCode = backendFiles.get(0).getContent();
                        String pureBackendCode = extractPurePythonCode(backendCode);
                        completeSourceCode.append(pureBackendCode).append("\n");
                    }
                }
                
                
                String tempDir = System.getProperty("java.io.tmpdir");
                String tempWordPath = tempDir + File.separator + "source_code_" + projectId + ".docx";
                
                byte[] sourceDoc = generateWordDocumentWithType(appName, 
                    completeSourceCode.toString(), "source");
                Files.write(Paths.get(tempWordPath), sourceDoc);
                
                
                try {
                    trimWordToMaxPages(tempWordPath, 60);
                    sourceDoc = Files.readAllBytes(Paths.get(tempWordPath));
                    
                    
                    String tempPdfPath = tempWordPath.replace(".docx", ".pdf");
                    byte[] sourcePdf = null;
                    try {
                        if (Files.exists(Paths.get(tempPdfPath))) {
                            sourcePdf = Files.readAllBytes(Paths.get(tempPdfPath));
                            log.info("源代码PDF文档已读取");
                        } else {
                            log.warn("未找到PDF文件: {}", tempPdfPath);
                        }
                    } catch (Exception pdfEx) {
                        log.warn("读取PDF文件失败: {}", pdfEx.getMessage());
                    }
                    
                    
                    if (sourcePdf != null) {
                        addFileToZip(zos, appName + "-源代码文档.pdf", sourcePdf);
                        log.info("源代码PDF文档已添加到ZIP");
                    } else {
                        log.warn("PDF文档生成失败，跳过添加");
                    }
                    
                } catch (Exception e) {
                    log.warn("源代码PDF生成失败: {}", e.getMessage());
                } finally {
                    try {
                        Files.deleteIfExists(Paths.get(tempWordPath));
                        
                        String tempPdfPath = tempWordPath.replace(".docx", ".pdf");
                        Files.deleteIfExists(Paths.get(tempPdfPath));
                    } catch (Exception ignored) {}
                }
            }
            
          
            
        } catch (Exception e) {
            log.error("添加数据库生成的源代码失败", e);
        }
    }

    /**
     * 从AI生成的内容中提取纯HTML代码
     */
    private String extractPureHtmlCode(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "";
        }
        
        
        
        return content;
    }

    /**
     * 从AI生成的内容中提取纯Python代码
     */
    private String extractPurePythonCode(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "";
        }
        
        StringBuilder pureCode = new StringBuilder();
        String[] lines = content.split("\n");
        boolean inCodeSection = false;
        
        for (String line : lines) {
            String trimmedLine = line.trim();
            
            
            if (trimmedLine.startsWith("```python") || 
                trimmedLine.startsWith("# app.py") || 
                trimmedLine.startsWith("from flask import") ||
                trimmedLine.startsWith("import ") ||
                trimmedLine.startsWith("class ") ||
                trimmedLine.startsWith("def ") ||
                trimmedLine.startsWith("app = Flask")) {
                inCodeSection = true;
            }
            
            
            if (trimmedLine.equals("```")) {
                inCodeSection = false;
                continue;
            }
            
            
            if (inCodeSection || 
                trimmedLine.startsWith("from ") ||
                trimmedLine.startsWith("import ") ||
                trimmedLine.startsWith("class ") ||
                trimmedLine.startsWith("def ") ||
                trimmedLine.startsWith("app") ||
                trimmedLine.startsWith("@") ||
                trimmedLine.contains("=") && !trimmedLine.startsWith("#") ||
                trimmedLine.startsWith("if ") ||
                trimmedLine.startsWith("return ") ||
                trimmedLine.startsWith("    ") || 
                trimmedLine.equals("") 
                ) {
                
                
                if (trimmedLine.contains("下面是") || 
                    trimmedLine.contains("这个实现") ||
                    trimmedLine.contains("## ") ||
                    trimmedLine.startsWith("**") ||
                    (trimmedLine.startsWith("#") && trimmedLine.contains("文件结构"))) {
                    continue;
                }
                
                pureCode.append(line).append("\n");
            }
        }
        
        String result = pureCode.toString();
        
        
        if (result.trim().length() < 1000) {
            log.warn("提取的Python代码太短，返回原始内容");
            return content;
        }
        
        return result;
    }


    
    /**
     * 计算数据库中生成的源代码行数
     */
    private int calculateGeneratedCodeLines(Map<String, List<CopyrightFile>> filesByType) {
        try {
            int totalLines = 0;
            
            
            List<CopyrightFile> frontendFiles = filesByType.get("frontend_code");
            if (frontendFiles != null && !frontendFiles.isEmpty()) {
                String frontendContent = frontendFiles.get(0).getContent();
                if (frontendContent != null) {
                    int frontendLines = countEffectiveLines(frontendContent);
                    totalLines += frontendLines;
                    log.debug("前端代码有效行数: {}", frontendLines);
                }
            }
            
            
            List<CopyrightFile> backendFiles = filesByType.get("backend_code");
            if (backendFiles != null && !backendFiles.isEmpty()) {
                String backendContent = backendFiles.get(0).getContent();
                if (backendContent != null) {
                    int backendLines = countEffectiveLines(backendContent);
                    totalLines += backendLines;
                    log.debug("后端代码有效行数: {}", backendLines);
                }
            }
            
            log.info("数据库生成的源代码总行数: {}", totalLines);
            return totalLines; 
            
        } catch (Exception e) {
            log.error("统计生成代码行数失败", e);
            return 8000; 
        }
    }
    
    /**
     * 统计有效代码行数（排除空行和注释行）
     */
    private int countEffectiveLines(String content) {
        if (content == null || content.trim().isEmpty()) {
            return 0;
        }
        
        String[] lines = content.split("\n");
        int count = 0;
        
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (!trimmedLine.isEmpty() && 
                !trimmedLine.startsWith("//") && 
                !trimmedLine.startsWith("/*") && 
                !trimmedLine.startsWith("*") && 
                !trimmedLine.startsWith("#") && 
                !trimmedLine.startsWith("<!--") && 
                !trimmedLine.equals("*/") &&
                !trimmedLine.equals("-->")) {
                count++;
            }
        }
        
        return count;
    }

    /**
     * 计算实际源代码行数（使用过滤后的源代码内容）
     */
    private int calculateActualSourceCodeLines(Map<String, List<CopyrightFile>> filesByType) {
        try {
            int totalLines = 0;
            
            
            List<CopyrightFile> frontendFiles = filesByType.get("frontend_code");
            if (frontendFiles != null && !frontendFiles.isEmpty()) {
                String frontendContent = frontendFiles.get(0).getContent();
                if (frontendContent != null) {
                    String pureFrontendCode = extractPureHtmlCode(frontendContent);
                    int frontendLines = countEffectiveLines(pureFrontendCode);
                    totalLines += frontendLines;
                    log.debug("前端代码有效行数: {}", frontendLines);
                }
            }
            
            
            List<CopyrightFile> backendFiles = filesByType.get("backend_code");
            if (backendFiles != null && !backendFiles.isEmpty()) {
                String backendContent = backendFiles.get(0).getContent();
                if (backendContent != null) {
                    String pureBackendCode = extractPurePythonCode(backendContent);
                    int backendLines = countEffectiveLines(pureBackendCode);
                    totalLines += backendLines;
                    log.debug("后端代码有效行数: {}", backendLines);
                }
            }
            
            log.info("过滤后的源代码总行数: {}", totalLines);
            return totalLines; 
            
        } catch (Exception e) {
            log.error("统计过滤后源代码行数失败", e);
            return 5000; 
        }
    }

    /**
     * 从文件类型分组中提取第一章内容
     */
    private String extractChapter1Content(Map<String, List<CopyrightFile>> filesByType) {
        List<CopyrightFile> chapter1Files = filesByType.get("chapter1");
        if (chapter1Files != null && !chapter1Files.isEmpty()) {
            return chapter1Files.get(0).getContent();
        }
        return "";
    }

    /**
     * 从第一章内容中提取软著申请信息（调用AI分析）
     */
    private Map<String, String> extractSoftwareInfoFromChapter1(String chapter1Content, String appName) {
        Map<String, String> info = new HashMap<>();
        
        if (chapter1Content == null || chapter1Content.trim().isEmpty()) {
            
            info.put("purpose", "提供高效的业务管理和服务功能");
            info.put("domain", "教育管理、数字化服务");
            info.put("functions", "提供完整的管理功能，包括用户管理、数据管理、系统配置等核心模块，支持多用户协作，具有良好的扩展性和稳定性，能够满足不同规模企业的业务需求。");
            info.put("features", "采用前后端分离架构，使用Vue3+TypeScript前端框架，Spring Boot后端框架");
            return info;
        }
        
        
        chapter1Content = cleanupContentForAI(chapter1Content);
        
        try {
            
            String prompt = String.format("""
                请分析以下软件说明书第一章内容，提取软著申请所需的关键信息。
                
                软件名称：%s
                
                第一章内容：
                %s
                
                请严格按照以下JSON格式返回，只返回JSON，不要有任何其他解释文字：
                {
                  "purpose": "开发目的内容",
                  "domain": "面向领域内容", 
                  "functions": "主要功能描述内容",
                  "features": "技术特点内容"
                }
                
                详细要求：
                1. purpose: 简洁描述软件的开发目的和用途，20-40字
                2. domain: 明确软件面向的行业，10-30字
                3. functions: **这是最重要的字段！必须详细描述软件的主要功能模块，必须达到120-180字！**
                   - 必须分点列举至少5-8个具体功能模块
                   - 每个功能模块要详细说明其作用和特点
                   - 例如：数据采集模块、数据分析模块、可视化展示模块、报表生成模块、用户管理模块、系统配置模块等
                   - 不要只写概括性的描述，要具体到每个模块的功能
                   - 字数必须在120-180字之间，不足则继续补充功能模块
                4. features: 突出软件采用的关键技术和架构特点，40-80字，不要写的太夸张，可以宽泛一点，不要包含源代码行数等无关信息
                
                注意：
                - **functions字段是重中之重！必须达到120-180字！如果不足120字，AI将被视为失败！**
                - functions必须详细列举多个功能模块，每个模块都要说明具体作用
                - 不要只写一句话概括，要分点详细描述
                - features字段只描述技术特点，不要包含"开发语言"、"源代码行数"、"过滤后的源代码总行数"等调试信息
                - 项目一定是Python相关项目，技术特点应该突出Python技术栈
                - 忽略任何包含"行数"、"总行数"的调试信息
                - 请确保返回的是标准JSON格式
                
                functions字段示例格式（必须类似这样的详细程度）：
                "系统提供数据采集模块，支持多种传感器接入和实时数据采集；数据预处理模块，实现数据清洗、去噪和格式转换；智能分析模块，运用机器学习算法进行数据分析和预测；可视化展示模块，提供多种图表和仪表盘展示数据趋势；报表生成模块，自动生成各类统计报表和分析报告；用户权限管理模块，支持多角色权限分配和访问控制；系统配置模块，提供灵活的参数配置和业务流程定制；数据导入导出模块，支持多种格式的数据交换。"
                """, appName, chapter1Content);
            
            
            String response = callDeepSeekChatAPI(prompt, 8000);
            
            
            if (response != null && !response.trim().isEmpty()) {
                try {
                    
                    info = parseSimpleJson(response);
                    log.info("AI提取软著信息成功: purpose={}, domain={}, functions={}字, features={}字", 
                        info.get("purpose"), info.get("domain"), 
                        info.get("functions") != null ? info.get("functions").length() : 0,
                        info.get("features") != null ? info.get("features").length() : 0);
                    
                    // ===== 字数校验和重新生成逻辑 =====
                    info = validateAndRegenerateFields(info, appName, chapter1Content);
                    
                } catch (Exception e) {
                    log.error("解析AI返回的JSON失败: {}", e.getMessage());
                    throw e;
                }
            } else {
                log.warn("AI返回内容为空");
                throw new Exception("AI返回内容为空");
            }
            
        } catch (Exception e) {
            log.error("AI提取软著信息失败，使用默认值: {}", e.getMessage());
            
            info.put("purpose", "通过人工智能技术提升业务管理效率和服务质量");
            info.put("domain", "企业管理、智能化服务");
            info.put("functions", "系统提供完整的业务管理功能，包括用户权限管理模块，支持多角色用户注册登录和权限分配；数据管理模块，实现数据的增删改查、导入导出和统计分析；智能分析模块，运用机器学习算法对业务数据进行深度分析和预测；报表生成模块，自动生成各类业务报表和可视化图表；系统配置模块，支持灵活的参数配置和业务流程定制；消息通知模块，实现实时消息推送和邮件提醒功能。");
            info.put("features", "采用Python作为主要开发语言，使用Django或Flask Web框架构建后端服务，集成机器学习库如scikit-learn进行数据分析，使用MySQL或PostgreSQL数据库存储数据，前端采用现代化框架实现响应式界面");
        }
        
        return info;
    }
    
    /**
     * 校验字段字数并重新生成不符合要求的字段
     */
    private Map<String, String> validateAndRegenerateFields(Map<String, String> info, String appName, String chapter1Content) {
        log.info("开始校验字段字数...");
        
        
        boolean needRegenerate = false;
        StringBuilder fieldsToRegenerate = new StringBuilder();
        
        
        int purposeLen = info.get("purpose") != null ? info.get("purpose").length() : 0;
        if (purposeLen < 20 || purposeLen > 40) {
            log.warn("purpose字数不符合要求: {}字（要求20-40字）", purposeLen);
            fieldsToRegenerate.append("purpose, ");
            needRegenerate = true;
        }
        
        
        int domainLen = info.get("domain") != null ? info.get("domain").length() : 0;
        if (domainLen < 10 || domainLen > 30) {
            log.warn("domain字数不符合要求: {}字（要求10-30字）", domainLen);
            fieldsToRegenerate.append("domain, ");
            needRegenerate = true;
        }
        
        
        int functionsLen = info.get("functions") != null ? info.get("functions").length() : 0;
        if (functionsLen < 120 || functionsLen > 180) {
            log.warn("functions字数不符合要求: {}字（要求120-180字）", functionsLen);
            fieldsToRegenerate.append("functions, ");
            needRegenerate = true;
        }
        
        
        int featuresLen = info.get("features") != null ? info.get("features").length() : 0;
        if (featuresLen < 40 || featuresLen > 80) {
            log.warn("features字数不符合要求: {}字（要求40-80字）", featuresLen);
            fieldsToRegenerate.append("features, ");
            needRegenerate = true;
        }
        
        
        if (needRegenerate) {
            log.info("需要重新生成以下字段: {}", fieldsToRegenerate.toString());
            
            for (int retry = 1; retry <= 3; retry++) {
                log.info("第{}次尝试重新生成不符合要求的字段...", retry);
                
                try {
                    
                    Map<String, String> regeneratedInfo = regenerateInvalidFields(info, appName, chapter1Content);
                    
                    
                    for (String key : regeneratedInfo.keySet()) {
                        info.put(key, regeneratedInfo.get(key));
                    }
                    
                    
                    purposeLen = info.get("purpose") != null ? info.get("purpose").length() : 0;
                    domainLen = info.get("domain") != null ? info.get("domain").length() : 0;
                    functionsLen = info.get("functions") != null ? info.get("functions").length() : 0;
                    featuresLen = info.get("features") != null ? info.get("features").length() : 0;
                    
                    boolean allValid = (purposeLen >= 20 && purposeLen <= 40) &&
                                       (domainLen >= 10 && domainLen <= 30) &&
                                       (functionsLen >= 120 && functionsLen <= 180) &&
                                       (featuresLen >= 40 && featuresLen <= 80);
                    
                    if (allValid) {
                        log.info("重新生成成功！所有字段字数符合要求");
                        break;
                    } else {
                        log.warn("第{}次重新生成后仍有字段不符合要求，继续尝试...", retry);
                    }
                    
                } catch (Exception e) {
                    log.error("第{}次重新生成失败: {}", retry, e.getMessage());
                }
            }
        } else {
            log.info("所有字段字数符合要求，无需重新生成");
        }
        
        
        log.info("最终软著信息: purpose={}字, domain={}字, functions={}字, features={}字",
            info.get("purpose") != null ? info.get("purpose").length() : 0,
            info.get("domain") != null ? info.get("domain").length() : 0,
            info.get("functions") != null ? info.get("functions").length() : 0,
            info.get("features") != null ? info.get("features").length() : 0);
        
        return info;
    }
    
    /**
     * 重新生成不符合字数要求的字段
     */
    private Map<String, String> regenerateInvalidFields(Map<String, String> currentInfo, String appName, String chapter1Content) {
        Map<String, String> result = new HashMap<>();
        
        
        int purposeLen = currentInfo.get("purpose") != null ? currentInfo.get("purpose").length() : 0;
        int domainLen = currentInfo.get("domain") != null ? currentInfo.get("domain").length() : 0;
        int functionsLen = currentInfo.get("functions") != null ? currentInfo.get("functions").length() : 0;
        int featuresLen = currentInfo.get("features") != null ? currentInfo.get("features").length() : 0;
        
        
        StringBuilder fieldsPrompt = new StringBuilder();
        
        if (purposeLen < 20 || purposeLen > 40) {
            fieldsPrompt.append("- purpose字段（当前").append(purposeLen).append("字，要求20-40字）\n");
        }
        if (domainLen < 10 || domainLen > 30) {
            fieldsPrompt.append("- domain字段（当前").append(domainLen).append("字，要求10-30字）\n");
        }
        if (functionsLen < 120 || functionsLen > 180) {
            fieldsPrompt.append("- functions字段（当前").append(functionsLen).append("字，要求120-180字）\n");
        }
        if (featuresLen < 40 || featuresLen > 80) {
            fieldsPrompt.append("- features字段（当前").append(featuresLen).append("字，要求40-80字）\n");
        }
        
        String prompt = String.format(
            "以下字段的字数不符合要求，请重新生成：\n%s\n" +
            "软件名称：%s\n\n" +
            "请严格按照字数要求重新生成，只返回JSON格式：\n" +
            "{\n" +
            "%s%s%s%s" +
            "}\n\n" +
            "要求：\n" +
            "- purpose: 20-40字\n" +
            "- domain: 10-30字\n" +
            "- functions: 120-180字，必须详细列举5-8个功能模块\n" +
            "- features: 40-80字\n" +
            "- 只返回需要重新生成的字段",
            fieldsPrompt.toString(),
            appName,
            (purposeLen < 20 || purposeLen > 40) ? "  \"purpose\": \"...\",\n" : "",
            (domainLen < 10 || domainLen > 30) ? "  \"domain\": \"...\",\n" : "",
            (functionsLen < 120 || functionsLen > 180) ? "  \"functions\": \"...\",\n" : "",
            (featuresLen < 40 || featuresLen > 80) ? "  \"features\": \"...\"\n" : ""
        );
        
        try {
            String response = callDeepSeekChatAPI(prompt, 4000);
            Map<String, String> regenerated = parseSimpleJson(response);
            
            
            for (String key : regenerated.keySet()) {
                int len = regenerated.get(key).length();
                if ("purpose".equals(key) && (purposeLen < 20 || purposeLen > 40)) {
                    result.put(key, regenerated.get(key));
                    log.info("重新生成purpose: {}字", len);
                } else if ("domain".equals(key) && (domainLen < 10 || domainLen > 30)) {
                    result.put(key, regenerated.get(key));
                    log.info("重新生成domain: {}字", len);
                } else if ("functions".equals(key) && (functionsLen < 120 || functionsLen > 180)) {
                    result.put(key, regenerated.get(key));
                    log.info("重新生成functions: {}字", len);
                } else if ("features".equals(key) && (featuresLen < 40 || featuresLen > 80)) {
                    result.put(key, regenerated.get(key));
                    log.info("重新生成features: {}字", len);
                }
            }
        } catch (Exception e) {
            log.error("重新生成字段失败: {}", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 简单的JSON解析（解析AI返回的软著信息）
     */
    private Map<String, String> parseSimpleJson(String jsonStr) {
        Map<String, String> result = new HashMap<>();
        
        try {
            
            jsonStr = jsonStr.trim();
            
            
            if (jsonStr.startsWith("```json")) {
                jsonStr = jsonStr.substring(7);
            }
            if (jsonStr.endsWith("```")) {
                jsonStr = jsonStr.substring(0, jsonStr.length() - 3);
            }
            
            jsonStr = jsonStr.trim();
            
            
            if (!jsonStr.startsWith("{")) {
                throw new RuntimeException("JSON格式错误：缺少开始花括号");
            }
            if (!jsonStr.endsWith("}")) {
                throw new RuntimeException("JSON格式错误：缺少结束花括号");
            }
            
            
            jsonStr = jsonStr.substring(1, jsonStr.length() - 1).trim();
            
            
            String[] pairs = jsonStr.split(",(?=\\s*\"[^\"]+\"\\s*:)");
            
            for (String pair : pairs) {
                
                String[] keyValue = pair.split(":", 2);
                if (keyValue.length == 2) {
                    String key = keyValue[0].trim().replaceAll("\"", "");
                    String value = keyValue[1].trim();
                    
                    
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    
                    
                    if ("purpose".equals(key) || "domain".equals(key) || "functions".equals(key) || "features".equals(key)) {
                        result.put(key, value);
                        log.info("解析JSON字段: {} = {}字", key, value.length());
                    }
                }
            }
            
            
            if (!result.containsKey("functions") || result.get("functions").length() < 100) {
                log.warn("functions字段内容不足，当前长度: {}", result.containsKey("functions") ? result.get("functions").length() : 0);
            }
            
        } catch (Exception e) {
            log.error("JSON解析失败: {}", e.getMessage());
            log.error("原始JSON内容: {}", jsonStr);
            throw new RuntimeException("JSON解析失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 清理内容中的调试信息，为AI提取准备
     */
    private String cleanupContentForAI(String content) {
        if (content == null) return "";
        
        
        String[] lines = content.split("\n");
        StringBuilder cleanContent = new StringBuilder();
        
        for (String line : lines) {
            
            if (line.contains("过滤后的源代码总行数") || 
                line.contains("源代码行数") ||
                line.matches(".*行数.*：.*\\d+.*")) {
                continue;
            }
            cleanContent.append(line).append("\n");
        }
        
        return cleanContent.toString().trim();
    }

    /**
     * 为Python脚本准备截图文件（保存到临时目录）
     */
    private String prepareScreenshotsForPython(Map<String, List<CopyrightFile>> filesByType, String appName) {
        String tempDir = System.getProperty("java.io.tmpdir");
        
        String cleanAppName = appName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_");
        String timestamp = String.valueOf(System.currentTimeMillis());
        String screenshotDir = tempDir + File.separator + "screenshots_" + cleanAppName + "_" + timestamp;
        
        try {
            
            Files.createDirectories(Paths.get(screenshotDir));
            
            List<CopyrightFile> screenshotFiles = filesByType.get("screenshot");
            if (screenshotFiles != null && !screenshotFiles.isEmpty()) {
                // log.info("准备{}个截图文件到临时目录: {}", screenshotFiles.size(), screenshotDir);
        log.info("准备{}个截图文件", screenshotFiles.size());
                
                for (int i = 0; i < screenshotFiles.size(); i++) {
                    CopyrightFile screenshotFile = screenshotFiles.get(i);
                    String content = screenshotFile.getContent();
                    
                    if (content != null && content.startsWith("data:image/png;base64,")) {
                        
                        String base64Data = content.substring("data:image/png;base64,".length());
                        byte[] imageData = java.util.Base64.getDecoder().decode(base64Data);
                        
                        
                        String fileName = String.format("%s_screenshot_%d.png", cleanAppName, i + 1);
                        String filePath = screenshotDir + "/" + fileName;
                        
                        
                        Files.write(Paths.get(filePath), imageData);
                        log.info("截图文件已保存: {}", fileName);
                    }
                }
            } else {
                log.info("未找到截图文件，跳过截图准备");
            }
            
            return screenshotDir;
        } catch (Exception e) {
            log.error("准备截图文件失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 清理临时截图目录
     */
    private void cleanupTempScreenshots(String screenshotDir) {
        if (screenshotDir == null) {
            return;
        }
        
        try {
            Path dirPath = Paths.get(screenshotDir);
            if (Files.exists(dirPath)) {
                
                Files.walk(dirPath)
                    .sorted((p1, p2) -> -p1.compareTo(p2)) 
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (Exception e) {
                            log.warn("删除临时文件失败: {}", path, e);
                        }
                    });
                log.info("临时截图目录已清理: {}", screenshotDir);
            }
        } catch (Exception e) {
            log.error("清理临时截图目录失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 从数据库获取软著信息（包括源代码行数）
     */
    private Map<String, Object> getSoftwareInfoFromDatabase(Map<String, List<CopyrightFile>> filesByType) {
        Map<String, Object> info = new HashMap<>();
        try {
            String currentUserId = getCurrentUserId();
            
            Integer sourceCodeLines = null;
            List<CopyrightFile> softwareInfoFiles = filesByType.get("software_info");
            if (softwareInfoFiles != null && !softwareInfoFiles.isEmpty()) {
                CopyrightFile softwareInfoFile = softwareInfoFiles.get(0);
                sourceCodeLines = softwareInfoFile.getSourceCodeLines();
            }
            
            
            List<CopyrightFile> chapter1Files = filesByType.get("chapter1");
            if (chapter1Files != null && !chapter1Files.isEmpty()) {
                CopyrightFile chapter1File = chapter1Files.get(0);
                
                if (sourceCodeLines == null) {
                    sourceCodeLines = chapter1File.getSourceCodeLines();
                }
                
                
                info.put("purpose", chapter1File.getSoftwarePurpose() != null ? chapter1File.getSoftwarePurpose() : "");
                info.put("domain", chapter1File.getSoftwareDomain() != null ? chapter1File.getSoftwareDomain() : "");
                info.put("functions", chapter1File.getSoftwareFunctions() != null ? chapter1File.getSoftwareFunctions() : "");
                info.put("features", chapter1File.getSoftwareFeatures() != null ? chapter1File.getSoftwareFeatures() : "");
                
                log.info("从数据库获取软著信息: purpose={}, domain={}, functions={}字, features={}字, sourceCodeLines={}", 
                    info.get("purpose"), info.get("domain"), 
                    info.get("functions").toString().length(), info.get("features").toString().length(),
                    sourceCodeLines);
            } else {
                log.warn("未找到第一章文件，无法获取软著信息");
            }
            
            
            info.put("sourceCodeLines", sourceCodeLines);
            
        } catch (Exception e) {
            log.error("从数据库获取软著信息失败: {}", e.getMessage(), e);
        }
        
        
        info.putIfAbsent("purpose", "");
        info.putIfAbsent("domain", "");
        info.putIfAbsent("functions", "");
        info.putIfAbsent("features", "");
        info.putIfAbsent("sourceCodeLines", null);
        
        return info;
    }

    /**
     * 保存软著信息到数据库
     */
    private void saveSoftwareInfoToDatabase(Map<String, List<CopyrightFile>> filesByType, Map<String, String> softwareInfo) {
        try {
            List<CopyrightFile> chapter1Files = filesByType.get("chapter1");
            if (chapter1Files != null && !chapter1Files.isEmpty()) {
                CopyrightFile chapter1File = chapter1Files.get(0);
                
                
                chapter1File.setSoftwarePurpose(softwareInfo.get("purpose"));
                chapter1File.setSoftwareDomain(softwareInfo.get("domain"));
                chapter1File.setSoftwareFunctions(softwareInfo.get("functions"));
                chapter1File.setSoftwareFeatures(softwareInfo.get("features"));
                
                
                copyrightFileService.updateById(chapter1File);
                log.info("软著信息已保存到数据库，文件ID: {}", chapter1File.getId());
            } else {
                log.warn("未找到第一章文件，无法保存软著信息");
            }
        } catch (Exception e) {
            log.error("保存软著信息到数据库失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 保存源代码行数到数据库
     */
    private void saveSourceCodeLinesToDatabase(Map<String, List<CopyrightFile>> filesByType, int sourceCodeLines) {
        try {
            String currentUserId = getCurrentUserId();
            
            CopyrightFile targetFile = null;
            
            // 1. 首先尝试找software_info类型的文件
            List<CopyrightFile> softwareInfoFiles = filesByType.get("software_info");
            if (softwareInfoFiles != null && !softwareInfoFiles.isEmpty()) {
                targetFile = softwareInfoFiles.get(0);
            } else {
                // 2. 如果没有software_info文件，使用chapter1文件
                List<CopyrightFile> chapter1Files = filesByType.get("chapter1");
                if (chapter1Files != null && !chapter1Files.isEmpty()) {
                    targetFile = chapter1Files.get(0);
                }
            }
            
            if (targetFile != null) {
                targetFile.setSourceCodeLines(sourceCodeLines);
                copyrightFileService.updateById(targetFile);
                log.info("源代码行数已保存到数据库: {} 行，文件ID: {}", sourceCodeLines, targetFile.getId());
            } else {
                log.warn("未找到合适的文件记录来保存源代码行数");
            }
        } catch (Exception e) {
            log.error("保存源代码行数到数据库失败: {}", e.getMessage(), e);
        }
    }

    // ==================== 截图相关API ====================

    /**
     * 获取项目的所有截图
     */
    @GetMapping("/copyright/screenshots/{projectId}")
    public Result<List<Map<String, Object>>> getProjectScreenshots(@PathVariable String projectId) {
        try {
            log.info("获取项目截图: {}", projectId);
            
            
            List<CopyrightFile> screenshots = copyrightFileService.lambdaQuery()
                    .eq(CopyrightFile::getProjectId, projectId)
                    .eq(CopyrightFile::getFileType, "screenshot")
                    .orderByAsc(CopyrightFile::getCreateTime)
                    .list();
            
            List<Map<String, Object>> result = screenshots.stream()
                    .map(screenshot -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("id", screenshot.getId());
                        item.put("fileName", screenshot.getFileName());
                        item.put("title", screenshot.getTitle());
                        item.put("createTime", screenshot.getCreateTime());
                        item.put("downloadUrl", "/jeecg-boot/agenthub/api/copyright/screenshot/download/" + screenshot.getId());
                        
                        
                        if (screenshot.getContent() != null && screenshot.getContent().startsWith("data:image")) {
                            item.put("previewUrl", "/jeecg-boot/agenthub/api/copyright/screenshot/preview/" + screenshot.getId());
                        }
                        
                        return item;
                    })
                    .collect(Collectors.toList());
            
            return Result.OK(result);
            
        } catch (Exception e) {
            log.error("获取项目截图失败: {}", e.getMessage(), e);
            return Result.error("获取项目截图失败: " + e.getMessage());
        }
    }

    /**
     * 下载截图文件
     */
    @GetMapping("/copyright/screenshot/download/{screenshotId}")
    public ResponseEntity<Resource> downloadScreenshot(@PathVariable String screenshotId) {
        try {
            log.info("下载截图: {}", screenshotId);
            
            CopyrightFile screenshot = copyrightFileService.getById(screenshotId);
            if (screenshot == null || !"screenshot".equals(screenshot.getFileType())) {
                return ResponseEntity.notFound().build();
            }
            
            String content = screenshot.getContent();
            byte[] imageBytes;
            String fileName = screenshot.getFileName();
            
            if (content.startsWith("data:image/png;base64,")) {
                // Base64编码的PNG图片
                String base64Data = content.substring("data:image/png;base64,".length());
                imageBytes = Base64.getDecoder().decode(base64Data);
                if (!fileName.endsWith(".png")) {
                    fileName += ".png";
                }
            } else {
                
                imageBytes = content.getBytes("UTF-8");
                if (!fileName.endsWith(".txt")) {
                    fileName = fileName.replace(".png", ".txt");
                }
            }
            
            ByteArrayResource resource = new ByteArrayResource(imageBytes);
            
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(content.startsWith("data:image") ? MediaType.IMAGE_PNG : MediaType.TEXT_PLAIN)
                    .contentLength(imageBytes.length)
                    .body(resource);
                    
        } catch (Exception e) {
            log.error("下载截图失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 预览截图（返回base64图片数据）
     */
    @GetMapping("/copyright/screenshot/preview/{screenshotId}")
    public ResponseEntity<String> previewScreenshot(@PathVariable String screenshotId) {
        try {
            log.info("预览截图: {}", screenshotId);
            
            CopyrightFile screenshot = copyrightFileService.getById(screenshotId);
            if (screenshot == null || !"screenshot".equals(screenshot.getFileType())) {
                return ResponseEntity.notFound().build();
            }
            
            String content = screenshot.getContent();
            if (content.startsWith("data:image")) {
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(content);
            } else {
                
                return ResponseEntity.badRequest().body("该截图不支持预览");
            }
                    
        } catch (Exception e) {
            log.error("预览截图失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 生成并保存软著申请表（在生成阶段调用）
     */
    private void generateAndSaveCopyrightApplicationForm(String projectId, String appName, String currentUserId) throws Exception {
        log.info("开始为项目 {} 生成软著申请表", projectId);
        
        // 0. 获取项目信息（用于获取创建时间）
        CopyrightProject project = copyrightProjectService.getById(projectId);
        if (project == null) {
            throw new Exception("未找到项目信息");
        }
        
        // 1. 获取第一章内容
        CopyrightFile chapter1File = copyrightFileService.lambdaQuery()
            .eq(CopyrightFile::getProjectId, projectId)
            .eq(CopyrightFile::getFileType, "chapter1")
            .one();
        
        if (chapter1File == null || chapter1File.getContent() == null) {
            throw new Exception("未找到第一章内容，无法生成软著申请表");
        }
        
        String chapter1Content = chapter1File.getContent();
        log.info("已获取第一章内容，长度: {} 字符", chapter1Content.length());
        
        // 2. 使用AI提取软著信息（包含字数校验和重新生成逻辑）
        Map<String, String> softwareInfo = extractSoftwareInfoFromChapter1(appName, chapter1Content);
        
        // 3. 构建软著申请表内容
        StringBuilder infoContent = new StringBuilder();
        infoContent.append("软件全称：").append(appName).append("\n");
        infoContent.append("软件简称：").append(appName).append("\n");
        infoContent.append("版本号：V1.0\n");
        infoContent.append("开发目的：").append(softwareInfo.get("purpose")).append("\n");
        infoContent.append("面向领域：").append(softwareInfo.get("domain")).append("\n");
        infoContent.append("\n主要功能：\n").append(softwareInfo.get("functions")).append("\n");
        infoContent.append("\n技术特点：\n").append(softwareInfo.get("features")).append("\n");
        
        
        CopyrightFile sourceCodeFile = copyrightFileService.lambdaQuery()
            .eq(CopyrightFile::getProjectId, projectId)
            .eq(CopyrightFile::getFileType, "source_code_word")
            .one();
        
        int totalLines = 0;
        if (sourceCodeFile != null && sourceCodeFile.getSourceCodeLines() != null) {
            totalLines = sourceCodeFile.getSourceCodeLines();
        } else {
            
            CopyrightFile frontendFile = copyrightFileService.lambdaQuery()
                .eq(CopyrightFile::getProjectId, projectId)
                .eq(CopyrightFile::getFileType, "frontend_code")
                .one();
            CopyrightFile backendFile = copyrightFileService.lambdaQuery()
                .eq(CopyrightFile::getProjectId, projectId)
                .eq(CopyrightFile::getFileType, "backend_code")
                .one();
            
            if (frontendFile != null && frontendFile.getContent() != null) {
                totalLines += frontendFile.getContent().split("\n").length;
            }
            if (backendFile != null && backendFile.getContent() != null) {
                totalLines += backendFile.getContent().split("\n").length;
            }
        }
        
        infoContent.append("\n源程序量：").append(totalLines).append("行\n");
        infoContent.append("开发语言：JavaScript、Python、HTML\n");
        
        log.info("软著申请表内容已构建，总长度: {} 字符", infoContent.length());
        
        // 4. 生成Word文档
        byte[] docBytes = generateWordDocumentWithType(appName + "-软著申请表", 
            infoContent.toString(), "info");
        
        // 5. 保存到数据库
        String base64Doc = java.util.Base64.getEncoder().encodeToString(docBytes);
        copyrightFileService.saveGeneratedFile(
            projectId, 
            "copyright_application", 
            appName + "-软著申请表.docx", 
            "软著申请表", 
            base64Doc, 
            currentUserId
        );
        
        log.info("项目 {} 软著申请表已生成并保存到数据库", projectId);
    }

} 