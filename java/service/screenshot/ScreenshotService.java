package org.jeecg.modules.agenthub.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.jeecg.modules.agenthub.copyright.service.ICopyrightFileService;
import org.jeecg.modules.agenthub.copyright.entity.CopyrightFile;
import org.jeecg.modules.agenthub.util.CrossPlatformUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

// Selenium imports
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.By;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.JavascriptExecutor;
import java.time.Duration;
import java.util.regex.Matcher;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.OutputStream;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import java.io.InputStream;
import java.io.FileInputStream;
import java.util.concurrent.TimeUnit;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.JavascriptExecutor;
import java.time.Duration;
import java.util.Base64;

/**
 * 截图服务
 */
@Slf4j
@Service
public class ScreenshotService {

    private static final String SCREENSHOT_DIR = "screenshots";
    private final ScreenshotRenderChecker renderChecker = new ScreenshotRenderChecker();
    private static final String TEMP_DIR = "temp";
    private static final int DEFAULT_WIDTH = 1200;
    private static final int DEFAULT_HEIGHT = 800;
    private static final int SERVER_TIMEOUT = 30;

    @Value("${screenshot.service.url:http://localhost:3000}")
    private String screenshotServiceUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    private ICopyrightFileService copyrightFileService;

    public void generateScreenshots(String appName, String frontendCode, SseEmitter emitter, String fileId) {
        CompletableFuture.runAsync(() -> {
            WebDriver driver = null;
            String tempFilePath = null;

            try {
                log.info("开始生成真实代码截图: appName={}", appName);

                sendProgress(emitter, 10, "正在准备前端代码...");

                String processedCode = preprocessCodeSimple(frontendCode, appName);

                sendProgress(emitter, 20, "正在创建临时HTML文件...");

                tempFilePath = createTempHtmlFile(appName, processedCode);

                sendProgress(emitter, 40, "正在启动Chrome截图...");

                log.info("跳过Chrome可用性检查，直接尝试初始化ChromeDriver...");

                sendProgress(emitter, 40, "正在初始化无头浏览器...");

                try {
                    driver = initializeWebDriver();
                } catch (Exception e) {
                    log.error("Chrome初始化失败，使用降级截图: {}", e.getMessage());

                    List<Map<String, String>> fallbackScreenshots = generateFallbackScreenshots(appName, processedCode);

                    sendProgress(emitter, 90, "正在保存降级截图...");
                    sendProgress(emitter, 100, "降级截图生成完成");

                    Map<String, Object> result = new HashMap<>();
                    result.put("completed", true);
                    result.put("screenshots", fallbackScreenshots);
                    result.put("mode", "fallback");
                    result.put("message", "Chrome初始化失败，使用降级方案生成截图");

                    if (emitter != null) {
                        emitter.send(SseEmitter.event().name("data").data(result));
                        emitter.complete();
                    } else {
                        log.debug("SSE emitter为null，跳过发送降级截图结果");
                    }
                    return;
                }

                sendProgress(emitter, 50, "正在加载页面...");

                File tempFile = new File(tempFilePath);
                String fileUrl = tempFile.toURI().toString();
                log.info("加载HTML文件URL: {}", fileUrl);
                
                driver.get(fileUrl);
                Thread.sleep(5000);
                
                JavascriptExecutor js = (JavascriptExecutor) driver;
                js.executeScript("location.reload(true);");
                Thread.sleep(5000);

                waitForPageLoad(driver);

                sendProgress(emitter, 70, "正在生成页面截图...");

                List<Map<String, String>> screenshots = captureRealScreenshots(driver, appName, fileId);

                sendProgress(emitter, 95, "正在保存截图文件...");

                if (screenshots.isEmpty()) {
                    log.warn("未能生成截图，尝试生成默认截图");
                    Map<String, String> defaultScreenshot = captureDefaultScreenshot(driver, appName);
                    if (defaultScreenshot != null) {
                        screenshots.add(defaultScreenshot);
                    }
                }

                sendProgress(emitter, 100, "真实代码截图生成完成");

                Map<String, Object> finalResult = new HashMap<>();
                finalResult.put("completed", true);
                finalResult.put("screenshots", screenshots);
                finalResult.put("screenshotCount", screenshots.size());
                finalResult.put("isRealCodeScreenshot", true);

                List<String> screenshotPaths = screenshots.stream()
                    .map(s -> s.get("fileName"))
                    .collect(java.util.stream.Collectors.toList());
                finalResult.put("screenshotFiles", screenshotPaths);

                log.info("真实代码截图生成完成: appName={}, 截图数量={}", appName, screenshots.size());

                if (emitter != null) {
                emitter.send(SseEmitter.event()
                    .name("data")
                    .data(finalResult));
                emitter.complete();
                } else {
                    log.debug("SSE emitter为null，跳过发送最终结果");
                }

            } catch (Exception e) {
                log.error("生成真实代码截图失败: appName={}", appName, e);
                sendError(emitter, "生成真实代码截图失败: " + e.getMessage());
            } finally {
                if (driver != null) {
                    try {
                        driver.quit();
                    } catch (Exception e) {
                        log.warn("关闭WebDriver失败", e);
                    }
                }
                if (tempFilePath != null) {
                    cleanupTempFile(tempFilePath);
                }
            }
        });
    }



    private String preprocessCodeSimple(String code, String appName) {
        try {
            if (code == null || code.trim().isEmpty()) {
                log.info("代码为空，生成默认HTML结构");
                return generateDefaultHtml(appName);
            }

            String processedCode = code.trim();

            if (processedCode.startsWith("\uFEFF")) {
                processedCode = processedCode.substring(1);
            }

            processedCode = extractPureHtmlCode(processedCode);
            log.info("HTML代码提取完成，提取后长度: {}", processedCode.length());

            processedCode = processedCode.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");

            processedCode = processedCode.replaceAll("^```html\\s*", "").replaceAll("^```\\s*", "");
            processedCode = processedCode.replaceAll("\\s*```$", "");

            processedCode = processedCode.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");

            String lowerCode = processedCode.toLowerCase();
            boolean hasHtml = lowerCode.contains("<html");
            boolean hasBody = lowerCode.contains("<body");

            if (!hasHtml || !hasBody) {
                log.info("代码缺少基本HTML结构，进行包装");
                processedCode = wrapWithBasicHtml(processedCode, appName);
            }

            log.info("代码预处理完成，处理后长度: {}", processedCode.length());
            return processedCode;

        } catch (Exception e) {
            log.error("代码预处理失败，使用默认HTML", e);
            return generateDefaultHtml(appName);
        }
    }

    /**
     * 用基本HTML结构包装代码
     */
    private String wrapWithBasicHtml(String content, String appName) {
        return String.format("""
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s</title>
                <link href="https://cdnjs.cloudflare.com/ajax/libs/bootstrap/5.3.0/css/bootstrap.min.css" rel="stylesheet">
                <style>
                    body { font-family: 'Microsoft YaHei', sans-serif; padding: 20px; }
                    .container { max-width: 1200px; margin: 0 auto; }
                </style>
            </head>
            <body>
                <div class="container">
                    %s
                </div>
                <script src="https://cdnjs.cloudflare.com/ajax/libs/bootstrap/5.3.0/js/bootstrap.bundle.min.js"></script>
            </body>
            </html>
            """, escapeHtml(appName), content);
    }

    /**
     * 检查Chrome是否可用
     */
    private boolean checkChromeAvailability() {
        try {
            
            String chromeDriverPath = CrossPlatformUtil.getChromeDriverPath("max-serve");
            File chromeDriverFile = new File(chromeDriverPath);

            if (!chromeDriverFile.exists()) {
                log.error("ChromeDriver文件不存在: {}", chromeDriverPath);
                return false;
            }

            log.info("找到ChromeDriver: {}", chromeDriverPath);
            System.setProperty("webdriver.chrome.driver", chromeDriverPath);

            
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--disable-gpu");
            options.addArguments("--window-size=1920,1080");
            options.addArguments("--force-device-scale-factor=1");
            options.addArguments("--enable-javascript");
            options.addArguments("--load-images=yes");

            log.info("正在创建测试ChromeDriver实例...");

            WebDriver testDriver = null;
            boolean success = false;

            try {
                long startTime = System.currentTimeMillis();

                
                ChromeDriverService service = new ChromeDriverService.Builder()
                        .usingDriverExecutable(new File(chromeDriverPath))
                        .build();

                testDriver = new ChromeDriver(service, options);
                long initTime = System.currentTimeMillis() - startTime;

                log.info("测试ChromeDriver创建成功，耗时: {}ms", initTime);
                success = true;

            } catch (Exception driverException) {
                log.error("ChromeDriver创建失败: {}", driverException.getMessage());
                success = false;
            } finally {
                if (testDriver != null) {
                    try {
                        testDriver.quit();
                        log.info("测试ChromeDriver已关闭");
                    } catch (Exception e) {
                        log.warn("关闭测试ChromeDriver时出错: {}", e.getMessage());
                    }
                }
            }

            return success;

        } catch (Exception e) {
            log.error("Chrome可用性检查失败: {}", e.getMessage());
            return false;
        }
    }









    /**
     * 智能代码修复器 - 核心修复逻辑
     */
    private String intelligentCodeFixer(String rawCode, String appName) {
        try {
            log.info("开始智能代码修复，原始代码长度: {}", rawCode != null ? rawCode.length() : 0);

            if (rawCode == null || rawCode.trim().isEmpty()) {
                log.info("代码为空，生成默认HTML结构");
                return generateDefaultHtml(appName);
            }

            String code = rawCode.trim();

            
            code = preprocessCode(code);

            
            CodeStructureInfo structureInfo = analyzeCodeStructure(code);
            log.info("代码结构分析: {}", structureInfo);

            
            if (structureInfo.isCompleteHtml) {
                code = fixCompleteHtmlDocument(code, structureInfo);
            } else if (structureInfo.hasHtmlTags) {
                code = wrapHtmlFragment(code, appName, structureInfo);
            } else {
                code = wrapPlainContent(code, appName);
            }

            
            code = fixHtmlSyntax(code);

            
            code = enhanceWithDependencies(code, appName);

            
            code = finalizeCode(code);

            log.info("智能代码修复完成，修复后代码长度: {}", code.length());
            return code;

        } catch (Exception e) {
            log.error("智能代码修复失败", e);
            throw new RuntimeException("代码修复失败: " + e.getMessage(), e);
        }
    }

    /**
     * 代码结构信息类
     */
    private static class CodeStructureInfo {
        boolean isCompleteHtml = false;
        boolean hasHtmlTags = false;
        boolean hasHead = false;
        boolean hasBody = false;
        boolean hasDoctype = false;
        boolean hasCss = false;
        boolean hasJs = false;
        boolean hasBootstrap = false;
        String charset = "UTF-8";
        String title = "";

        @Override
        public String toString() {
            return String.format("CodeStructureInfo{complete=%s, hasHtml=%s, hasHead=%s, hasBody=%s, hasDoctype=%s}",
                isCompleteHtml, hasHtmlTags, hasHead, hasBody, hasDoctype);
        }
    }

    /**
     * 验证HTML结构完整性 - 更宽松的验证
     */
    private boolean validateHtmlStructure(String code) {
        if (code == null || code.trim().isEmpty()) {
            return false;
        }

        String lowerCode = code.toLowerCase();

        
        boolean hasBasicStructure = lowerCode.contains("<html") && lowerCode.contains("<body");

        
        boolean hasClosingTags = lowerCode.contains("</html>") && lowerCode.contains("</body>");

        return hasBasicStructure && hasClosingTags;
    }

    /**
     * 代码预处理 - 清理和标准化
     */
    private String preprocessCode(String code) {
        
        if (code.startsWith("\uFEFF")) {
            code = code.substring(1);
        }

        
        code = code.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");

        
        code = code.replaceAll("^```html\\s*", "").replaceAll("^```\\s*", "");
        code = code.replaceAll("\\s*```$", "");

        
        code = code.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");

        
        code = code.replaceAll("\\n\\s*\\n\\s*\\n", "\n\n");

        return code.trim();
    }

    /**
     * 分析代码结构
     */
    private CodeStructureInfo analyzeCodeStructure(String code) {
        CodeStructureInfo info = new CodeStructureInfo();
        String lowerCode = code.toLowerCase();

        
        info.hasDoctype = lowerCode.contains("<!doctype");
        info.hasHtmlTags = lowerCode.contains("<html");
        info.hasHead = lowerCode.contains("<head");
        info.hasBody = lowerCode.contains("<body");

        
        if (!info.hasHtmlTags) {
            
            String[] commonTags = {"<div", "<p", "<span", "<h1", "<h2", "<h3", "<h4", "<h5", "<h6",
                                  "<ul", "<ol", "<li", "<table", "<tr", "<td", "<th", "<form",
                                  "<input", "<button", "<a", "<img", "<br", "<hr", "<nav", "<section"};
            for (String tag : commonTags) {
                if (lowerCode.contains(tag)) {
                    info.hasHtmlTags = true;
                    break;
                }
            }
        }

        
        info.isCompleteHtml = info.hasHtmlTags && info.hasHead && info.hasBody;

        
        info.hasCss = lowerCode.contains("<style") || lowerCode.contains(".css");
        info.hasJs = lowerCode.contains("<script") || lowerCode.contains(".js");

        
        info.hasBootstrap = lowerCode.contains("bootstrap") || lowerCode.contains("bs-");

        
        Pattern titlePattern = Pattern.compile("<title[^>]*>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
        Matcher titleMatcher = titlePattern.matcher(code);
        if (titleMatcher.find()) {
            info.title = titleMatcher.group(1).trim();
        }

        
        Pattern charsetPattern = Pattern.compile("charset\\s*=\\s*[\"']?([^\"'\\s>]+)", Pattern.CASE_INSENSITIVE);
        Matcher charsetMatcher = charsetPattern.matcher(code);
        if (charsetMatcher.find()) {
            info.charset = charsetMatcher.group(1);
        }

        return info;
    }

    /**
     * 修复完整HTML文档
     */
    private String fixCompleteHtmlDocument(String code, CodeStructureInfo info) {
        
        if (!info.hasDoctype) {
            if (!code.toLowerCase().startsWith("<!doctype")) {
                code = "<!DOCTYPE html>\n" + code;
            }
        }

        
        if (!code.toLowerCase().contains("lang=")) {
            code = code.replaceFirst("<html", "<html lang=\"zh-CN\"");
        }

        
        if (!code.toLowerCase().contains("charset")) {
            code = addCharsetToHead(code);
        }

        if (!code.toLowerCase().contains("viewport")) {
            code = addViewportToHead(code);
        }

        return code;
    }

    /**
     * 包装HTML片段
     */
    private String wrapHtmlFragment(String code, String appName, CodeStructureInfo info) {
        String title = info.title.isEmpty() ? appName : info.title;

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-CN\">\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"").append(info.charset).append("\">\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("    <title>").append(escapeHtml(title)).append("</title>\n");

        
        if (!info.hasBootstrap) {
            html.append("    <link href=\"https://cdnjs.cloudflare.com/ajax/libs/bootstrap/5.3.0/css/bootstrap.min.css\" rel=\"stylesheet\">\n");
            html.append("    <link href=\"https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css\" rel=\"stylesheet\">\n");
        }

        html.append("    <style>\n");
        html.append("        body { font-family: 'Microsoft YaHei', sans-serif; padding: 20px; }\n");
        html.append("        .container { max-width: 1200px; margin: 0 auto; }\n");
        html.append("    </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("    <div class=\"container\">\n");
        html.append(code);
        html.append("\n    </div>\n");

        
        if (!info.hasBootstrap) {
            html.append("    <script src=\"https://cdnjs.cloudflare.com/ajax/libs/bootstrap/5.3.0/js/bootstrap.bundle.min.js\"></script>\n");
        }

        html.append("    <script>\n");
        html.append("        document.addEventListener('DOMContentLoaded', function() {\n");
        html.append("            console.log('页面加载完成');\n");
        html.append("        });\n");
        html.append("    </script>\n");
        html.append("</body>\n");
        html.append("</html>");

        return html.toString();
    }

    /**
     * 创建临时HTML文件
     */
    private String createTempHtmlFile(String appName, String htmlContent) throws IOException {
        Path tempDir = Paths.get(TEMP_DIR);
        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = String.format("%s_real_%s.html",
            appName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_"), timestamp);
        Path htmlFile = tempDir.resolve(fileName);

        
        try (FileWriter writer = new FileWriter(htmlFile.toFile(), StandardCharsets.UTF_8)) {
            writer.write(htmlContent);
        }

        log.info("创建临时HTML文件: {}", htmlFile.toString());

        
        try {
            String savedContent = Files.readString(htmlFile, StandardCharsets.UTF_8);
            log.info("文件保存验证 - 文件大小: {} bytes, 内容长度: {} 字符",
                Files.size(htmlFile), savedContent.length());

            
            boolean hasDoctype = savedContent.toLowerCase().contains("<!doctype html>");
            boolean hasButtons = savedContent.contains("home-btn") && savedContent.contains("user-btn");
            boolean hasBootstrap = savedContent.contains("bootstrap");
            log.info("内容检查 - DOCTYPE: {}, 按钮: {}, Bootstrap: {}", hasDoctype, hasButtons, hasBootstrap);

            
            String[] lines = savedContent.split("\\r?\\n");
            log.info("HTML文件前10行内容:");
            for (int i = 0; i < Math.min(10, lines.length); i++) {
                log.info("第{}行: {}", i+1, lines[i]);
            }

        } catch (Exception e) {
            log.error("验证文件内容时出错: {}", e.getMessage());
        }

        return htmlFile.toString();
    }

    /**
     * 生成默认HTML结构（用于空代码）
     */
    private String generateDefaultHtml(String appName) {
        return String.format("""
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s</title>
                <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.1.3/dist/css/bootstrap.min.css" rel="stylesheet">
                <style>
                    body { font-family: 'Microsoft YaHei', sans-serif; padding: 40px; }
                    .welcome { text-align: center; margin-top: 50px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="welcome">
                        <h1>%s</h1>
                        <p class="lead">欢迎使用系统</p>
                    </div>
                </div>
                <script src="https://unpkg.com/bootstrap@5.1.3/dist/js/bootstrap.bundle.min.js"></script>
            </body>
            </html>
            """, escapeHtml(appName), escapeHtml(appName));
    }

    /**
     * 包装纯文本内容
     */
    private String wrapPlainContent(String content, String appName) {
        return String.format("""
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s</title>
                <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.1.3/dist/css/bootstrap.min.css" rel="stylesheet">
                <style>
                    body { font-family: 'Microsoft YaHei', sans-serif; padding: 40px; }
                    .content { background: #f8f9fa; padding: 30px; border-radius: 8px; }
                    pre { background: #e9ecef; padding: 15px; border-radius: 4px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>%s</h1>
                    <div class="content">
                        <pre>%s</pre>
                    </div>
                </div>
                <script src="https://unpkg.com/bootstrap@5.1.3/dist/js/bootstrap.bundle.min.js"></script>
            </body>
            </html>
            """, escapeHtml(appName), escapeHtml(appName), escapeHtml(content));
    }

    /**
     * HTML语法修复
     */
    private String fixHtmlSyntax(String code) {
        
        code = code.replaceAll("<(area|base|br|col|embed|hr|img|input|link|meta|param|source|track|wbr)([^>]*?)(?<!/)>",
                              "<$1$2 />");

        
        code = fixUnclosedTags(code);

        
        code = fixAttributeQuotes(code);

        
        code = fixScriptAndStyleTags(code);

        return code;
    }

    /**
     * 修复未闭合标签的改进版本
     */
    private String fixUnclosedTags(String code) {
        
        String[] tagsToFix = {"div", "p", "span", "h1", "h2", "h3", "h4", "h5", "h6",
                             "ul", "ol", "li", "table", "tr", "td", "th", "thead", "tbody",
                             "form", "section", "article", "nav", "header", "footer", "main"};

        for (String tag : tagsToFix) {
            code = fixSpecificTag(code, tag);
        }

        return code;
    }

    /**
     * 修复特定标签
     */
    private String fixSpecificTag(String code, String tag) {
        Pattern openPattern = Pattern.compile("<" + tag + "(?:\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
        Pattern closePattern = Pattern.compile("</" + tag + ">", Pattern.CASE_INSENSITIVE);

        Matcher openMatcher = openPattern.matcher(code);
        Matcher closeMatcher = closePattern.matcher(code);

        int openCount = 0;
        int closeCount = 0;

        while (openMatcher.find()) openCount++;
        while (closeMatcher.find()) closeCount++;

        
        if (openCount > closeCount) {
            StringBuilder sb = new StringBuilder(code);
            for (int i = 0; i < (openCount - closeCount); i++) {
                sb.append("</").append(tag).append(">");
            }
            return sb.toString();
        }

        return code;
    }

    /**
     * 修复属性引号
     */
    private String fixAttributeQuotes(String code) {
        
        Pattern attrPattern = Pattern.compile("(\\w+)\\s*=\\s*([^\"'\\s>][^\\s>]*)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = attrPattern.matcher(code);

        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String attrName = matcher.group(1);
            String attrValue = matcher.group(2);
            matcher.appendReplacement(sb, attrName + "=\"" + attrValue + "\"");
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * 修复script和style标签
     */
    private String fixScriptAndStyleTags(String code) {
        
        code = code.replaceAll("<script([^>]*)>([^<]*(?:(?!</script>)<[^<]*)*)</script>",
                              "<script$1>$2</script>");

        
        code = code.replaceAll("<style([^>]*)>([^<]*(?:(?!</style>)<[^<]*)*)</style>",
                              "<style$1>$2</style>");

        return code;
    }

    /**
     * 查找可用端口
     */
    private int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 启动本地Web服务器
     */
    private HttpServer startLocalServer(int port, String htmlFilePath) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        
        server.createContext("/", new StaticFileHandler(htmlFilePath));

        server.setExecutor(null);
        server.start();

        log.info("本地Web服务器已启动: http://localhost:{}", port);
        return server;
    }

    /**
     * 静态文件处理器
     */
    private static class StaticFileHandler implements HttpHandler {
        private final String htmlFilePath;

        public StaticFileHandler(String htmlFilePath) {
            this.htmlFilePath = htmlFilePath;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();

            
            if ("/".equals(path) || "/index.html".equals(path)) {
                File file = new File(htmlFilePath);
                if (file.exists()) {
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, file.length());

                    try (OutputStream os = exchange.getResponseBody();
                         InputStream is = new FileInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                    }
                } else {
                    String response = "File not found";
                    exchange.sendResponseHeaders(404, response.length());
                    exchange.getResponseBody().write(response.getBytes());
                    exchange.getResponseBody().close();
                }
            } else {
                String response = "Not found";
                exchange.sendResponseHeaders(404, response.length());
                exchange.getResponseBody().write(response.getBytes());
                exchange.getResponseBody().close();
            }
        }
    }

    /**
     * 初始化无头浏览器 - 带重试机制
     */
    private WebDriver initializeWebDriverWithRetry() {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                log.info("尝试初始化ChromeDriver，第{}次尝试", i + 1);
                return initializeWebDriver();
            } catch (Exception e) {
                log.warn("第{}次初始化ChromeDriver失败: {}", i + 1, e.getMessage());
                if (i == maxRetries - 1) {
                    log.error("所有重试都失败，无法初始化ChromeDriver");
                    return null;
                }
                try {
                    Thread.sleep(2000); 
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 初始化无头浏览器 - 改进版本，参考Python成功实现
     */
    private WebDriver initializeWebDriver() {
        log.info("开始初始化ChromeDriver...");
        
        // 🔥 清理临时文件和缓存目录
        cleanupTempFiles();

        ChromeOptions options = new ChromeOptions();

        
        options.addArguments("--headless");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--force-device-scale-factor=1");
        options.addArguments("--enable-javascript");
        options.addArguments("--load-images=yes");
        
        // 🔥 新增：缓存控制选项 - 解决截图质量下降问题
        options.addArguments("--disable-web-security");
        options.addArguments("--disable-features=VizDisplayCompositor");
        options.addArguments("--disable-application-cache");
        options.addArguments("--disable-background-networking");
        options.addArguments("--disable-background-timer-throttling");
        options.addArguments("--disable-renderer-backgrounding");
        options.addArguments("--disable-backgrounding-occluded-windows");
        options.addArguments("--disable-client-side-phishing-detection");
        options.addArguments("--disable-default-apps");
        options.addArguments("--disable-hang-monitor");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--disable-prompt-on-repost");
        options.addArguments("--disable-sync");
        options.addArguments("--disable-translate");
        options.addArguments("--disable-ipc-flooding-protection");
        options.addArguments("--incognito"); 
        options.addArguments("--no-first-run");
        options.addArguments("--no-default-browser-check");
        
        // 🔥 强制刷新和缓存控制
        options.addArguments("--aggressive-cache-discard");
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-plugins");
        options.addArguments("--disable-images"); 
        options.addArguments("--enable-logging");
        options.addArguments("--log-level=0");
        
        
        options.addArguments("--memory-pressure-off");
        options.addArguments("--max_old_space_size=4096");
        
        
        String uniqueId = java.util.UUID.randomUUID().toString().substring(0, 8);
        options.addArguments("--user-data-dir=" + System.getProperty("java.io.tmpdir") + "/chrome_temp_" + System.currentTimeMillis() + "_" + uniqueId);

        try {
            log.info("正在创建ChromeDriver实例...");
            long startTime = System.currentTimeMillis();

            
            String osName = System.getProperty("os.name").toLowerCase();
            String chromeDriverName = osName.contains("win") ? "chromedriver.exe" : "chromedriver";
            String chromeDriverPath = null;
            
            // 1. 优先检查系统路径（apt/yum安装的）
            String systemPath = "/usr/bin/chromedriver";
            if (new File(systemPath).exists()) {
                chromeDriverPath = systemPath;
                log.info("使用系统ChromeDriver: {}", chromeDriverPath);
            } 
            // 2. 检查自定义路径（Windows或Docker挂载）
            else {
                chromeDriverPath = System.getProperty("user.dir") + File.separator + "max-serve" + File.separator + chromeDriverName;
                log.info("操作系统: {}, ChromeDriver路径: {}", osName, chromeDriverPath);
            }

            
            String logPath = System.getProperty("java.io.tmpdir") + File.separator + "chromedriver_" + System.currentTimeMillis() + ".log";
            ChromeDriverService service = new ChromeDriverService.Builder()
                    .usingDriverExecutable(new File(chromeDriverPath))
                    .withLogFile(new File(logPath))
                    .withVerbose(true)
                    .build();
            log.info("ChromeDriver日志路径: {}", logPath);

            
            WebDriver driver = createChromeDriverWithTimeout(options, service, 30);

            long endTime = System.currentTimeMillis();
            log.info("ChromeDriver实例创建成功，耗时: {}ms", (endTime - startTime));

            
            driver.manage().window().setSize(new Dimension(1920, 1080));

            
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(120)); 
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(20));   
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));    
            
            // 🔥 清除浏览器缓存和Cookie
            driver.manage().deleteAllCookies();
            
            // 🔥 禁用浏览器缓存
            JavascriptExecutor js = (JavascriptExecutor) driver;
            try {
                js.executeScript("window.localStorage.clear();");
                js.executeScript("window.sessionStorage.clear();");
                log.info("浏览器存储已清理");
            } catch (WebDriverException e) {
                // localStorage/sessionStorage 在某些情况下不可用（如 data: URLs），忽略此错误
                log.warn("清理浏览器存储失败（可能是 headless 模式限制），继续执行: {}", e.getMessage());
            }

            log.info("无头浏览器初始化完成: 1920x1080，缓存已清理");
            return driver;

        } catch (Exception e) {
            log.error("创建ChromeDriver实例失败: {}", e.getMessage(), e);
            throw new RuntimeException("无法创建ChromeDriver实例，请检查Chrome和ChromeDriver是否正确安装: " + e.getMessage(), e);
        }
    }

    /**
     * 创建ChromeDriver实例，带超时控制 - 与Python测试一致
     */
    private WebDriver createChromeDriverWithTimeout(ChromeOptions options, ChromeDriverService service, int timeoutSeconds) throws Exception {
        CompletableFuture<WebDriver> future = CompletableFuture.supplyAsync(() -> {
            try {
                log.info("在后台线程中创建ChromeDriver（使用Service）...");
                return new ChromeDriver(service, options);
            } catch (Exception e) {
                log.error("后台线程创建ChromeDriver失败: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        });

        try {
            WebDriver driver = future.get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("ChromeDriver创建成功");
            return driver;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("ChromeDriver创建超时（{}秒）", timeoutSeconds);
            throw new RuntimeException("ChromeDriver创建超时，请检查Chrome安装和配置");
        } catch (Exception e) {
            log.error("ChromeDriver创建失败: {}", e.getMessage());
            throw new RuntimeException("ChromeDriver创建失败: " + e.getMessage(), e);
        }
    }

    /**
     * 🔥 清理临时文件和缓存目录 - 解决截图质量下降问题
     */
    private void cleanupTempFiles() {
        try {
            String tempDir = System.getProperty("java.io.tmpdir");
            File tempFolder = new File(tempDir);
            
            
            File[] chromeTemps = tempFolder.listFiles((dir, name) -> 
                name.startsWith("chrome_temp_"));
            
            if (chromeTemps != null) {
                for (File chromeTemp : chromeTemps) {
                    if (chromeTemp.isDirectory()) {
                        deleteDirectoryRecursively(chromeTemp);
                        log.info("清理Chrome临时目录: {}", chromeTemp.getName());
                    }
                }
            }
            
            log.info("临时文件清理完成");
        } catch (Exception e) {
            log.warn("清理临时文件时出现异常: {}", e.getMessage());
        }
    }
    
    /**
     * 递归删除目录
     */
    private void deleteDirectoryRecursively(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectoryRecursively(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }

    /**
     * 等待页面完全加载 - 智能等待版本，增强内容检测
     */
    private void waitForPageLoad(WebDriver driver) {
        try {
            log.info("开始智能等待页面加载...");

            
            log.info("阶段1: 等待DOM基本结构加载...");
            Thread.sleep(5000); 

            
            log.info("阶段2: 等待外部CDN资源加载...");
            waitForExternalResources(driver);

            
            log.info("阶段3: 等待JavaScript执行完成...");
            waitForJavaScriptToLoad(driver);

            
            log.info("阶段4: 等待CSS样式加载...");
            waitForCSSToLoad(driver);

            
            log.info("阶段5: 等待Bootstrap框架加载...");
            waitForBootstrapComponents(driver);

            
            log.info("阶段6: 等待动态内容生成...");
            waitForDynamicContent(driver);

            
            log.info("阶段7: 等待Font Awesome图标加载...");
            waitForFontAwesome(driver);

            
            log.info("阶段8: 额外等待异步操作完成...");
            Thread.sleep(20000); 

            
            log.info("阶段7: 验证页面内容完整性...");
            validatePageContent(driver);

            
            log.info("阶段8: 调整页面显示设置...");
            adjustPageDisplay(driver);

            log.info("智能等待页面加载完成");

        } catch (InterruptedException e) {
            log.warn("页面加载等待被中断: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("智能等待页面加载时出现异常，继续执行截图: {}", e.getMessage());
        }
    }

    /**
     * 捕获真实页面截图
     */
    private List<Map<String, String>> captureRealScreenshots(WebDriver driver, String appName, String fileId) throws IOException {
        log.info("🚀🚀🚀 进入captureRealScreenshots方法 - 功能切换截图模式 🚀🚀🚀");
        List<Map<String, String>> screenshots = new ArrayList<>();

        
        Path screenshotDir = Paths.get(SCREENSHOT_DIR);
        if (!Files.exists(screenshotDir)) {
            Files.createDirectories(screenshotDir);
        }

        try {
            
            log.info("等待页面完全加载...");
            Thread.sleep(5000); 
            
            
            JavascriptExecutor js = (JavascriptExecutor) driver;
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15)); 
            
            
            wait.until(webDriver -> {
                try {
                    Boolean functionExists = (Boolean) js.executeScript("return typeof showContent === 'function'");
                    return Boolean.TRUE.equals(functionExists);
                } catch (Exception e) {
                    return false;
                }
            });
            
            
            log.info("页面JavaScript加载完成，等待样式渲染...");
            Thread.sleep(3000); 
            
            log.info("开始截图流程");

            
            String[][] functions = {
                {"home", "首页", "home-btn", "home-content"},
                {"user", "用户管理", "user-btn", "user-content"},
                {"data", "数据分析", "data-btn", "data-content"},
                {"settings", "系统设置", "settings-btn", "settings-content"},
                {"message", "消息中心", "message-btn", "message-content"}
            };

            for (String[] function : functions) {
                String functionKey = function[0];
                String functionName = function[1];
                String buttonId = function[2];
                String contentId = function[3];

                try {
                    log.info("=== 开始截图: {} ===", functionName);
                    
                    
                    try {
                        String currentStatus = (String) js.executeScript("""
                            var activeElements = [];
                            var allContent = document.querySelectorAll('[id$="-content"]');
                            allContent.forEach(function(el) {
                                if (el.style.display !== 'none' && !el.classList.contains('d-none')) {
                                    activeElements.push(el.id);
                                }
                            });
                            return 'Current active content: ' + activeElements.join(', ');
                            """);
                        log.info("页面当前状态: {}", currentStatus);
                    } catch (Exception e) {
                        log.warn("检查页面状态时出错: {}", e.getMessage());
                    }
                    
                    
                    boolean switched = false;
                    try {
                        
                        Boolean functionExists = (Boolean) js.executeScript("return typeof showContent === 'function'");
                        if (!Boolean.TRUE.equals(functionExists)) {
                            log.warn("页面中不存在showContent函数，跳过JavaScript切换");
                        } else {
                            log.info("--- 尝试方式1: showContent函数调用 ---");
                            log.info("showContent函数存在: true");
                            log.info("调用showContent函数切换到{}", functionName);
                            String switchScript = String.format("showContent('%s');", contentId);
                            js.executeScript(switchScript);
                            Thread.sleep(3000); 
                            
                            
                            Boolean contentVisible = (Boolean) js.executeScript(String.format("""
                                var element = document.getElementById('%s');
                                if (!element) return false;
                                
                                
                                var isVisible = element.style.display === 'block' || 
                                               element.style.display === '' || 
                                               element.classList.contains('active') ||
                                               element.classList.contains('show') ||
                                               window.getComputedStyle(element).display !== 'none';
                                
                                console.log('Element %s visibility check: ', '%s', isVisible);
                                return isVisible;
                                """, contentId, contentId));
                            
                            switched = Boolean.TRUE.equals(contentVisible);

                    if (switched) {
                                log.info("✅ {}功能通过showContent切换成功", functionName);
                            } else {
                                log.warn("❌ {}功能showContent切换后验证失败", functionName);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("JavaScript切换{}功能失败: {}", functionName, e.getMessage());
                    }
                    
                    
                    if (!switched) {
                        log.info("--- 尝试方式2: 强制JavaScript切换 ---");
                        log.info("准备强制切换到{}", functionName);
                        try {
                            
                            String forceSwitch = String.format("""
                                console.log('=== 开始强制切换到: %s ===');
                                
                                // 1. 查找并隐藏所有可能的内容区域
                                var selectors = ['.content-section', '.content-panel', '.tab-pane', '[id$="-content"]'];
                                var hiddenCount = 0;
                                selectors.forEach(function(selector) {
                                    var elements = document.querySelectorAll(selector);
                                    elements.forEach(function(el) {
                                        el.style.display = 'none';
                                        el.classList.remove('active', 'show');
                                        hiddenCount++;
                                    });
                                });
                                console.log('隐藏了 ' + hiddenCount + ' 个内容区域');
                                
                                // 2. 显示目标内容
                                var targetContent = document.getElementById('%s');
                                if (targetContent) {
                                    targetContent.style.display = 'block';
                                    targetContent.classList.add('active', 'show');
                                    console.log('目标内容已显示: %s');
                                    console.log('目标元素信息:', {
                                        tagName: targetContent.tagName,
                                        className: targetContent.className,
                                        style: targetContent.style.cssText
                                    });
                                } else {
                                    console.error('找不到目标内容: %s');
                                    return false;
                                }
                                
                                // 3. 更新导航状态
                                var navSelectors = ['.nav-link', '.nav-item a', 'a[id$="-btn"]'];
                                navSelectors.forEach(function(selector) {
                                    var links = document.querySelectorAll(selector);
                                    links.forEach(function(link) {
                                        link.classList.remove('active');
                                    });
                                });
                                
                                // 4. 激活对应的导航按钮
                                var targetButton = document.getElementById('%s');
                                if (targetButton) {
                                    targetButton.classList.add('active');
                                    console.log('导航按钮已激活: %s');
                                } else {
                                    console.warn('找不到导航按钮: %s');
                                }
                                
                                console.log('强制切换完成');
                                return true;
                                """, functionName, contentId, contentId, contentId, buttonId, buttonId, buttonId);
                            
                            Boolean result = (Boolean) js.executeScript(forceSwitch);
                            if (Boolean.TRUE.equals(result)) {
                                switched = true;
                                log.info("✅ {}功能强制切换成功", functionName);
                                Thread.sleep(2000); 
                            } else {
                                log.warn("❌ {}功能强制切换失败", functionName);
                            }
                        } catch (Exception e) {
                            log.error("强制切换{}功能失败: {}", functionName, e.getMessage());
                        }
                    }
                    
                    
                    if (!switched) {
                        log.info("--- 尝试方式3: 按钮点击切换 ---");
                        log.info("尝试使用按钮点击方式切换到{}", functionName);
                        switched = testSwitchToFunction(driver, buttonId, contentId, functionName);
                        if (switched) {
                            log.info("✅ {}功能通过按钮点击切换成功", functionName);
                        } else {
                            log.error("❌ {}功能按钮点击切换也失败", functionName);
                        }
                    }
                    
                    if (switched) {
                        
                        
                        boolean renderOk = renderChecker.checkRenderComplete(driver, contentId, functionName);
                        
                        if (!renderOk) {
                            log.warn("⚠️ {}内容渲染检查失败，但继续截图", functionName);
                        }
                        
                        
                        js.executeScript("window.scrollTo(0, 0);");
                        Thread.sleep(1000); 

                        
                        try {
                            String elementStatus = (String) js.executeScript(String.format("""
                                var element = document.getElementById('%s');
                                if (!element) return 'NOT_FOUND';
                                var computedStyle = window.getComputedStyle(element);
                                return 'Element %s status: display=' + computedStyle.display + 
                                       ', visibility=' + computedStyle.visibility + 
                                       ', width=' + element.offsetWidth + 
                                       ', height=' + element.offsetHeight;
                                """, contentId, contentId));
                            log.info("截图前最终检查 - {}", elementStatus);
                        } catch (Exception e) {
                            log.warn("截图前检查出错: {}", e.getMessage());
                        }

                        
                        String screenshotFile = captureScreenshot(driver, appName, functionKey, functionName, fileId);
                        if (screenshotFile != null) {
                            
                            File file = new File(SCREENSHOT_DIR, screenshotFile);
                            long fileSize = file.exists() ? file.length() : 0;
                            log.info("成功生成{}功能截图: {}, 文件大小: {} bytes", functionName, screenshotFile, fileSize);

                            
                            try {
                                saveScreenshotToDatabase(fileId, functionName, screenshotFile, file);
                                log.info("成功保存{}功能截图到数据库", functionName);
                            } catch (Exception e) {
                                log.error("保存{}功能截图到数据库失败", functionName, e);
                            }

                            screenshots.add(createScreenshotInfo(functionName, screenshotFile));
                        } else {
                            log.warn("{}功能截图生成失败", functionName);
                        }
                    } else {
                        log.warn("无法切换到{}功能，跳过截图", functionName);
                    }

                } catch (Exception e) {
                    log.error("截图{}功能时出错: {}", functionName, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("捕获真实页面截图时出错: {}", e.getMessage(), e);
        }
        return screenshots;
    }

    /**
     * 确保内容区域可见
     */
    private void ensureContentVisible(WebDriver driver, String contentId) {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;

            
            String checkScript = String.format(
                "var element = document.getElementById('%s'); " +
                "if (element) { " +
                "  element.style.display = 'block'; " +
                "  element.classList.add('active'); " +
                "  return element.offsetHeight > 0; " +
                "} " +
                "return false;",
                contentId
            );

            Boolean isVisible = (Boolean) js.executeScript(checkScript);
            log.info("内容区域 {} 可见性: {}", contentId, isVisible);

            
            String scrollScript = String.format(
                "var element = document.getElementById('%s'); " +
                "if (element) { element.scrollIntoView({behavior: 'instant', block: 'start'}); }",
                contentId
            );
            js.executeScript(scrollScript);

        } catch (Exception e) {
            log.warn("确保内容可见时出错: {}", e.getMessage());
        }
    }

    /**
     * 切换到指定功能
     * @param driver WebDriver实例
     * @param buttonId 功能按钮的ID
     * @param contentId 功能内容区域的ID
     * @return 是否成功切换
     */
    private boolean switchToFunction(WebDriver driver, String buttonId, String contentId) {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;

            
            try {
                WebElement button = driver.findElement(By.id(buttonId));
                if (button.isDisplayed() && button.isEnabled()) {
                    button.click();
                    log.info("通过按钮ID {}成功点击", buttonId);
                    Thread.sleep(500); 
                    return true;
                }
            } catch (Exception e) {
                log.debug("通过按钮ID {}点击失败: {}", buttonId, e.getMessage());
            }

            
            try {
                String clickScript = String.format("document.getElementById('%s').click();", buttonId);
                js.executeScript(clickScript);
                log.info("通过JavaScript点击按钮 {}成功", buttonId);
                Thread.sleep(500);
                return true;
            } catch (Exception e) {
                log.debug("通过JavaScript点击按钮 {}失败: {}", buttonId, e.getMessage());
            }

            
            try {
                String showContentScript = String.format(
                    "if (typeof showContent === 'function') { showContent('%s'); } " +
                    "else { " +
                    "  var contents = document.querySelectorAll('[id$=\"-content\"]'); " +
                    "  contents.forEach(function(el) { el.style.display = 'none'; }); " +
                    "  var target = document.getElementById('%s'); " +
                    "  if (target) { target.style.display = 'block'; } " +
                    "}",
                    contentId, contentId
                );
                js.executeScript(showContentScript);
                log.info("通过JavaScript直接显示内容 {}成功", contentId);
                Thread.sleep(500);
                return true;
            } catch (Exception e) {
                log.debug("通过JavaScript显示内容 {}失败: {}", contentId, e.getMessage());
            }

            
            try {
                String buttonText = getButtonTextByFunction(buttonId);
                if (buttonText != null) {
                    WebElement button = driver.findElement(By.xpath(String.format("//button[contains(text(), '%s')] | //a[contains(text(), '%s')] | //*[contains(@class, 'btn') and contains(text(), '%s')]", buttonText, buttonText, buttonText)));
                    if (button.isDisplayed()) {
                        button.click();
                        log.info("通过文本内容 {}成功点击", buttonText);
                        Thread.sleep(500);
                        return true;
                    }
                }
            } catch (Exception e) {
                log.debug("通过文本内容点击失败: {}", e.getMessage());
            }

            log.warn("所有切换方法都失败，无法切换到功能: {}", buttonId);
            return false;

        } catch (Exception e) {
            log.error("切换功能时出现异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 根据按钮ID获取对应的中文文本
     */
    private String getButtonTextByFunction(String buttonId) {
        switch (buttonId) {
            case "home-btn": return "首页";
            case "user-btn": return "用户管理";
            case "data-btn": return "数据分析";
            case "settings-btn": return "系统设置";
            case "message-btn": return "消息中心";
            default: return null;
        }
    }

    /**
     * 捕获默认截图（当其他方法失败时使用）
     */
    private Map<String, String> captureDefaultScreenshot(WebDriver driver, String appName) {
        try {
            log.info("生成默认截图");

            
            Thread.sleep(500);

            
            TakesScreenshot takesScreenshot = (TakesScreenshot) driver;
            byte[] screenshotBytes = takesScreenshot.getScreenshotAs(OutputType.BYTES);

            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            String fileName = String.format("%s_default_%s.png",
                appName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_"),
                timestamp);

            
            Path screenshotDir = Paths.get(SCREENSHOT_DIR);
            if (!Files.exists(screenshotDir)) {
                Files.createDirectories(screenshotDir);
            }

            
            Path screenshotPath = Paths.get(SCREENSHOT_DIR, fileName);
            Files.write(screenshotPath, screenshotBytes);

            log.info("成功生成默认截图: {}", fileName);

            Map<String, String> screenshotInfo = new HashMap<>();
            screenshotInfo.put("title", "系统主界面");
            screenshotInfo.put("fileName", fileName);
            return screenshotInfo;

        } catch (Exception e) {
            log.error("生成默认截图失败", e);
            return null;
        }
    }

    /**
     * 捕获单张截图（向后兼容版本）
     */
    private String captureScreenshot(WebDriver driver, String appName, String type, String description) {
        return captureScreenshot(driver, appName, type, description, null);
    }
    
    /**
     * 捕获单张截图
     */
    private String captureScreenshot(WebDriver driver, String appName, String type, String description, String fileId) {
        try {
            
            Thread.sleep(500);

            
            TakesScreenshot takesScreenshot = (TakesScreenshot) driver;
            byte[] screenshotBytes = takesScreenshot.getScreenshotAs(OutputType.BYTES);

            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            
            String uuidShort = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String fileName = String.format("%s_%s_%s_%s.png",
                fileId != null ? fileId : appName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_"),
                type,
                timestamp,
                uuidShort);

            
            Path screenshotPath = Paths.get(SCREENSHOT_DIR, fileName);
            Files.write(screenshotPath, screenshotBytes);

            log.info("成功捕获截图: {} - {} (fileId: {})", description, fileName, fileId);
            return fileName;

        } catch (Exception e) {
            log.error("捕获截图失败: {}", description, e);
            return null;
        }
    }

    /**
     * 生成不同界面状态的截图
     */
    private List<String> generateInterfaceStates(WebDriver driver, String appName) {
        List<String> screenshots = new ArrayList<>();

        try {
            
            JavascriptExecutor js = (JavascriptExecutor) driver;

            
            List<String> selectors = List.of(
                "a[href*='user'], a[href*='管理'], .nav-link:contains('用户')",
                "a[href*='data'], a[href*='分析'], .nav-link:contains('数据')",
                "a[href*='setting'], a[href*='设置'], .nav-link:contains('设置')",
                "a[href*='message'], a[href*='消息'], .nav-link:contains('消息')"
            );

            String[] interfaceTypes = {"user_management", "data_analytics", "system_settings", "message_center"};
            String[] descriptions = {"用户管理界面", "数据分析界面", "系统设置界面", "消息中心界面"};

            for (int i = 0; i < selectors.size() && i < interfaceTypes.length; i++) {
                try {
                    
                    String script = String.format("""
                        var elements = document.querySelectorAll('%s');
                        if (elements.length > 0) {
                            elements[0].click();
                            return true;
                        }
                        return false;
                        """, selectors.get(i).split(",")[0]); 

                    Boolean clicked = (Boolean) js.executeScript(script);

                    if (Boolean.TRUE.equals(clicked)) {
                        
                        Thread.sleep(800);

                        
                        String screenshot = captureScreenshot(driver, appName, interfaceTypes[i], descriptions[i]);
                        if (screenshot != null) {
                            screenshots.add(screenshot);
                        }
                    }

                } catch (Exception e) {
                    log.debug("尝试切换到{}失败: {}", descriptions[i], e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("生成不同界面状态截图时出现异常", e);
        }

        return screenshots;
    }

    /**
     * 通过滚动页面生成更多截图
     */
    private List<String> captureScrollScreenshots(WebDriver driver, String appName) {
        List<String> screenshots = new ArrayList<>();

        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;

            
            Long totalHeight = (Long) js.executeScript("return document.body.scrollHeight");
            int viewportHeight = DEFAULT_HEIGHT;

            if (totalHeight > viewportHeight) {
                
                js.executeScript("window.scrollTo(0, arguments[0]);", totalHeight / 2);
                Thread.sleep(1000);
                String midScreenshot = captureScreenshot(driver, appName, "middle", "页面中部");
                if (midScreenshot != null) {
                    screenshots.add(midScreenshot);
                }

                
                js.executeScript("window.scrollTo(0, arguments[0]);", totalHeight);
                Thread.sleep(1000);
                String bottomScreenshot = captureScreenshot(driver, appName, "bottom", "页面底部");
                if (bottomScreenshot != null) {
                    screenshots.add(bottomScreenshot);
                }

                
                js.executeScript("window.scrollTo(0, 0);");
                Thread.sleep(500);
            }

        } catch (Exception e) {
            log.warn("生成滚动截图时出现异常", e);
        }

        return screenshots;
    }

    /**
     * 从文件名提取界面类型
     */
    private String extractInterfaceTypeFromFileName(String fileName) {
        if (fileName.contains("user_management")) return "用户管理界面";
        if (fileName.contains("data_analytics")) return "数据分析界面";
        if (fileName.contains("system_settings")) return "系统设置界面";
        if (fileName.contains("message_center")) return "消息中心界面";
        if (fileName.contains("middle")) return "页面中部视图";
        if (fileName.contains("bottom")) return "页面底部视图";
        if (fileName.contains("main")) return "主界面";
        return "系统界面";
    }

    /**
     * 创建截图信息
     */
    private Map<String, String> createScreenshotInfo(String title, String fileName) {
        Map<String, String> info = new HashMap<>();
        info.put("title", title);
        info.put("fileName", fileName);
        return info;
    }

    /**
     * 保存截图到数据库
     */
    private void saveScreenshotToDatabase(String projectId, String functionName, String fileName, File file) throws IOException {
        log.info("开始保存截图到数据库: projectId={}, functionName={}, fileName={}, fileExists={}", 
                 projectId, functionName, fileName, file.exists());
        
        if (copyrightFileService == null) {
            log.error("copyrightFileService 为 null，无法保存截图到数据库");
            throw new RuntimeException("copyrightFileService 未正确注入");
        }
        
        if (!file.exists()) {
            log.error("截图文件不存在: {}", file.getAbsolutePath());
            throw new IOException("截图文件不存在: " + file.getAbsolutePath());
        }
        
        
        byte[] imageBytes = Files.readAllBytes(file.toPath());
        log.info("读取截图文件成功，文件大小: {} bytes", imageBytes.length);
        
        String base64Data = "data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes);
        log.info("base64转换完成，数据长度: {} 字符", base64Data.length());
        
        
        CopyrightFile screenshotFile = new CopyrightFile();
        screenshotFile.setProjectId(projectId);
        screenshotFile.setFileName(fileName);
        screenshotFile.setTitle(functionName);
        screenshotFile.setFileType("screenshot");
        screenshotFile.setContent(base64Data);
        screenshotFile.setStatus("completed");
        screenshotFile.setCreateTime(new java.util.Date());
        screenshotFile.setUpdateTime(new java.util.Date());
        
        log.info("开始保存CopyrightFile到数据库...");
        
        
        copyrightFileService.save(screenshotFile);
        
        log.info("截图已成功保存到数据库: {} ({})", functionName, fileName);
    }

    /**
     * 发送进度信息
     */
    private void sendProgress(SseEmitter emitter, int progress, String message) {
        
        if (emitter == null) {
            log.debug("SSE emitter为null，跳过发送进度: {}% - {}", progress, message);
            return;
        }
        
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("progress", progress);
            data.put("message", message);
            emitter.send(SseEmitter.event().name("progress").data(data));
        } catch (IOException e) {
            log.error("发送进度信息失败", e);
        }
    }

    /**
     * 发送错误信息
     */
    private void sendError(SseEmitter emitter, String errorMessage) {
        
        if (emitter == null) {
            log.debug("SSE emitter为null，跳过发送错误信息: {}", errorMessage);
            return;
        }
        
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("error", errorMessage);
            emitter.send(SseEmitter.event().name("error").data(data));
            emitter.complete();
        } catch (IOException e) {
            log.error("发送错误信息失败", e);
        }
    }

    /**
     * 🔥 自动替换CDN为国内镜像 - 解决Docker容器访问国外CDN超时问题
     * 
     * ⚠️ 临时注释：已在AI提示词中直接使用cdnjs.cloudflare.com（已验证可访问），无需二次替换
     * 
     * 支持的CDN替换：
     * - cdn.jsdelivr.net → unpkg.com (国际CDN，国内访问较快)
     * - cdnjs.cloudflare.com → unpkg.com
     * - ajax.googleapis.com → unpkg.com
     * 
     * 支持的库：Bootstrap, Font Awesome, jQuery, Vue, React等
     */
    /* 临时注释整个方法
    private String replaceCdnUrls(String code) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        
        try {
            String replacedCode = code;
            int replacementCount = 0;
            
            
            // Bootstrap
            if (replacedCode.contains("cdn.jsdelivr.net/npm/bootstrap")) {
                replacedCode = replacedCode.replaceAll(
                    "https://cdn\\.jsdelivr\\.net/npm/bootstrap@([0-9.]+)/dist/",
                    "https://unpkg.com/bootstrap@$1/dist/"
                );
                replacementCount++;
                log.info("替换Bootstrap CDN: jsdelivr.net → unpkg.com");
            }
            
            // Font Awesome
            if (replacedCode.contains("cdnjs.cloudflare.com/ajax/libs/font-awesome")) {
                replacedCode = replacedCode.replaceAll(
                    "https://cdnjs\\.cloudflare\\.com/ajax/libs/font-awesome/([0-9.]+)/",
                    "https://unpkg.com/@fortawesome/fontawesome-free@$1/"
                );
                replacementCount++;
                log.info("替换Font Awesome CDN: cdnjs → unpkg.com");
            }
            
            if (replacedCode.contains("cdn.jsdelivr.net/npm/@fortawesome")) {
                // jsdelivr已经可用，但统一使用unpkg
                replacedCode = replacedCode.replaceAll(
                    "https://cdn\\.jsdelivr\\.net/npm/@fortawesome/fontawesome-free@([0-9.]+)/",
                    "https://unpkg.com/@fortawesome/fontawesome-free@$1/"
                );
                replacementCount++;
                log.info("替换Font Awesome CDN: jsdelivr.net → unpkg.com");
            }
            
            // jQuery
            if (replacedCode.contains("cdn.jsdelivr.net/npm/jquery")) {
                replacedCode = replacedCode.replaceAll(
                    "https://cdn\\.jsdelivr\\.net/npm/jquery@([0-9.]+)/",
                    "https://unpkg.com/jquery@$1/"
                );
                replacementCount++;
                log.info("替换jQuery CDN: jsdelivr.net → unpkg.com");
            }
            
            if (replacedCode.contains("ajax.googleapis.com/ajax/libs/jquery")) {
                replacedCode = replacedCode.replaceAll(
                    "https://ajax\\.googleapis\\.com/ajax/libs/jquery/([0-9.]+)/",
                    "https://unpkg.com/jquery@$1/dist/"
                );
                replacementCount++;
                log.info("替换jQuery CDN: googleapis.com → unpkg.com");
            }
            
            // Vue.js
            if (replacedCode.contains("cdn.jsdelivr.net/npm/vue")) {
                replacedCode = replacedCode.replaceAll(
                    "https://cdn\\.jsdelivr\\.net/npm/vue@([0-9.]+)/",
                    "https://unpkg.com/vue@$1/"
                );
                replacementCount++;
                log.info("替换Vue CDN: jsdelivr.net → unpkg.com");
            }
            
            // React
            if (replacedCode.contains("cdn.jsdelivr.net/npm/react")) {
                replacedCode = replacedCode.replaceAll(
                    "https://cdn\\.jsdelivr\\.net/npm/react@([0-9.]+)/",
                    "https://unpkg.com/react@$1/"
                );
                replacementCount++;
                log.info("替换React CDN: jsdelivr.net → unpkg.com");
            }
            
            // Lodash
            if (replacedCode.contains("cdnjs.cloudflare.com/ajax/libs/lodash")) {
                replacedCode = replacedCode.replaceAll(
                    "https://cdnjs\\.cloudflare\\.com/ajax/libs/lodash\\.js/([0-9.]+)/",
                    "https://unpkg.com/lodash@$1/"
                );
                replacementCount++;
                log.info("替换Lodash CDN: cdnjs → unpkg.com");
            }
            
            // Axios
            if (replacedCode.contains("cdn.jsdelivr.net/npm/axios")) {
                replacedCode = replacedCode.replaceAll(
                    "https://cdn\\.jsdelivr\\.net/npm/axios@([0-9.]+)/",
                    "https://unpkg.com/axios@$1/"
                );
                replacementCount++;
                log.info("替换Axios CDN: jsdelivr.net → unpkg.com");
            }
            
            // Element UI
            if (replacedCode.contains("unpkg.com/element-ui")) {
                // unpkg已经是好的选择，保持不变
                log.debug("Element UI已使用unpkg，无需替换");
            }
            
            if (replacementCount > 0) {
                log.info("✅ CDN替换完成，共替换 {} 个CDN链接", replacementCount);
            } else {
                log.debug("未检测到需要替换的CDN链接");
            }
            
            return replacedCode;
            
        } catch (Exception e) {
            log.error("CDN替换过程中出现异常，返回原始代码", e);
            return code;
        }
    }
    */ 
    
    /**
     * 增强代码依赖
     */
    private String enhanceWithDependencies(String code, String appName) {
        
        if (code.toLowerCase().contains("bootstrap")) {
            return code;
        }

        
        String headInsert = """
            <link href="https://cdnjs.cloudflare.com/ajax/libs/bootstrap/5.3.0/css/bootstrap.min.css" rel="stylesheet">
            <link href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css" rel="stylesheet">
            """;

        code = code.replaceFirst("</head>", headInsert + "</head>");

        
        String bodyInsert = """
            <script src="https://cdnjs.cloudflare.com/ajax/libs/bootstrap/5.3.0/js/bootstrap.bundle.min.js"></script>
            """;

        code = code.replaceFirst("</body>", bodyInsert + "</body>");

        return code;
    }

    /**
     * 最终代码清理
     */
    private String finalizeCode(String code) {
        
        code = code.replaceAll("\\n\\s*\\n\\s*\\n", "\n\n");

        
        if (!code.endsWith("\n")) {
            code += "\n";
        }

        return code;
    }

    /**
     * 向head中添加charset
     */
    private String addCharsetToHead(String code) {
        String charsetMeta = "<meta charset=\"UTF-8\">";
        return code.replaceFirst("<head>", "<head>\n    " + charsetMeta);
    }

    /**
     * 向head中添加viewport
     */
    private String addViewportToHead(String code) {
        String viewportMeta = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">";
        return code.replaceFirst("</head>", "    " + viewportMeta + "\n</head>");
    }

    /**
     * HTML转义
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#39;");
    }



    /**
     * 直接截图方法 - 类似Python版本的简化实现
     */
    public Map<String, Object> captureScreenshotDirect(String htmlContent, String tabName, String appName) {
        WebDriver driver = null;
        String tempFilePath = null;
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("开始直接截图: appName={}, tabName={}", appName, tabName);

            
            String processedHtml = preprocessCodeSimple(htmlContent, appName);

            
            tempFilePath = createTempHtmlFile(appName, processedHtml);

            
            if (!checkChromeAvailability()) {
                result.put("error", "Chrome浏览器或ChromeDriver不可用");
                return result;
            }

            
            driver = initializeWebDriver();

            
            File tempFile = new File(tempFilePath);
            String fileUrl = tempFile.toURI().toString();
            log.info("测试流程加载HTML文件URL: {}", fileUrl);
            driver.get(fileUrl);

            
            waitForPageLoad(driver);

            
            TakesScreenshot takesScreenshot = (TakesScreenshot) driver;
            byte[] screenshotBytes = takesScreenshot.getScreenshotAs(OutputType.BYTES);

            
            Path screenshotDir = Paths.get(SCREENSHOT_DIR);
            if (!Files.exists(screenshotDir)) {
                Files.createDirectories(screenshotDir);
            }

            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            String fileName = String.format("%s_screenshot_%s_%s.png",
                appName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_"),
                tabName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_"),
                timestamp);

            
            Path screenshotPath = Paths.get(SCREENSHOT_DIR, fileName);
            Files.write(screenshotPath, screenshotBytes);

            
            String base64Screenshot = Base64.getEncoder().encodeToString(screenshotBytes);

            
            result.put("success", true);
            result.put("screenshotUrl", "/screenshots/" + fileName);
            result.put("fileName", fileName);
            result.put("dataUrl", "data:image/png;base64," + base64Screenshot);

            log.info("直接截图成功: {}", fileName);

        } catch (Exception e) {
            log.error("直接截图失败", e);
            result.put("error", "截图失败: " + e.getMessage());
        } finally {
            
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    log.warn("关闭WebDriver失败", e);
                }
            }
            if (tempFilePath != null) {
                cleanupTempFile(tempFilePath);
            }
        }

        return result;
    }

    /**
     * 兼容性方法 - 为了向后兼容测试代码
     * @deprecated 使用 preprocessCodeSimple 替代
     */
    @Deprecated
    public String validateAndFixCode(String frontendCode, String appName) {
        log.warn("使用了已废弃的validateAndFixCode方法，建议使用preprocessCodeSimple");
        return preprocessCodeSimple(frontendCode, appName);
    }

    /**
     * 调用Puppeteer截图服务
     */
    private byte[] callPuppeteerScreenshotService(String html) {
        try {
            log.info("调用Puppeteer截图服务: {}", screenshotServiceUrl);

            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("html", html);

            Map<String, Object> options = new HashMap<>();
            options.put("width", 1280);
            options.put("height", 720);
            options.put("fullPage", true);
            requestBody.put("options", options);

            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            
            ResponseEntity<byte[]> response = restTemplate.postForEntity(
                screenshotServiceUrl + "/screenshot",
                request,
                byte[].class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("Puppeteer截图服务调用成功，截图大小: {} bytes", response.getBody().length);
                return response.getBody();
            } else {
                log.warn("Puppeteer截图服务返回异常状态: {}", response.getStatusCode());
                return null;
            }

        } catch (Exception e) {
            log.error("调用Puppeteer截图服务失败", e);
            throw new RuntimeException("Puppeteer截图服务调用失败: " + e.getMessage());
        }
    }

    /**
     * 保存截图文件
     */
    private String saveScreenshotFile(String appName, byte[] screenshotData) {
        try {
            
            File screenshotDir = new File(SCREENSHOT_DIR);
            if (!screenshotDir.exists()) {
                screenshotDir.mkdirs();
            }

            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = appName + "_screenshot_" + timestamp + ".png";
            String filePath = SCREENSHOT_DIR + File.separator + fileName;

            
            Files.write(Paths.get(filePath), screenshotData);

            log.info("截图文件保存成功: {}", filePath);
            return fileName;

        } catch (Exception e) {
            log.error("保存截图文件失败", e);
            throw new RuntimeException("保存截图文件失败: " + e.getMessage());
        }
    }

    /**
     * 生成降级截图（当所有方案都失败时）
     */
    private List<Map<String, String>> generateFallbackScreenshots(String appName, String html) {
        List<Map<String, String>> screenshots = new ArrayList<>();

        try {
            
            String simplifiedHtml = generateSimplifiedHtml(appName, html);

            
            

            Map<String, String> screenshot = new HashMap<>();
            screenshot.put("title", appName + " - 界面预览");
            screenshot.put("fileName", "fallback_" + appName + "_" + System.currentTimeMillis() + ".txt");
            screenshot.put("description", "代码预览（降级模式）");
            screenshot.put("content", simplifiedHtml.substring(0, Math.min(500, simplifiedHtml.length())));
            screenshots.add(screenshot);

        } catch (Exception e) {
            log.error("生成降级截图失败", e);

            
            Map<String, String> screenshot = new HashMap<>();
            screenshot.put("title", appName + " - 系统界面");
            screenshot.put("fileName", "error_" + System.currentTimeMillis() + ".txt");
            screenshot.put("description", "截图生成失败，请检查系统环境");
            screenshot.put("error", "所有截图方案都失败了");
            screenshots.add(screenshot);
        }

        return screenshots;
    }

    /**
     * 生成简化的HTML（用于降级方案）
     */
    private String generateSimplifiedHtml(String appName, String originalHtml) {
        
        String simplified = originalHtml
            .replaceAll("<script[^>]*>.*?</script>", "")
            .replaceAll("<style[^>]*>.*?</style>", "")
            .replaceAll("style=\"[^\"]*\"", "");

        return String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <title>%s - 界面预览</title>
                <style>
                    body { font-family: Arial, sans-serif; padding: 20px; }
                    .preview { border: 1px solid #ccc; padding: 15px; }
                </style>
            </head>
            <body>
                <h1>%s</h1>
                <div class="preview">
                    %s
                </div>
            </body>
            </html>
            """, appName, appName, simplified.length() > 1000 ? simplified.substring(0, 1000) + "..." : simplified);
    }

    /**
     * 清理临时文件
     */
    private void cleanupTempFile(String filePath) {
        try {
            if (filePath != null) {
                Files.deleteIfExists(Paths.get(filePath));
                log.info("清理临时文件: {}", filePath);
            }
        } catch (IOException e) {
            log.warn("清理临时文件失败: {}", filePath, e);
        }
    }

    /**
     * 测试截图功能 - 直接测试指定HTML文件
     */
    public Map<String, Object> testScreenshot(String htmlFilePath) {
        Map<String, Object> result = new HashMap<>();
        WebDriver driver = null;

        try {
            log.info("开始测试截图功能，HTML文件: {}", htmlFilePath);

            
            File htmlFile = new File(htmlFilePath);
            if (!htmlFile.exists()) {
                result.put("success", false);
                result.put("error", "HTML文件不存在: " + htmlFilePath);
                return result;
            }

            
            log.info("初始化Chrome浏览器...");
            driver = initializeWebDriver();

            
            String fileUrl = htmlFile.toURI().toString();
            log.info("加载HTML文件: {}", fileUrl);
            driver.get(fileUrl);

            
            log.info("等待页面加载...");
            waitForPageLoad(driver);

            
            String pageTitle = driver.getTitle();
            String pageSource = driver.getPageSource();
            log.info("页面标题: {}", pageTitle);
            log.info("页面源码长度: {}", pageSource.length());

            
            List<Map<String, String>> screenshots = new ArrayList<>();

            // 1. 生成初始页面截图
            log.info("生成初始页面截图...");
            String initialScreenshot = captureScreenshot(driver, "测试", "initial", "初始页面");
            if (initialScreenshot != null) {
                File file = new File(SCREENSHOT_DIR + "/" + initialScreenshot);
                long fileSize = file.exists() ? file.length() : 0;
                screenshots.add(Map.of(
                    "name", "初始页面",
                    "fileName", initialScreenshot,
                    "fileSize", String.valueOf(fileSize)
                ));
                log.info("初始页面截图: {}, 大小: {} bytes", initialScreenshot, fileSize);
            }



            // 2. 测试五功能切换截图
            log.info("开始测试五功能切换截图...");
            String[][] functions = {
                {"home", "首页", "home-btn", "home-content"},
                {"user", "用户管理", "user-btn", "user-content"},
                {"data", "数据分析", "data-btn", "data-content"},
                {"settings", "系统设置", "settings-btn", "settings-content"},
                {"message", "消息中心", "message-btn", "message-content"}
            };

            for (String[] function : functions) {
                String functionKey = function[0];
                String functionName = function[1];
                String buttonId = function[2];
                String contentId = function[3];

                try {
                    log.info("测试功能切换: {}", functionName);

                    
                    boolean switched = testSwitchToFunction(driver, buttonId, contentId, functionName);

                    if (switched) {
                        
                        Thread.sleep(6000); 

                        
                        waitForFunctionContentToLoad(driver, contentId, functionName);

                        
                        String screenshotFile = captureScreenshot(driver, "测试", functionKey, functionName);
                        if (screenshotFile != null) {
                            File file = new File(SCREENSHOT_DIR + "/" + screenshotFile);
                            long fileSize = file.exists() ? file.length() : 0;
                            screenshots.add(Map.of(
                                "name", functionName,
                                "fileName", screenshotFile,
                                "fileSize", String.valueOf(fileSize),
                                "switched", "true"
                            ));
                            log.info("{}截图成功: {}, 大小: {} bytes", functionName, screenshotFile, fileSize);
                        }
                    } else {
                        screenshots.add(Map.of(
                            "name", functionName,
                            "fileName", "切换失败",
                            "fileSize", "0",
                            "switched", "false"
                        ));
                        log.warn("{}功能切换失败", functionName);
                    }

                } catch (Exception e) {
                    log.error("测试{}功能时出错: {}", functionName, e.getMessage());
                    screenshots.add(Map.of(
                        "name", functionName,
                        "fileName", "异常: " + e.getMessage(),
                        "fileSize", "0",
                        "switched", "error"
                    ));
                }
            }

            result.put("success", true);
            result.put("pageTitle", pageTitle);
            result.put("pageSourceLength", pageSource.length());
            result.put("screenshots", screenshots);
            result.put("screenshotCount", screenshots.size());

            log.info("测试截图完成，生成{}张截图", screenshots.size());

        } catch (Exception e) {
            log.error("测试截图失败: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                    log.info("Chrome浏览器已关闭");
                } catch (Exception e) {
                    log.warn("关闭浏览器时出错: {}", e.getMessage());
                }
            }
        }

        return result;
    }

    /**
     * 提取纯HTML代码，移除中文说明和代码块标记
     */
    private String extractPureHtmlCode(String code) {
        try {
            log.info("开始提取纯HTML代码，原始长度: {}", code.length());

            // 1. 移除代码块标记
            String cleaned = code;

            
            cleaned = cleaned.replaceAll("```html\\s*", "");
            cleaned = cleaned.replaceAll("```\\s*$", "");
            cleaned = cleaned.replaceAll("```", "");

            // 2. 查找HTML开始和结束标记
            int htmlStart = -1;
            int htmlEnd = -1;

            
            String lowerCleaned = cleaned.toLowerCase();
            int doctypePos = lowerCleaned.indexOf("<!doctype html>");
            int htmlTagPos = lowerCleaned.indexOf("<html");

            if (doctypePos != -1) {
                htmlStart = doctypePos;
            } else if (htmlTagPos != -1) {
                htmlStart = htmlTagPos;
            }

            
            int htmlEndPos = lowerCleaned.lastIndexOf("</html>");
            if (htmlEndPos != -1) {
                htmlEnd = htmlEndPos + "</html>".length();
            }

            // 3. 提取HTML代码
            if (htmlStart != -1 && htmlEnd != -1 && htmlStart < htmlEnd) {
                String extractedHtml = cleaned.substring(htmlStart, htmlEnd);
                log.info("成功提取HTML代码，提取长度: {}", extractedHtml.length());
                return extractedHtml.trim();
            }

            // 4. 如果没有找到完整的HTML结构，尝试其他方法
            log.warn("未找到完整HTML结构，尝试其他提取方法");

            
            String[] lines = cleaned.split("\\r?\\n");
            StringBuilder htmlBuilder = new StringBuilder();
            boolean inHtml = false;

            for (String line : lines) {
                String trimmedLine = line.trim();

                
                if (trimmedLine.matches(".*[\\u4e00-\\u9fa5].*") &&
                    !trimmedLine.contains("<") &&
                    !trimmedLine.contains(">")) {
                    continue;
                }

                
                if (trimmedLine.toLowerCase().contains("<!doctype") ||
                    trimmedLine.toLowerCase().contains("<html")) {
                    inHtml = true;
                }

                if (inHtml) {
                    htmlBuilder.append(line).append("\n");
                }

                
                if (trimmedLine.toLowerCase().contains("</html>")) {
                    break;
                }
            }

            String result = htmlBuilder.toString().trim();
            if (result.length() > 100) { 
                log.info("通过行过滤提取HTML代码，长度: {}", result.length());
                return result;
            }

            // 5. 最后的降级方案：返回原始代码
            log.warn("HTML提取失败，返回原始代码");
            return cleaned.trim();

        } catch (Exception e) {
            log.error("HTML代码提取失败: {}", e.getMessage());
            return code; 
        }
    }

    /**
     * 测试功能切换
     */
    private boolean testSwitchToFunction(WebDriver driver, String buttonId, String contentId, String functionName) {
        try {
            log.info("尝试切换到功能: {}, 按钮ID: {}, 内容ID: {}", functionName, buttonId, contentId);

            
            WebElement button = findButtonByMultipleMethods(driver, buttonId, functionName);

            if (button != null) {
                
                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", button);
                Thread.sleep(500);

                
                button.click();
                log.info("成功点击{}按钮", functionName);

                
                Thread.sleep(2000);

                
                boolean contentVisible = verifyContentVisible(driver, contentId, functionName);
                log.info("{}内容可见性验证: {}", functionName, contentVisible);

                return true; 
            } else {
                log.warn("未找到{}功能的按钮", functionName);
                return false;
            }

        } catch (Exception e) {
            log.error("切换到{}功能失败: {}", functionName, e.getMessage());
            return false;
        }
    }

    /**
     * 通过多种方式查找按钮
     */
    private WebElement findButtonByMultipleMethods(WebDriver driver, String buttonId, String functionName) {
        WebElement button = null;

        
        try {
            button = driver.findElement(By.id(buttonId));
            log.info("通过ID找到{}按钮: {}", functionName, buttonId);
            return button;
        } catch (Exception e) {
            log.debug("通过ID未找到{}按钮: {}", functionName, buttonId);
        }

        
        try {
            button = driver.findElement(By.className(buttonId));
            log.info("通过class找到{}按钮: {}", functionName, buttonId);
            return button;
        } catch (Exception e) {
            log.debug("通过class未找到{}按钮: {}", functionName, buttonId);
        }

        
        try {
            String tabName = buttonId.replace("-btn", "");
            button = driver.findElement(By.cssSelector("[data-tab='" + tabName + "']"));
            log.info("通过data-tab找到{}按钮: {}", functionName, tabName);
            return button;
        } catch (Exception e) {
            log.debug("通过data-tab未找到{}按钮: {}", functionName, buttonId);
        }

        
        try {
            button = driver.findElement(By.xpath("//button[contains(text(), '" + functionName + "')]"));
            log.info("通过文本找到{}按钮", functionName);
            return button;
        } catch (Exception e) {
            log.debug("通过文本未找到{}按钮: {}", functionName, functionName);
        }

        
        try {
            String[] keywords = {"首页", "用户", "数据", "设置", "消息"};
            for (String keyword : keywords) {
                if (functionName.contains(keyword)) {
                    button = driver.findElement(By.xpath("//button[contains(text(), '" + keyword + "')]"));
                    log.info("通过关键词'{}'找到{}按钮", keyword, functionName);
                    return button;
                }
            }
        } catch (Exception e) {
            log.debug("通过关键词未找到{}按钮", functionName);
        }

        return null;
    }

    /**
     * 验证内容区域是否可见
     */
    private boolean verifyContentVisible(WebDriver driver, String contentId, String functionName) {
        try {
            
            WebElement content = null;

            try {
                content = driver.findElement(By.id(contentId));
                log.info("通过ID找到{}内容区域: {}", functionName, contentId);
            } catch (Exception e) {
                try {
                    content = driver.findElement(By.className(contentId));
                    log.info("通过class找到{}内容区域: {}", functionName, contentId);
                } catch (Exception e2) {
                    log.debug("未找到{}内容区域: {}", functionName, contentId);
                    return false;
                }
            }

            if (content != null && content.isDisplayed()) {
                
                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", content);
                Thread.sleep(500);
                log.info("{}内容区域已可见", functionName);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.debug("验证{}内容可见性时出错: {}", functionName, e.getMessage());
            return false;
        }
    }

    /**
     * 测试实战截图流程 - 模拟AI代码生成+截图的完整流程
     */
    public Map<String, Object> testRealScreenshotFlow(String appName, String aiGeneratedCode) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, String>> screenshots = new ArrayList<>();
        WebDriver driver = null;
        String tempFilePath = null;

        try {
            log.info("开始测试实战截图流程: appName={}", appName);

            // 1. 代码预处理（模拟实战中的预处理步骤）
            log.info("步骤1: 预处理AI生成的代码...");
            String processedCode = preprocessCodeSimple(aiGeneratedCode, appName);
            log.info("代码预处理完成，处理后长度: {}", processedCode.length());

            // 2. 创建临时HTML文件（模拟实战中的文件创建）
            log.info("步骤2: 创建临时HTML文件...");
            tempFilePath = createTempHtmlFile(appName, processedCode);
            log.info("临时HTML文件创建成功: {}", tempFilePath);

            // 3. 检查Chrome可用性
            log.info("步骤3: 检查Chrome可用性...");
            if (!checkChromeAvailability()) {
                result.put("success", false);
                result.put("error", "Chrome浏览器或ChromeDriver不可用");
                return result;
            }
            log.info("Chrome可用性检查通过");

            // 4. 初始化WebDriver
            log.info("步骤4: 初始化Chrome浏览器...");
            driver = initializeWebDriver();
            log.info("Chrome浏览器初始化成功");

            // 5. 加载HTML文件
            log.info("步骤5: 加载HTML文件...");
            File tempFile = new File(tempFilePath);
            String fileUrl = tempFile.toURI().toString();
            driver.get(fileUrl);
            log.info("HTML文件加载成功: {}", fileUrl);

            // 6. 等待页面加载
            log.info("步骤6: 等待页面加载...");
            waitForPageLoad(driver);

            
            String pageTitle = driver.getTitle();
            String pageSource = driver.getPageSource();
            log.info("页面标题: {}", pageTitle);
            log.info("页面源码长度: {}", pageSource.length());

            // 7. 生成初始页面截图
            log.info("步骤7: 生成初始页面截图...");
            String initialScreenshot = captureScreenshot(driver, appName, "initial", "初始页面");
            if (initialScreenshot != null) {
                File file = new File(SCREENSHOT_DIR + "/" + initialScreenshot);
                long fileSize = file.exists() ? file.length() : 0;
                screenshots.add(Map.of(
                    "name", "初始页面",
                    "fileName", initialScreenshot,
                    "fileSize", String.valueOf(fileSize),
                    "switched", "initial"
                ));
                log.info("初始页面截图: {}, 大小: {} bytes", initialScreenshot, fileSize);
            }

            // 8. 测试五功能切换截图（与testScreenshot相同的逻辑）
            log.info("步骤8: 开始测试五功能切换截图...");
            String[][] functions = {
                {"home", "首页", "home-btn", "home-content"},
                {"user", "用户管理", "user-btn", "user-content"},
                {"data", "数据分析", "data-btn", "data-content"},
                {"settings", "系统设置", "settings-btn", "settings-content"},
                {"message", "消息中心", "message-btn", "message-content"}
            };

            for (String[] function : functions) {
                String functionKey = function[0];
                String functionName = function[1];
                String buttonId = function[2];
                String contentId = function[3];

                try {
                    log.info("测试功能切换: {}", functionName);

                    
                    boolean switched = testSwitchToFunction(driver, buttonId, contentId, functionName);

                    if (switched) {
                        
                        log.info("等待{}功能内容切换和渲染...", functionName);
                        Thread.sleep(15000); 

                        
                        waitForFunctionContentToLoad(driver, contentId, functionName);

                        
                        verifyContentDisplayed(driver, contentId, functionName);
                        
                        
                        String screenshotFile = captureScreenshot(driver, appName, functionKey, functionName);
                        if (screenshotFile != null) {
                            File file = new File(SCREENSHOT_DIR + "/" + screenshotFile);
                            long fileSize = file.exists() ? file.length() : 0;
                            screenshots.add(Map.of(
                                "name", functionName,
                                "fileName", screenshotFile,
                                "fileSize", String.valueOf(fileSize),
                                "switched", "true"
                            ));
                            log.info("{}截图成功: {}, 大小: {} bytes", functionName, screenshotFile, fileSize);
                        }
                    } else {
                        screenshots.add(Map.of(
                            "name", functionName,
                            "fileName", "切换失败",
                            "fileSize", "0",
                            "switched", "false"
                        ));
                        log.warn("{}功能切换失败", functionName);
                    }

                } catch (Exception e) {
                    log.error("测试{}功能时出错: {}", functionName, e.getMessage());
                    screenshots.add(Map.of(
                        "name", functionName,
                        "fileName", "异常: " + e.getMessage(),
                        "fileSize", "0",
                        "switched", "error"
                    ));
                }
            }

            result.put("success", true);
            result.put("appName", appName);
            result.put("pageTitle", pageTitle);
            result.put("pageSourceLength", pageSource.length());
            result.put("tempFilePath", tempFilePath);
            result.put("screenshots", screenshots);
            result.put("screenshotCount", screenshots.size());
            result.put("processedCodeLength", processedCode.length());

            log.info("实战截图流程测试完成，生成{}张截图", screenshots.size());

        } catch (Exception e) {
            log.error("实战截图流程测试失败: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                    log.info("Chrome浏览器已关闭");
                } catch (Exception e) {
                    log.warn("关闭浏览器时出错: {}", e.getMessage());
                }
            }
            
            if (tempFilePath != null) {
                log.info("临时文件保留用于调试: {}", tempFilePath);
            }
        }

        return result;
    }

    /**
     * 调试代码预处理（公开方法供Controller调用）
     */
    public String debugPreprocessCode(String code, String appName) {
        return preprocessCodeSimple(code, appName);
    }

    /**
     * 等待JavaScript执行完成
     */
    private void waitForJavaScriptToLoad(WebDriver driver) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

            
            wait.until(webDriver -> {
                try {
                    JavascriptExecutor js = (JavascriptExecutor) webDriver;
                    Object jqueryReady = js.executeScript("return typeof jQuery !== 'undefined' ? jQuery.active == 0 : true");
                    return Boolean.TRUE.equals(jqueryReady);
                } catch (Exception e) {
                    return true; 
                }
            });

            
            wait.until(webDriver -> {
                JavascriptExecutor js = (JavascriptExecutor) webDriver;
                String readyState = (String) js.executeScript("return document.readyState");
                return "complete".equals(readyState);
            });

            log.info("JavaScript执行完成检查通过");
        } catch (Exception e) {
            log.warn("等待JavaScript执行完成时出现异常: {}", e.getMessage());
        }
    }

    /**
     * 等待CSS样式加载完成
     */
    private void waitForCSSToLoad(WebDriver driver) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(25)); 

            
            wait.until(webDriver -> {
                try {
                    JavascriptExecutor js = (JavascriptExecutor) webDriver;
                    Long stylesheetCount = (Long) js.executeScript(
                        "return document.styleSheets.length"
                    );
                    return stylesheetCount > 0;
                } catch (Exception e) {
                    return true;
                }
            });

            
            wait.until(webDriver -> {
                try {
                    JavascriptExecutor js = (JavascriptExecutor) webDriver;
                    
                    Object result = js.executeScript(
                        "var element = document.querySelector('.navbar, .card, .btn');" +
                        "if (element) {" +
                        "  var style = window.getComputedStyle(element);" +
                        "  return style.backgroundColor !== 'rgba(0, 0, 0, 0)' || style.color !== 'rgba(0, 0, 0, 0)';" +
                        "}" +
                        "return true;"
                    );
                    return Boolean.TRUE.equals(result);
                } catch (Exception e) {
                    return true;
                }
            });

            
            Thread.sleep(6000);
            log.info("CSS样式加载完成检查通过");
        } catch (Exception e) {
            log.warn("等待CSS样式加载时出现异常: {}", e.getMessage());
        }
    }

    /**
     * 等待Bootstrap组件加载完成
     */
    private void waitForBootstrapComponents(WebDriver driver) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30)); 

            
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.className("container")));
                log.info("Bootstrap容器已加载");
            } catch (Exception e) {
                log.warn("未找到Bootstrap容器: {}", e.getMessage());
            }

            
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.className("navbar")));
                log.info("Bootstrap导航栏已加载");
            } catch (Exception e) {
                log.warn("未找到Bootstrap导航栏: {}", e.getMessage());
            }

            
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.className("btn")));
                log.info("Bootstrap按钮组件已加载");
            } catch (Exception e) {
                log.warn("未找到Bootstrap按钮组件: {}", e.getMessage());
            }

            
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.className("card")));
                log.info("Bootstrap卡片组件已加载");
            } catch (Exception e) {
                log.warn("未找到Bootstrap卡片组件: {}", e.getMessage());
            }

            
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.className("table")));
                log.info("Bootstrap表格组件已加载");
            } catch (Exception e) {
                log.warn("未找到Bootstrap表格组件: {}", e.getMessage());
            }

            
            Thread.sleep(5000);
            log.info("Bootstrap组件加载完成");

        } catch (Exception e) {
            log.warn("等待Bootstrap组件时出现异常: {}", e.getMessage());
        }
    }

    /**
     * 等待动态内容生成
     */
    private void waitForDynamicContent(WebDriver driver) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));

            
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".content, .main-content, #content, #main")));
                log.info("主要内容区域已加载");
            } catch (Exception e) {
                log.warn("未找到主要内容区域: {}", e.getMessage());
            }

            
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("table, .table, ul, ol, .list")));
                log.info("表格或列表内容已加载");
            } catch (Exception e) {
                log.warn("未找到表格或列表内容: {}", e.getMessage());
            }

            
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".card, .panel, .widget")));
                log.info("卡片或面板内容已加载");
            } catch (Exception e) {
                log.warn("未找到卡片或面板内容: {}", e.getMessage());
            }

            
            Thread.sleep(3000);
            log.info("动态内容生成等待完成");

        } catch (Exception e) {
            log.warn("等待动态内容生成时出现异常: {}", e.getMessage());
        }
    }

    /**
     * 验证页面内容完整性
     */
    private void validatePageContent(WebDriver driver) {
        try {
            
            String pageTitle = driver.getTitle();
            log.info("页面标题: {}", pageTitle);

            
            WebElement body = driver.findElement(By.tagName("body"));
            String bodyText = body.getText();
            log.info("页面文本内容长度: {} 字符", bodyText.length());

            if (bodyText.length() < 50) {
                log.warn("页面内容较少，可能存在加载问题");
            } else {
                log.info("页面内容验证通过");
            }

            
            try {
                List<WebElement> errorElements = driver.findElements(By.cssSelector(".error, .alert-danger, .text-danger"));
                if (!errorElements.isEmpty()) {
                    log.warn("页面中发现错误信息元素");
                }
            } catch (Exception e) {
                
            }

            
            try {
                JavascriptExecutor js = (JavascriptExecutor) driver;
                Long imageCount = (Long) js.executeScript(
                    "return document.images.length"
                );
                Long loadedImages = (Long) js.executeScript(
                    "return Array.from(document.images).filter(img => img.complete && img.naturalHeight !== 0).length"
                );
                log.info("图片加载状态: {}/{}", loadedImages, imageCount);
            } catch (Exception e) {
                log.warn("检查图片加载状态时出现异常: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.warn("验证页面内容时出现异常: {}", e.getMessage());
        }
    }

    /**
     * 调整页面显示设置
     */
    private void adjustPageDisplay(WebDriver driver) {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;

            
            js.executeScript("window.scrollTo(0, 0);");
            Thread.sleep(1000);

            
            Long pageHeight = (Long) js.executeScript("return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);");
            log.info("页面实际高度: {}px", pageHeight);

            
            if (pageHeight > 1080) {
                int newHeight = Math.min(pageHeight.intValue() + 100, 3000); 
                driver.manage().window().setSize(new Dimension(1920, newHeight));
                log.info("调整窗口高度为: {}px", newHeight);
                Thread.sleep(2000); 
            }

            
            js.executeScript("window.scrollTo(0, 0);");
            Thread.sleep(500);

            log.info("页面显示调整完成");

        } catch (Exception e) {
            log.warn("调整页面显示时出现异常: {}", e.getMessage());
        }
    }

    /**
     * 等待功能内容加载完成
     */
    private void waitForFunctionContentToLoad(WebDriver driver, String contentId, String functionName) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

            
            if (contentId != null && !contentId.isEmpty()) {
                try {
                    wait.until(ExpectedConditions.visibilityOfElementLocated(By.id(contentId)));
                    log.info("{}功能内容区域已可见", functionName);
                } catch (Exception e) {
                    log.warn("等待{}功能内容区域可见时超时: {}", functionName, e.getMessage());
                }
            }

            
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("table, .table, .card, .panel, .list-group, .form-group")));
                log.info("{}功能内部元素已加载", functionName);
            } catch (Exception e) {
                log.warn("等待{}功能内部元素加载时超时: {}", functionName, e.getMessage());
            }

            
            Thread.sleep(2000);
            log.info("{}功能内容加载等待完成", functionName);

        } catch (Exception e) {
            log.warn("等待{}功能内容加载时出现异常: {}", functionName, e.getMessage());
        }
    }

    /**
     * 验证内容是否正确显示
     */
    private void verifyContentDisplayed(WebDriver driver, String contentId, String functionName) {
        try {
            log.info("开始验证{}功能内容显示状态", functionName);
            
            WebElement contentElement = driver.findElement(By.id(contentId));
            
            
            boolean isDisplayed = contentElement.isDisplayed();
            log.info("{}功能内容区域可见性: {}", functionName, isDisplayed);
            
            
            String displayStyle = contentElement.getCssValue("display");
            log.info("{}功能内容区域display样式: {}", functionName, displayStyle);
            
            
            org.openqa.selenium.Dimension size = contentElement.getSize();
            log.info("{}功能内容区域尺寸: {}x{}", functionName, size.width, size.height);
            
            
            List<WebElement> childElements = contentElement.findElements(By.cssSelector(".card, .table, .list-group, .btn"));
            log.info("{}功能内容区域包含{}个子元素", functionName, childElements.size());
            
            
            switch (contentId) {
                case "home-content":
                    verifyHomeContent(driver);
                    break;
                case "user-content":
                    verifyUserContent(driver);
                    break;
                case "data-content":
                    verifyDataContent(driver);
                    break;
                case "settings-content":
                    verifySettingsContent(driver);
                    break;
                case "message-content":
                    verifyMessageContent(driver);
                    break;
            }
            
        } catch (Exception e) {
            log.warn("验证{}功能内容显示时出现异常: {}", functionName, e.getMessage());
        }
    }
    
    private void verifyHomeContent(WebDriver driver) {
        try {
            
            List<WebElement> statsCards = driver.findElements(By.cssSelector("#home-content .stats-number"));
            log.info("首页统计卡片数量: {}", statsCards.size());
            
            
            List<WebElement> activityTable = driver.findElements(By.cssSelector("#home-content .table"));
            log.info("首页活动表格数量: {}", activityTable.size());
        } catch (Exception e) {
            log.warn("验证首页内容时出现异常: {}", e.getMessage());
        }
    }
    
    private void verifyUserContent(WebDriver driver) {
        try {
            
            List<WebElement> userTable = driver.findElements(By.cssSelector("#user-content .table"));
            log.info("用户管理页面表格数量: {}", userTable.size());
            
            
            List<WebElement> permissions = driver.findElements(By.cssSelector("#user-content .form-check"));
            log.info("用户管理页面权限选项数量: {}", permissions.size());
        } catch (Exception e) {
            log.warn("验证用户管理内容时出现异常: {}", e.getMessage());
        }
    }
    
    private void verifyDataContent(WebDriver driver) {
        try {
            
            List<WebElement> statsNumbers = driver.findElements(By.cssSelector("#data-content .stats-number"));
            log.info("数据分析页面统计数字数量: {}", statsNumbers.size());
            
            
            List<WebElement> progressBars = driver.findElements(By.cssSelector("#data-content .progress"));
            log.info("数据分析页面进度条数量: {}", progressBars.size());
        } catch (Exception e) {
            log.warn("验证数据分析内容时出现异常: {}", e.getMessage());
        }
    }
    
    private void verifySettingsContent(WebDriver driver) {
        try {
            
            List<WebElement> rangeInputs = driver.findElements(By.cssSelector("#settings-content .form-range"));
            log.info("系统设置页面滑块数量: {}", rangeInputs.size());
            
            
            List<WebElement> deviceTable = driver.findElements(By.cssSelector("#settings-content .table"));
            log.info("系统设置页面设备表格数量: {}", deviceTable.size());
        } catch (Exception e) {
            log.warn("验证系统设置内容时出现异常: {}", e.getMessage());
        }
    }
    
    private void verifyMessageContent(WebDriver driver) {
        try {
            
            List<WebElement> messageCategories = driver.findElements(By.cssSelector("#message-content .list-group-item"));
            log.info("消息中心页面消息分类数量: {}", messageCategories.size());
            
            
            List<WebElement> badges = driver.findElements(By.cssSelector("#message-content .badge"));
            log.info("消息中心页面徽章数量: {}", badges.size());
        } catch (Exception e) {
            log.warn("验证消息中心内容时出现异常: {}", e.getMessage());
        }
    }

    /**
     * 等待外部CDN资源加载完成 - 新增方法
     */
    private void waitForExternalResources(WebDriver driver) {
        try {
            log.info("开始等待外部CDN资源加载...");
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
            JavascriptExecutor js = (JavascriptExecutor) driver;

            
            log.info("等待Bootstrap CSS加载...");
            wait.until(webDriver -> {
                try {
                    
                    Object result = js.executeScript(
                        "return window.getComputedStyle && " +
                        "window.getComputedStyle(document.body).getPropertyValue('--bs-blue') !== '' || " +
                        "document.querySelector('link[href*=\"bootstrap\"]') !== null"
                    );
                    return Boolean.TRUE.equals(result);
                } catch (Exception e) {
                    return false;
                }
            });

            
            log.info("等待Font Awesome CSS加载...");
            wait.until(webDriver -> {
                try {
                    Object result = js.executeScript(
                        "return document.querySelector('link[href*=\"font-awesome\"]') !== null || " +
                        "document.querySelector('link[href*=\"fontawesome\"]') !== null"
                    );
                    return Boolean.TRUE.equals(result);
                } catch (Exception e) {
                    return false;
                }
            });

            
            Thread.sleep(8000);
            log.info("外部CDN资源加载完成");

        } catch (Exception e) {
            log.warn("等待外部CDN资源时出现异常: {}", e.getMessage());
        }
    }

    /**
     * 等待Font Awesome图标加载完成 - 新增方法
     */
    private void waitForFontAwesome(WebDriver driver) {
        try {
            log.info("开始等待Font Awesome图标加载...");
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
            JavascriptExecutor js = (JavascriptExecutor) driver;

            
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("i.fas, i.fa, i.far, i.fab")));
                log.info("Font Awesome图标元素已找到");
            } catch (Exception e) {
                log.warn("未找到Font Awesome图标元素: {}", e.getMessage());
            }

            
            wait.until(webDriver -> {
                try {
                    Object result = js.executeScript(
                        "var testIcon = document.createElement('i');" +
                        "testIcon.className = 'fas fa-home';" +
                        "testIcon.style.position = 'absolute';" +
                        "testIcon.style.left = '-9999px';" +
                        "document.body.appendChild(testIcon);" +
                        "var width = testIcon.offsetWidth;" +
                        "document.body.removeChild(testIcon);" +
                        "return width > 0;"
                    );
                    return Boolean.TRUE.equals(result);
                } catch (Exception e) {
                    return false;
                }
            });

            
            Thread.sleep(3000);
            log.info("Font Awesome图标加载完成");

        } catch (Exception e) {
            log.warn("等待Font Awesome图标时出现异常: {}", e.getMessage());
        }
    }

}
 