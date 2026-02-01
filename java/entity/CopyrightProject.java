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
 * 版权项目实体类
 */
@Data
@TableName("copyright_project")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
@Schema(description="版权项目表（增强版）")
public class CopyrightProject implements Serializable {
    private static final long serialVersionUID = 1L;

    /**主键*/
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private String id;

    // ==================== 基础信息 ====================
    
    /**软著项目名称*/
    @Excel(name = "软著项目名称", width = 25)
    @Schema(description = "软著项目名称")
    private String projectName;
    
    /**应用名称*/
    @Excel(name = "应用名称", width = 20)
    @Schema(description = "应用名称")
    private String appName;
    
    /**专业领域*/
    @Excel(name = "专业领域", width = 15)
    @Schema(description = "专业领域")
    private String domain;
    
    /**应用描述*/
    @Excel(name = "应用描述", width = 30)
    @Schema(description = "应用描述")
    private String appPrompt;
    
    /**使用的AI模型*/
    @Excel(name = "AI模型", width = 15)
    @Schema(description = "使用的AI模型")
    private String modelId;
    
    // ==================== 生成状态 ====================
    
    /**项目状态：pending待生成, generating生成中, completed已完成, error错误, cancelled取消*/
    @Excel(name = "项目状态", width = 15)
    @Schema(description = "项目状态")
    private String status;
    
    /**生成进度百分比(0-100)*/
    @Excel(name = "生成进度", width = 10)
    @Schema(description = "生成进度百分比(0-100)")
    private Integer progress;
    
    /**当前生成步骤描述*/
    @Excel(name = "当前步骤", width = 20)
    @Schema(description = "当前生成步骤描述")
    private String currentStep;
    


    // ==================== 简单统计信息 ====================
    
    /**已完成文件数量*/
    @Excel(name = "已完成文件数", width = 12)
    @Schema(description = "已完成文件数量")
    private Integer completedFiles;
    
    /**生成中文件数量*/
    @Excel(name = "生成中文件数", width = 12)
    @Schema(description = "生成中文件数量")
    private Integer generatingFiles;



    // ==================== 时间信息 ====================
    
    /**生成开始时间*/
    @JsonFormat(timezone = "GMT+8",pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Schema(description = "生成开始时间")
    private Date startTime;
    
    /**生成结束时间*/
    @JsonFormat(timezone = "GMT+8",pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Schema(description = "生成结束时间")
    private Date endTime;
    
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
     * 获取完成率（基于4个标准文件：前端、后端、文档、截图）
     */
    public Double getCompletionRate() {
        int totalFiles = 4; 
        return (double) (completedFiles != null ? completedFiles : 0) / totalFiles * 100;
    }
    
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
            case "cancelled": return "已取消";
            default: return "未知状态";
        }
    }
} 