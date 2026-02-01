package org.jeecg.modules.agenthub.copyright.service.impl;

import org.jeecg.modules.agenthub.copyright.entity.CopyrightProject;
import org.jeecg.modules.agenthub.copyright.mapper.CopyrightProjectMapper;
import org.jeecg.modules.agenthub.copyright.service.ICopyrightProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import java.util.Date;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 软著项目服务实现类
 */
@Slf4j
@Service
public class CopyrightProjectServiceImpl extends ServiceImpl<CopyrightProjectMapper, CopyrightProject> implements ICopyrightProjectService {

    @Override
    @Transactional
    public CopyrightProject createProject(String projectName, String appName, String domain, String appPrompt, String modelId, String createBy) {
        
        CopyrightProject project = new CopyrightProject();
        project.setProjectName(projectName);
        project.setAppName(appName);
        project.setDomain(domain);
        project.setAppPrompt(appPrompt);
        project.setModelId(modelId);
        project.setStatus("pending");
        project.setProgress(0);
        project.setCurrentStep("准备生成...");
        project.setCompletedFiles(0);
        project.setGeneratingFiles(0);
        project.setCreateBy(createBy);
        project.setCreateTime(new Date());
        
        this.save(project);
        return project;
    }

    @Override
    public void updateProgress(String projectId, int progress) {
        CopyrightProject project = new CopyrightProject();
        project.setId(projectId);
        project.setProgress(progress);
        project.setUpdateTime(new Date());
        this.updateById(project);
    }

    @Override
    public void updateProgress(String projectId, int progress, String currentStep) {
        CopyrightProject project = new CopyrightProject();
        project.setId(projectId);
        project.setProgress(progress);
        project.setCurrentStep(currentStep);
        project.setUpdateTime(new Date());
        this.updateById(project);
    }

    @Override
    public void updateStatus(String projectId, String status) {
        CopyrightProject project = new CopyrightProject();
        project.setId(projectId);
        project.setStatus(status);
        project.setUpdateTime(new Date());
        
        if ("completed".equals(status)) {
            project.setEndTime(new Date());
        } else if ("generating".equals(status)) {
            project.setStartTime(new Date());
        }
        
        this.updateById(project);
    }

    @Override
    public void updateCurrentStep(String projectId, String currentStep) {
        CopyrightProject project = new CopyrightProject();
        project.setId(projectId);
        project.setCurrentStep(currentStep);
        project.setUpdateTime(new Date());
        this.updateById(project);
    }

    @Override
    public List<CopyrightProject> getProjectsByUser(String createBy) {
        return this.baseMapper.selectByCreateBy(createBy);
    }

    @Override
    @Transactional
    public boolean checkAndUpdateStatus(String projectId, String expectedStatus, String newStatus) {
        try {
            CopyrightProject project = this.getById(projectId);
            if (project == null) {
                return false;
            }
            if (expectedStatus != null && !expectedStatus.equals(project.getStatus())) {
                return false;
            }
            project.setStatus(newStatus);
            project.setUpdateTime(new Date());
            if ("completed".equals(newStatus)) {
                project.setEndTime(new Date());
            } else if ("generating".equals(newStatus)) {
                project.setStartTime(new Date());
            }
            return this.updateById(project);
        } catch (Exception e) {
            log.error("原子性更新项目状态失败: projectId={}, expectedStatus={}, newStatus={}, error={}",
                projectId, expectedStatus, newStatus, e.getMessage());
            return false;
        }
    }
} 