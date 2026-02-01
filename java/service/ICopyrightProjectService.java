package org.jeecg.modules.agenthub.copyright.service;

import org.jeecg.modules.agenthub.copyright.entity.CopyrightProject;
import com.baomidou.mybatisplus.extension.service.IService;
import java.util.List;

/**
 * 软著项目服务接口
 */
public interface ICopyrightProjectService extends IService<CopyrightProject> {

    /**
     * 创建项目
     */
    CopyrightProject createProject(String projectName, String appName, String domain, String appPrompt, String modelId, String createBy);

    /**
     * 更新项目进度
     */
    void updateProgress(String projectId, int progress);

    /**
     * 更新项目进度和当前步骤
     */
    void updateProgress(String projectId, int progress, String currentStep);

    /**
     * 更新项目状态
     */
    void updateStatus(String projectId, String status);

    /**
     * 更新当前步骤
     */
    void updateCurrentStep(String projectId, String currentStep);

    /**
     * 根据用户获取项目列表
     */
    List<CopyrightProject> getProjectsByUser(String createBy);

    /**
     * 原子性检查并更新项目状态（用于并发控制）
     * @param projectId 项目ID
     * @param expectedStatus 期望的当前状态
     * @param newStatus 新状态
     * @return 是否更新成功
     */
    boolean checkAndUpdateStatus(String projectId, String expectedStatus, String newStatus);
} 