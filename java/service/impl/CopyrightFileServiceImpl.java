package org.jeecg.modules.agenthub.copyright.service.impl;

import org.jeecg.modules.agenthub.copyright.entity.CopyrightFile;
import org.jeecg.modules.agenthub.copyright.mapper.CopyrightFileMapper;
import org.jeecg.modules.agenthub.copyright.service.ICopyrightFileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import java.util.Date;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

/**
 * 软著文件服务实现类
 */
@Slf4j
@Service
public class CopyrightFileServiceImpl extends ServiceImpl<CopyrightFileMapper, CopyrightFile> implements ICopyrightFileService {

    @Override
    @Transactional
    public CopyrightFile saveGeneratedFile(String projectId, String fileType, String fileName, String title, String fileContent, String createBy) {
        
        CopyrightFile existingFile = getFileByProjectIdAndType(projectId, fileType);
        
        if (existingFile != null) {
            
            log.info("文件已存在，更新现有文件: projectId={}, fileType={}, fileId={}", 
                projectId, fileType, existingFile.getId());
            
            existingFile.setFileName(fileName != null ? fileName : generateFileName(fileType));
            existingFile.setTitle(title);
            existingFile.setContent(fileContent);
            existingFile.setUpdateBy(createBy);
            existingFile.setUpdateTime(new Date());
            existingFile.setStatus("completed");
            
            this.updateById(existingFile);
            return existingFile;
        } else {
            
            log.info("创建新文件: projectId={}, fileType={}", projectId, fileType);
            
            CopyrightFile file = new CopyrightFile();
            file.setProjectId(projectId);
            file.setFileType(fileType);
            file.setFileName(fileName != null ? fileName : generateFileName(fileType));
            file.setTitle(title);
            file.setContent(fileContent);
            file.setCreateBy(createBy);
            file.setCreateTime(new Date());
            file.setStatus("completed");
            
            this.save(file);
            return file;
        }
    }

    @Override
    public CopyrightFile getFileByProjectIdAndType(String projectId, String fileType) {
        List<CopyrightFile> files = this.baseMapper.selectByProjectId(projectId);
        return files.stream()
                .filter(file -> fileType.equals(file.getFileType()))
                .findFirst()
                .orElse(null);
    }

    @Override
    @Transactional
    public void deleteByProjectId(String projectId) {
        this.remove(new QueryWrapper<CopyrightFile>().eq("project_id", projectId));
    }

    private String generateFileName(String fileType) {
        switch (fileType) {
            case "frontend_code":
                return "前端代码.html";
            case "backend_code":
                return "后端代码.java";
            case "chapter1":
                return "第一章.txt";
            case "chapter2":
                return "第二章.txt";
            case "chapter3":
                return "第三章.txt";
            case "chapter4":
                return "第四章.txt";
            case "screenshot":
                return "界面截图.png";
            default:
                return "文件.txt";
        }
    }
}
