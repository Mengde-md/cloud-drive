<template>
  <el-container class="app-layout">
    <!-- 桌面：固定侧边栏 -->
    <el-aside v-if="!isMobile" width="220px" class="app-aside">
      <SideNav :active="route.name" @navigate="onNavigate" />
    </el-aside>

    <el-container class="app-body">
      <el-header class="app-header">
        <div class="header-left">
          <!-- 手机：汉堡按钮唤出抽屉 -->
          <el-button v-if="isMobile" text :icon="Fold" class="menu-btn" @click="drawer = true" />
          <span class="header-title">{{ pageTitle }}</span>
        </div>

        <el-dropdown @command="onCommand">
          <span class="app-user">
            <el-icon><UserFilled /></el-icon>
            <span v-if="!isMobile" class="user-name">{{ userStore.username }}</span>
            <el-tag v-if="userStore.isAdmin" size="small">管理员</el-tag>
            <el-tag v-else size="small" type="info">普通用户</el-tag>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>{{ userStore.username }}</el-dropdown-item>
              <el-dropdown-item v-if="userStore.email" disabled>{{ userStore.email }}</el-dropdown-item>
              <el-dropdown-item command="ai">AI 智能分类</el-dropdown-item>
              <el-dropdown-item v-if="userStore.isAdmin" command="admin">管理员控制台</el-dropdown-item>
              <el-dropdown-item command="refresh">刷新配额</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <el-main class="app-main">
        <router-view :key="route.name" @refresh-quota="loadProfile" />
      </el-main>
    </el-container>

    <!-- 手机：抽屉导航。复用同一个 SideNav 组件，菜单只需维护一份 -->
    <el-drawer v-model="drawer" direction="ltr" size="72%" :with-header="false" class="nav-drawer">
      <SideNav :active="route.name" @navigate="onNavigate" />
    </el-drawer>
  </el-container>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Fold } from '@element-plus/icons-vue'
import SideNav from '../components/SideNav.vue'
import { useUserStore } from '../stores/user'
import { useBreakpoint } from '../composables/useBreakpoint'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { isMobile } = useBreakpoint()

const drawer = ref(false)

const titles = {
  files: '我的文件',
  'ai-center': 'AI 智能分类',
  'open-share': '打开分享',
  shares: '我的分享',
  trash: '回收站',
  'admin-users': '管理员控制台 · 用户与权限管理',
  'admin-reviews': '管理员控制台 · 文件审核管理'
}
const pageTitle = computed(() => titles[route.name] || '')

// 路由一变就收起抽屉：否则点完菜单抽屉还盖在页面上，手机上得再点一次遮罩
watch(() => route.name, () => {
  drawer.value = false
})

// 从手机宽度切回桌面时也要收起，避免抽屉压在固定侧边栏上
watch(isMobile, (mobile) => {
  if (!mobile) drawer.value = false
})

async function loadProfile() {
  try {
    await userStore.loadProfile()
  } catch (error) {
    // 401 已由拦截器处理并跳转
  }
}

function onNavigate(name) {
  if (route.name !== name) {
    router.push({ name })
  }
  drawer.value = false
}

function onCommand(command) {
  if (command === 'logout') {
    userStore.logout()
    ElMessage.success('已退出登录')
    router.push({ name: 'login' })
  } else if (command === 'refresh') {
    loadProfile()
  } else if (command === 'ai') {
    router.push({ name: 'ai-center' })
  } else if (command === 'admin' && userStore.isAdmin) {
    router.push({ name: 'admin-users' })
  }
}

onMounted(loadProfile)
</script>

<style scoped>
.app-layout {
  height: 100%;
}

.app-aside {
  border-right: 1px solid #e4e7ed;
  overflow: hidden;
}

.app-body {
  min-width: 0;
}

.app-header {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px 0 8px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.menu-btn {
  font-size: 20px;
}

.header-title {
  font-size: 15px;
  color: #606266;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.app-user {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  outline: none;
}

.app-main {
  padding: 16px;
  overflow: auto;
}

@media (max-width: 768px) {
  .app-main {
    padding: 12px;
  }
}
</style>

<style>
/* 抽屉内边距归零，让 SideNav 自己铺满 */
.nav-drawer .el-drawer__body {
  padding: 0;
}
</style>
