# 软件著作权申请材料自动生成系统

Software Copyright Application Material Auto-Generation System

## 1. 项目概述

本系统基于大语言模型技术，实现软件著作权申请材料的自动化生成。系统接收用户输入的软件名称及应用领域，通过调用大语言模型API，自动生成符合国家版权局要求的完整申请材料，包括源代码、软件说明书及申请表等。

## 2. 系统架构

### 2.1 技术选型

| 层次 | 技术方案 | 版本要求 |
|-----|---------|----------|
| 应用层 | Spring Boot | 3.3+ |
| 运行环境 | JDK | 17+ |
| 持久层 | MyBatis-Plus | 3.5+ |
| 数据库 | MySQL | 8.0+ |
| 文档处理 | Python + python-docx | 3.9+ |
| 浏览器自动化 | Selenium + ChromeDriver | 4.x |
| 大语言模型 | DeepSeek API | - |

### 2.2 目录结构

```
opensource-copyright-generator/
├── src/main/
│   ├── java/com/copyright/  # Spring Boot启动类
│   └── resources/
│       └── application.yml  # 配置文件
├── java/
│   ├── controller/          # 控制层
│   ├── entity/              # 实体类
│   ├── mapper/              # 数据访问层
│   ├── service/             # 业务逻辑层
│   └── util/                # 工具类
├── frontend/
│   ├── api/                 # API接口定义
│   ├── views/               # 页面组件
│   ├── router/              # 路由配置
│   └── types/               # 类型定义
├── python/
│   ├── word_generator.py    # Word文档生成
│   ├── word_trimmer.py      # 文档裁剪
│   └── requirements.txt     # Python依赖
├── sql/
│   └── init.sql             # 数据库初始化
├── pom.xml                  # Maven配置
└── README.md
```

## 3. 环境配置

### 3.1 基础环境

1. **JDK 17+**
   ```bash
   java -version
   ```

2. **Python 3.9+**
   ```bash
   python --version
   pip install -r python/requirements.txt
   ```

3. **MySQL 8.0+**
   ```bash
   mysql -u root -p < sql/init.sql
   ```

### 3.2 Chrome无头浏览器配置

系统使用Selenium进行界面截图，需要配置ChromeDriver：

**Windows环境：**
```
1. 下载ChromeDriver: https://chromedriver.chromium.org/downloads
2. 将chromedriver.exe放置于项目根目录
3. 确保Chrome浏览器版本与ChromeDriver版本匹配
```

**Linux环境：**
```bash
# 安装Chrome
wget https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb
sudo dpkg -i google-chrome-stable_current_amd64.deb

# 安装ChromeDriver
wget https://chromedriver.storage.googleapis.com/114.0.5735.90/chromedriver_linux64.zip
unzip chromedriver_linux64.zip
chmod +x chromedriver
mv chromedriver /usr/local/bin/
```

### 3.3 LibreOffice配置（可选）

用于Word转PDF功能：

```bash
# Ubuntu/Debian
sudo apt-get install libreoffice

# CentOS/RHEL
sudo yum install libreoffice

# Windows
# 下载安装: https://www.libreoffice.org/download/
```

### 3.4 大语言模型API配置

在`application.yml`中配置API密钥：

```yaml
ai:
  deepseek:
    api-key: ${DEEPSEEK_API_KEY}
    base-url: https://api.deepseek.com/v1
```

或设置环境变量：
```bash
export DEEPSEEK_API_KEY=your_api_key_here
```

## 4. 运行方式

### 4.1 开发环境

```bash
# 编译项目
mvn clean compile

# 运行服务
mvn spring-boot:run
```

### 4.2 生产环境

```bash
# 打包
mvn clean package -DskipTests

# 运行
java -jar target/copyright-generator.jar --spring.profiles.active=prod
```

### 4.3 Docker部署

```dockerfile
FROM openjdk:17-jdk-slim
RUN apt-get update && apt-get install -y python3 python3-pip chromium chromium-driver
COPY target/*.jar app.jar
COPY python/ /app/python/
RUN pip3 install -r /app/python/requirements.txt
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

## 5. 接口说明

### 5.1 项目管理接口

| 接口路径 | 请求方式 | 功能描述 |
|---------|---------|----------|
| `/agenthub/api/copyright/projects` | POST | 创建生成项目 |
| `/agenthub/api/copyright/projects` | GET | 查询项目列表 |
| `/agenthub/api/copyright/projects/{id}/status` | GET | 查询生成状态 |
| `/agenthub/api/copyright/projects/{id}` | DELETE | 删除项目 |

### 5.2 生成接口

| 接口路径 | 请求方式 | 功能描述 |
|---------|---------|----------|
| `/agenthub/api/copyright/generate-names` | POST | 生成软件名称建议 |
| `/agenthub/api/copyright/projects/{id}/generate` | POST | 启动材料生成 |
| `/agenthub/api/copyright/download-materials/{id}` | GET | 下载生成材料 |

## 6. 生成流程

系统采用流水线式处理流程：

```
输入参数 → 前端代码生成 → 后端代码生成 → 说明书生成 → 截图生成 → 申请表生成 → 打包输出
```

各阶段耗时估算：
- 前端代码生成：约60秒
- 后端代码生成：约120秒（分3次调用）
- 说明书生成：约160秒（4章）
- 截图生成：约30秒
- 文档打包：约10秒

## 7. 输出文件

生成的ZIP压缩包包含以下文件：

| 文件名 | 说明 |
|-------|------|
| 源代码.html | 前端源代码文件 |
| 源代码.py | 后端源代码文件 |
| {软件名称}-软件说明书.docx | 软件说明书文档 |
| {软件名称}-源代码文档.pdf | 源代码打印文档（60页） |
| {软件名称}-软著申请表.docx | 软著申请信息表 |

## 8. 注意事项

1. **API调用限制**：大语言模型API存在调用频率限制，建议控制并发数不超过3个。

2. **内存配置**：建议JVM堆内存不低于2GB，处理大型文档时可适当增加。

3. **网络环境**：系统需要访问外部CDN资源（Bootstrap、Font Awesome等），请确保网络畅通。

4. **ChromeDriver版本**：必须与本地Chrome浏览器版本匹配，否则截图功能将失效。

5. **临时文件清理**：系统会在`temp/`目录生成临时文件，建议定期清理。

6. **编码问题**：所有文件均采用UTF-8编码，请确保数据库、操作系统编码一致。

## 9. 常见问题

**Q: 截图功能报错 "ChromeDriver not found"**

A: 请检查ChromeDriver是否正确安装，路径是否配置正确。Linux环境需要设置可执行权限。

**Q: Word文档生成失败**

A: 检查Python环境是否正确安装python-docx库，执行`pip install python-docx`。

**Q: API调用超时**

A: 大语言模型生成较长内容时可能超时，可适当增加超时时间配置。

## 10. 开源协议

本项目采用 MIT License 开源协议。

## 11. 参考文献

[1] 中国版权保护中心. 计算机软件著作权登记指南[EB/OL].

[2] Spring Boot官方文档. https://spring.io/projects/spring-boot

[3] Selenium官方文档. https://www.selenium.dev/documentation/
