package org.jeecg.modules.agenthub.util;

import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * 跨平台兼容性工具类 - Linux/Windows统一处理
 */
@Slf4j
public class CrossPlatformUtil {

    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final boolean IS_WINDOWS = OS_NAME.contains("win");
    private static final boolean IS_LINUX = OS_NAME.contains("linux");
    private static final boolean IS_MAC = OS_NAME.contains("mac");

    /**
     * 获取ChromeDriver文件名（跨平台）
     * Windows: chromedriver.exe
     * Linux/Mac: chromedriver
     */
    public static String getChromeDriverName() {
        return IS_WINDOWS ? "chromedriver.exe" : "chromedriver";
    }

    /**
     * 获取ChromeDriver完整路径
     * @param baseDir 基础目录
     * @return ChromeDriver完整路径
     */
    public static String getChromeDriverPath(String baseDir) {
        String driverName = getChromeDriverName();
        String path = baseDir + File.separator + driverName;
        log.debug("操作系统: {}, ChromeDriver路径: {}", OS_NAME, path);
        return path;
    }

    /**
     * 获取Python命令（跨平台）
     * Windows: python
     * Linux: 优先python3，降级python
     */
    public static String getPythonCommand() {
        if (IS_WINDOWS) {
            return "python";
        }
        
        if (isPythonCommandAvailable("python3")) {
            log.debug("使用python3命令");
            return "python3";
        }
        
        if (isPythonCommandAvailable("python")) {
            log.debug("使用python命令");
            return "python";
        }
        
        log.warn("Python命令不可用，降级使用python3");
        return "python3";
    }

    /**
     * 检查Python命令是否可用
     */
    private static boolean isPythonCommandAvailable(String command) {
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
     * 获取操作系统类型
     */
    public static String getOSName() {
        return OS_NAME;
    }

    /**
     * 是否为Windows系统
     */
    public static boolean isWindows() {
        return IS_WINDOWS;
    }

    /**
     * 是否为Linux系统
     */
    public static boolean isLinux() {
        return IS_LINUX;
    }

    /**
     * 是否为Mac系统
     */
    public static boolean isMac() {
        return IS_MAC;
    }

    /**
     * 获取Chrome无头模式选项（Linux需要额外参数）
     * @return Chrome启动参数数组
     */
    public static String[] getChromeHeadlessOptions() {
        if (IS_LINUX) {
            return new String[]{
                "--headless=new",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--disable-software-rasterizer",
                "--disable-extensions"
            };
        } else {
            // Windows/Mac
            return new String[]{
                "--headless=new",
                "--disable-gpu"
            };
        }
    }

    /**
     * 确保ChromeDriver有执行权限（Linux）
     */
    public static void ensureChromeDriverExecutable(String chromeDriverPath) {
        if (IS_LINUX || IS_MAC) {
            try {
                File chromeDriver = new File(chromeDriverPath);
                if (chromeDriver.exists()) {
                    chromeDriver.setExecutable(true, false);
                    log.info("已设置ChromeDriver执行权限: {}", chromeDriverPath);
                }
            } catch (Exception e) {
                log.warn("设置ChromeDriver执行权限失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 获取临时目录路径（跨平台）
     */
    public static String getTempDir() {
        return System.getProperty("java.io.tmpdir");
    }

    /**
     * 构建跨平台文件路径
     */
    public static String buildPath(String... parts) {
        return String.join(File.separator, parts);
    }
}
