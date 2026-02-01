package org.jeecg.modules.agenthub.copyright.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.agenthub.copyright.entity.CopyrightFile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 软著文件数据访问接口
 */
public interface CopyrightFileMapper extends BaseMapper<CopyrightFile> {

    /**
     * 根据项目ID查询文件列表
     */
    @Select("SELECT * FROM copyright_file WHERE project_id = #{projectId} ORDER BY create_time DESC")
    List<CopyrightFile> selectByProjectId(@Param("projectId") String projectId);

} 