package org.jeecg.modules.agenthub.copyright.entity;

import java.io.Serializable;
import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.format.annotation.DateTimeFormat;
import org.jeecgframework.poi.excel.annotation.Excel;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 版权文件实体类
 */
@Data
@TableName("copyright_file")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
@Schema(description="版权文件表（增强版）")
public class CopyrightFile implements Serializable {
    private static final long serialVersionUID = 1L;

    /**主键*/
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private String id;

    // ==================== 基础信息 ====================
    
    /**项目ID*/
    @Excel(name = "项目ID", width = 20)
    @Schema(description = "项目ID")
    private String projectId;
    
    /**文件名称*/
    @Excel(name = "文件名称", width = 25)
    @Schema(description = "文件名称")
    private String fileName;
    
    /**文件标题*/
    @Excel(name = "文件标题", width = 30)
    @Schema(description = "文件标题")
    private String title;
    
    /**文件类型：frontend_code前端代码, backend_code后端代码, document_chapters文档章节, screenshot截图路径*/
    @Excel(name = "文件类型", width = 15)
    @Schema(description = "文件类型")
    private String fileType;
    
    /**文件完整内容（代码、文档等）*/
    @Schema(description = "文件完整内容（代码、文档等）")
    private String content;

    // ==================== 简单状态 ====================
    
    /**文件状态：pending待生成, generating生成中, completed已完成, error错误*/
    @Excel(name = "文件状态", width = 12)
    @Schema(description = "文件状态")
    private String status;

    // ==================== 系统字段 ====================

    /**创建人*/
    @Schema(description = "创建人")
    private String createBy;

    /**创建日期*/
    @JsonFormat(timezone = "GMT+8",pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建日期")
    private Date createTime;

    /**更新人*/
    @Schema(description = "更新人")
    private String updateBy;

    /**更新日期*/
    @JsonFormat(timezone = "GMT+8",pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Schema(description = "更新日期")
    private Date updateTime;

    // ==================== 计算属性方法 ====================
    
    /**
     * 是否正在生成中
     */
    public Boolean isGenerating() {
        return "generating".equals(status);
    }
    
    /**
     * 是否已完成
     */
    public Boolean isCompleted() {
        return "completed".equals(status);
    }
    
    /**
     * 是否有错误
     */
    public Boolean hasError() {
        return "error".equals(status);
    }
    
    /**
     * 获取状态描述
     */
    public String getStatusDesc() {
        if (status == null) return "未知";
        switch (status) {
            case "pending": return "待生成";
            case "generating": return "生成中";
            case "completed": return "已完成";
            case "error": return "错误";
            default: return "未知状态";
        }
    }
    
    /**
     * 获取文件类型描述
     */
    public String getFileTypeDesc() {
        if (fileType == null) return "未知";
        switch (fileType) {
            case "frontend_code": return "前端代码";
            case "backend_code": return "后端代码";
            case "document_chapters": return "文档章节";
            case "screenshot": return "界面截图";
            default: return "其他文件";
        }
    }
    
    /**
     * 获取内容摘要（前200个字符）
     */
    public String getContentSummary() {
        if (content == null || content.length() == 0) {
            return "暂无内容";
        }
        if (content.length() <= 200) {
            return content;
        }
        return content.substring(0, 200) + "...";
    }
    
    /**
     * 检查是否有实际内容
     */
    public Boolean hasContent() {
        return content != null && !content.trim().isEmpty();
    }

    // ==================== 软著信息字段 ====================
    
    /**软件用途/开发目的*/
    @Excel(name = "软件用途", width = 25)
    @Schema(description = "软件用途/开发目的")
    private String softwarePurpose;
    
    /**面向领域/行业*/
    @Excel(name = "面向领域", width = 25)
    @Schema(description = "面向领域/行业")
    private String softwareDomain;
    
    /**软件主要功能*/
    @Excel(name = "主要功能", width = 50)
    @Schema(description = "软件主要功能")
    private String softwareFunctions;
    
    /**软件技术特点*/
    @Excel(name = "技术特点", width = 40)
    @Schema(description = "软件技术特点")
    private String softwareFeatures;
    
    /**源代码总行数*/
    @Excel(name = "源代码行数", width = 15)
    @Schema(description = "源代码总行数（用于软著申请）")
    private Integer sourceCodeLines;
} 