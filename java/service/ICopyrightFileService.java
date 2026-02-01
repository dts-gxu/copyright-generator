package org.jeecg.modules.agenthub.copyright.service;

import org.jeecg.modules.agenthub.copyright.entity.CopyrightFile;
import com.baomidou.mybatisplus.extension.service.IService;
import java.util.List;

/**
 * 软著文件服务接口
 */
public interface ICopyrightFileService extends IService<CopyrightFile> {

    /**
     * 保存生成的文件
     */
    CopyrightFile saveGeneratedFile(String projectId, String fileType, String fileName, String title, String fileContent, String createBy);

    /**
     * 根据项目ID和文件类型获取文件
     */
    CopyrightFile getFileByProjectIdAndType(String projectId, String fileType);
    
    /**
     * 根据项目ID删除文件
     */
    void deleteByProjectId(String projectId);
} 