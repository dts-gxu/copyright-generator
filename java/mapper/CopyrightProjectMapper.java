package org.jeecg.modules.agenthub.copyright.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.agenthub.copyright.entity.CopyrightProject;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 软著项目数据访问接口
 */
public interface CopyrightProjectMapper extends BaseMapper<CopyrightProject> {

    /**
     * 根据用户查询项目列表
     */
    @Select("SELECT * FROM copyright_project WHERE create_by = #{createBy} ORDER BY create_time DESC")
    List<CopyrightProject> selectByCreateBy(@Param("createBy") String createBy);

} 