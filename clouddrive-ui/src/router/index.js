import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  // 匿名访问：拿到分享链接的人不需要登录
  { path: '/s/:token', name: 'share-access', component: () => import('../views/ShareAccess.vue') },
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    children: [
      { path: '', redirect: '/files' },
      { path: 'files', name: 'files', component: () => import('../views/Files.vue') },
      { path: 'ai', name: 'ai-center', component: () => import('../views/AiCenter.vue') },
      { path: 'open', name: 'open-share', component: () => import('../views/OpenShare.vue') },
      { path: 'shares', name: 'shares', component: () => import('../views/Shares.vue') },
      { path: 'trash', name: 'trash', component: () => import('../views/Trash.vue') },
      { path: 'admin/users', name: 'admin-users', component: () => import('../views/AdminUsers.vue'), meta: { admin: true } },
      { path: 'admin/reviews', name: 'admin-reviews', component: () => import('../views/AdminReviews.vue'), meta: { admin: true } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/files' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫：没令牌就别进主框架。
// 这只是体验层面的拦截，真正的权限判断在后端——
// 前端守卫可以被绕过，绝不能当成安全边界。
router.beforeEach(async (to) => {
  const isPublic = to.name === 'login' || to.name === 'share-access'
  const token = localStorage.getItem('cd_token')
  if (!isPublic && !token) {
    useUserStore().logout()
    return { name: 'login' }
  }
  if (!isPublic && token) {
    const user = useUserStore()
    try {
      if (!user.profileLoaded || to.meta.admin) await user.loadProfile()
    } catch (error) {
      if (!localStorage.getItem('cd_token')) {
        user.logout()
        return { name: 'login' }
      }
      return false
    }
    if (to.meta.admin && !user.isAdmin) return { name: 'files' }
  }
  if (to.name === 'login' && token) {
    return { name: 'files' }
  }
  return true
})

export default router
