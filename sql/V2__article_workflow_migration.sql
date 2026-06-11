-- ============================================
-- 文章生命周期工作流 - 数据库迁移脚本
-- ============================================

-- 1. sg_article 表新增字段
ALTER TABLE sg_article
    ADD COLUMN publish_time DATETIME DEFAULT NULL COMMENT '定时发布时间',
    ADD COLUMN reject_reason VARCHAR(500) DEFAULT NULL COMMENT '驳回原因',
    ADD COLUMN violation_reason VARCHAR(500) DEFAULT NULL COMMENT '违规原因';

-- 2. 状态值迁移：旧 0=已发布 -> 新 3=已发布，旧 1=草稿 -> 新 0=草稿
-- 注意：先将 '0'(已发布) 改为 '3'，再将 '1'(草稿) 改为 '0'
UPDATE sg_article SET status = '3' WHERE status = '0';
UPDATE sg_article SET status = '0' WHERE status = '1';

-- 3. 创建文章审核操作日志表
CREATE TABLE IF NOT EXISTS sg_article_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    article_id BIGINT NOT NULL COMMENT '文章ID',
    from_status VARCHAR(2) DEFAULT NULL COMMENT '原状态',
    to_status VARCHAR(2) NOT NULL COMMENT '新状态',
    operator_id BIGINT NOT NULL COMMENT '操作人ID',
    operator_name VARCHAR(64) DEFAULT NULL COMMENT '操作人姓名',
    reason VARCHAR(500) DEFAULT NULL COMMENT '操作原因/备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    INDEX idx_article_id (article_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章审核操作日志';

-- 4. 插入工作流相关菜单权限（parent_id=0 表示顶级菜单，实际使用时需要根据实际情况调整）
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, menu_type, visible, status, perms, icon, del_flag)
VALUES
('文章审核', 0, 5, 'article-review', NULL, 1, 'C', '0', '0', 'content:article:review', 'edit', 0),
('定时发布管理', 0, 6, 'article-scheduled', NULL, 1, 'C', '0', '0', 'content:article:schedule', 'time', 0),
('违规文章管理', 0, 7, 'article-violation', NULL, 1, 'C', '0', '0', 'content:article:violation', 'bug', 0);

-- 获取刚插入的审核菜单ID，用作按钮权限的parent_id
SET @review_menu_id = LAST_INSERT_ID() - 2;

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, menu_type, visible, status, perms, icon, del_flag)
VALUES
('提交审核', @review_menu_id, 10, '', NULL, 1, 'F', '0', '0', 'content:article:submit', '#', 0),
('审核通过', @review_menu_id, 11, '', NULL, 1, 'F', '0', '0', 'content:article:approve', '#', 0),
('审核驳回', @review_menu_id, 12, '', NULL, 1, 'F', '0', '0', 'content:article:reject', '#', 0),
('撤回文章', @review_menu_id, 13, '', NULL, 1, 'F', '0', '0', 'content:article:withdraw', '#', 0),
('违规下架', @review_menu_id, 14, '', NULL, 1, 'F', '0', '0', 'content:article:violation', '#', 0),
('强制发布', @review_menu_id, 15, '', NULL, 1, 'F', '0', '0', 'content:article:forcePublish', '#', 0);

-- 5. 为管理员角色(id=1)分配新菜单权限
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE perms IN (
    'content:article:review',
    'content:article:schedule',
    'content:article:violation',
    'content:article:submit',
    'content:article:approve',
    'content:article:reject',
    'content:article:withdraw',
    'content:article:forcePublish'
);
