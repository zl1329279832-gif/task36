-- ============================================
-- 灰度可见/归档状态 + 运维仪表盘 - 数据库迁移脚本
-- ============================================

-- 1. sg_article 表新增字段
ALTER TABLE sg_article
    ADD COLUMN grayscale_groups VARCHAR(500) DEFAULT NULL COMMENT '灰度目标用户组(逗号分隔)',
    ADD COLUMN archive_reason VARCHAR(500) DEFAULT NULL COMMENT '归档原因';

-- 2. 插入新的菜单权限（仪表盘为顶级菜单，灰度/归档/重新发布为按钮权限）
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame,
                      menu_type, visible, status, perms, icon, del_flag)
VALUES ('运维仪表盘', 0, 8, 'article-dashboard', NULL, 1, 'C', '0', '0',
        'content:article:dashboard', 'dashboard', 0);

-- 获取文章审核父菜单ID
SET @review_menu_id = (SELECT id FROM sys_menu
    WHERE perms = 'content:article:review' AND menu_type = 'C' LIMIT 1);

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame,
                      menu_type, visible, status, perms, icon, del_flag)
VALUES
    ('灰度发布', @review_menu_id, 16, '', NULL, 1, 'F', '0', '0',
     'content:article:grayscale', '#', 0),
    ('归档文章', @review_menu_id, 17, '', NULL, 1, 'F', '0', '0',
     'content:article:archive', '#', 0),
    ('重新发布', @review_menu_id, 18, '', NULL, 1, 'F', '0', '0',
     'content:article:republish', '#', 0);

-- 3. 为管理员角色(id=1)分配新菜单权限
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE perms IN (
    'content:article:grayscale',
    'content:article:archive',
    'content:article:republish',
    'content:article:dashboard'
);
