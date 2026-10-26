-- 目录级联删除（Day22 的补充修正）。
--
-- 问题：原来删除只更新一行，删掉一个非空目录后，里面的文件仍然是"未删除"状态，
-- 照样占配额、照样出现在列表接口里，但因为父目录已进回收站、界面进不去，
-- 就成了看不见却占空间的幽灵条目。
--
-- 解法：删除时把整棵子树一起标记，并在每一行上记下"这次删除的根是谁"。
-- trash_root_id 带来三个好处：
--   1. 回收站列表只需要 trash_root_id = id 这一个条件就能过滤出"用户当初删的那一个"，
--      不必把几百个子孙条目全铺在界面上；
--   2. 恢复时按 trash_root_id 一次性把整棵子树捞回来；
--   3. 天然区分"先单独删了某个子文件、之后又删了父目录"这种情况——
--      两次删除的 trash_root_id 不同，恢复父目录不会顺手把那个早先单独删掉的文件也带回来。
ALTER TABLE drive_entries
    ADD COLUMN trash_root_id BIGINT NULL,
    ADD INDEX idx_drive_entries_trash_root (owner_id, trash_root_id);
