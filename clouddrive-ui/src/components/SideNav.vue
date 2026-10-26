<template>
  <div class="side-nav">
    <div class="nav-logo">
      <el-icon :size="22" color="#409eff"><FolderOpened /></el-icon>
      <span>网络云盘</span>
    </div>

    <div class="nav-identity">
      <el-tag :type="userStore.isAdmin ? 'primary' : 'info'" size="small">{{ userStore.isAdmin ? '管理员' : '普通用户' }}</el-tag>
      <span class="identity-name">{{ userStore.username }}</span>
      <p>{{ userStore.isAdmin ? '可管理用户账号与文件审核' : '管理自己的文件与分享' }}</p>
    </div>

    <div class="nav-list">
      <template v-for="item in items" :key="item.name">
      <div v-if="item.name === 'admin-users'" class="nav-section">管理员控制台</div>
      <button
        type="button"
        class="nav-item"
        :class="{ active: active === item.name }"
        @click="$emit('navigate', item.name)"
      >
        <el-icon>
          <Document v-if="item.name === 'files'" />
          <MagicStick v-else-if="item.name === 'ai-center'" />
          <Link v-else-if="item.name === 'open-share'" />
          <Share v-else-if="item.name === 'shares'" />
          <UserFilled v-else-if="item.name === 'admin-users'" />
          <CircleCheck v-else-if="item.name === 'admin-reviews'" />
          <Delete v-else />
        </el-icon>
        <span>{{ item.label }}</span>
      </button>
      </template>
    </div>

    <div class="nav-quota">
      <div class="cd-muted quota-title">存储空间</div>
      <el-progress
        :percentage="userStore.usedPercent"
        :stroke-width="8"
        :status="userStore.usedPercent >= 90 ? 'exception' : undefined"
      />
      <div class="cd-muted quota-text">
        {{ formatBytes(userStore.usedBytes) }} / {{ formatBytes(userStore.quotaBytes) }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { CircleCheck, Delete, Document, FolderOpened, Link, MagicStick, Share, UserFilled } from '@element-plus/icons-vue'
import { useUserStore } from '../stores/user'
import { formatBytes } from '../utils'

defineProps({
  active: { type: String, default: '' }
})
defineEmits(['navigate'])

const userStore = useUserStore()

// 导航项抽成数据而不是写死三段模板：桌面侧边栏和手机抽屉共用这一个组件，
// 改菜单只需要动这一处。
const items = computed(() => [
  { name: 'files', label: '我的文件' },
  { name: 'ai-center', label: 'AI 智能分类' },
  { name: 'open-share', label: '打开分享' },
  { name: 'shares', label: '我的分享' },
  { name: 'trash', label: '回收站' },
  ...(userStore.isAdmin ? [
    { name: 'admin-users', label: '用户与权限管理' },
    { name: 'admin-reviews', label: '文件审核管理' }
  ] : [])
])
</script>

<style scoped>
.side-nav {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
}

.nav-logo {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 20px;
  font-size: 16px;
  font-weight: 600;
  border-bottom: 1px solid #f0f2f5;
  flex-shrink: 0;
}

.nav-list {
  flex: 1;
  padding: 8px 0;
  overflow-y: auto;
}

.nav-identity { padding: 16px 20px 12px; border-bottom: 1px solid #f0f2f5; }
.identity-name { margin-left: 8px; font-size: 13px; overflow-wrap: anywhere; }
.nav-identity p { color: #909399; font-size: 12px; line-height: 1.6; margin: 8px 0 0; }
.nav-section { color: #909399; font-size: 12px; padding: 18px 20px 8px; }

.nav-item {
  border: 0;
  width: 100%;
  background: transparent;
  font-family: inherit;
  text-align: left;
  display: flex;
  align-items: center;
  gap: 10px;
  /* 48px 是手机上手指点击的舒适高度，低于 44px 容易点不中 */
  height: 48px;
  padding: 0 20px;
  cursor: pointer;
  font-size: 14px;
  color: #303133;
}

.nav-item:hover {
  background: #f5f7fa;
}

.nav-item.active {
  color: #409eff;
  background: #ecf5ff;
  font-weight: 500;
}

.nav-quota {
  padding: 16px 20px 20px;
  border-top: 1px solid #f0f2f5;
  flex-shrink: 0;
}

.quota-title {
  margin-bottom: 8px;
}

.quota-text {
  margin-top: 6px;
}
</style>
