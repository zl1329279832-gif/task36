-- 文章生命周期工作流 - 数据库变更脚本

-- 1. sg_article 表新增字段
ALTER TABLE sg_article ADD COLUMN publish_time DATETIME NULL COMMENT '定时发布时间' AFTER is_comment;
ALTER TABLE sg_article ADD COLUMN violation_reason VARCHAR(500) NULL COMMENT '违规下线原因' AFTER publish_time;

-- 2. 新建文章操作日志表
CREATE TABLE IF NOT EXISTS sg_article_operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    article_id BIGINT NOT NULL COMMENT '文章ID',
    operator_id BIGINT NOT NULL COMMENT '操作人ID（-1表示系统自动）',
    from_status VARCHAR(2) NOT NULL COMMENT '转换前状态',
    to_status VARCHAR(2) NOT NULL COMMENT '转换后状态',
    reason VARCHAR(500) NULL COMMENT '操作原因/备注',
    create_time DATETIME NOT NULL COMMENT '操作时间',
    del_flag INT DEFAULT 0 COMMENT '删除标志（0未删除，1已删除）',
    KEY idx_article_id (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章操作日志表';

-- 3. 插入工作流权限菜单按钮（type='F' 表示按钮权限）
-- 注意：parent_id 需根据实际环境中"写博文"菜单的 id 进行调整
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, del_flag, remark)
VALUES
('提交审核', 0, 10, '', '', 'F', '0', '0', 'content:article:submitReview', '#', 1, NOW(), 1, NOW(), 0, '文章提交审核权限'),
('审核通过', 0, 11, '', '', 'F', '0', '0', 'content:article:approve', '#', 1, NOW(), 1, NOW(), 0, '文章审核通过权限'),
('审核驳回', 0, 12, '', '', 'F', '0', '0', 'content:article:reject', '#', 1, NOW(), 1, NOW(), 0, '文章审核驳回权限'),
('撤回文章', 0, 13, '', '', 'F', '0', '0', 'content:article:withdraw', '#', 1, NOW(), 1, NOW(), 0, '文章撤回权限'),
('违规下线', 0, 14, '', '', 'F', '0', '0', 'content:article:violationOffline', '#', 1, NOW(), 1, NOW(), 0, '文章违规下线权限');
