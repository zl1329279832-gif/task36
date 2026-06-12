-- ============================================
-- V3: Article Lifecycle Extension
-- Add GRAY_VISIBLE(6), REPUBLISH(7), ARCHIVED(8) statuses
-- ============================================

-- 1. sg_article table: add new columns for extended lifecycle
ALTER TABLE sg_article ADD COLUMN gray_audience VARCHAR(1000) DEFAULT NULL COMMENT 'grayscale audience (comma-separated user IDs)';
ALTER TABLE sg_article ADD COLUMN republish_count INT DEFAULT 0 COMMENT 'republish counter';
ALTER TABLE sg_article ADD COLUMN archived_time DATETIME DEFAULT NULL COMMENT 'archive timestamp';
ALTER TABLE sg_article ADD COLUMN gray_publish_time DATETIME DEFAULT NULL COMMENT 'gray publish timestamp';

-- 2. Update status column comment to reflect all statuses
ALTER TABLE sg_article MODIFY COLUMN status VARCHAR(2) DEFAULT '0'
    COMMENT '0-draft 1-pending 2-scheduled 3-published 4-withdrawn 5-violation 6-gray 7-republish 8-archived';

-- 3. Cache operation log table (persistent fallback for dashboard stats)
CREATE TABLE IF NOT EXISTS sg_cache_operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_type VARCHAR(20) NOT NULL COMMENT 'REFRESH or INVALIDATE',
    article_id BIGINT DEFAULT NULL,
    operator_id BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_op_type (operation_type),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. New menu permissions for extended lifecycle operations
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, menu_type, visible, status, perms, icon, del_flag) VALUES
('灰度发布', 0, 8, 'article-gray', NULL, 1, 'C', '0', '0', 'content:article:grayPublish', 'eye', 0),
('完全发布', 0, 9, 'article-full', NULL, 1, 'C', '0', '0', 'content:article:fullPublish', 'check', 0),
('重新发布', 0, 10, 'article-republish', NULL, 1, 'C', '0', '0', 'content:article:republish', 'redo', 0),
('归档管理', 0, 11, 'article-archive', NULL, 1, 'C', '0', '0', 'content:article:archive', 'inbox', 0),
('运营看板', 0, 12, 'dashboard', NULL, 1, 'C', '0', '0', 'content:dashboard:view', 'dashboard', 0);

-- 5. Assign new permissions to admin role (id=1)
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE perms IN (
    'content:article:grayPublish', 'content:article:fullPublish',
    'content:article:republish', 'content:article:archive', 'content:dashboard:view'
);
