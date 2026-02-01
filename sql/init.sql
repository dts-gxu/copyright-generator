



CREATE TABLE IF NOT EXISTS `copyright_project` (
  `id` varchar(36) NOT NULL COMMENT '主键',
  `project_name` varchar(200) DEFAULT NULL COMMENT '软著项目名称',
  `app_name` varchar(200) DEFAULT NULL COMMENT '应用名称',
  `domain` varchar(100) DEFAULT NULL COMMENT '专业领域',
  `app_prompt` text COMMENT '应用描述',
  `model_id` varchar(50) DEFAULT NULL COMMENT '使用的AI模型',
  `status` varchar(20) DEFAULT 'pending' COMMENT '状态：pending/generating/completed/error/cancelled',
  `progress` int DEFAULT 0 COMMENT '进度百分比（0-100）',
  `current_step` varchar(200) DEFAULT NULL COMMENT '当前步骤描述',
  `completed_files` int DEFAULT 0 COMMENT '已完成文件数',
  `generating_files` int DEFAULT 0 COMMENT '生成中文件数',
  `start_time` datetime DEFAULT NULL COMMENT '生成开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '生成结束时间',
  `create_by` varchar(50) DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(50) DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_create_by` (`create_by`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='软著项目表';


CREATE TABLE IF NOT EXISTS `copyright_file` (
  `id` varchar(36) NOT NULL COMMENT '主键',
  `project_id` varchar(36) DEFAULT NULL COMMENT '项目ID',
  `file_name` varchar(200) DEFAULT NULL COMMENT '文件名称',
  `title` varchar(200) DEFAULT NULL COMMENT '文件标题',
  `file_type` varchar(50) DEFAULT NULL COMMENT '文件类型：frontend_code/backend_code/backend_part1/backend_part2/backend_part3/chapter1-4/screenshot/copyright_application/source_code_pdf',
  `content` longtext COMMENT '文件内容（代码、文档、Base64图片等）',
  `status` varchar(20) DEFAULT 'pending' COMMENT '状态：pending/generating/completed/error',
  `software_purpose` text COMMENT '软件用途/开发目的（软著信息）',
  `software_domain` varchar(200) DEFAULT NULL COMMENT '面向领域/行业（软著信息）',
  `software_functions` text COMMENT '软件主要功能（软著信息）',
  `software_features` text COMMENT '软件技术特点（软著信息）',
  `source_code_lines` int DEFAULT NULL COMMENT '源代码总行数（用于软著申请）',
  `create_by` varchar(50) DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(50) DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_project_id` (`project_id`),
  KEY `idx_file_type` (`file_type`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='软著文件表';


-- ALTER TABLE `copyright_file` ADD CONSTRAINT `fk_copyright_file_project` 
--   FOREIGN KEY (`project_id`) REFERENCES `copyright_project`(`id`) ON DELETE CASCADE;
