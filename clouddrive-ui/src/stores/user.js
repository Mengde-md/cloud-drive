import { defineStore } from 'pinia'
import * as authApi from '../api/auth'

/**
 * 登录态。
 *
 * token 存 localStorage 而不是只放内存：刷新页面后要还能保持登录。
 * 代价是 XSS 场景下可被脚本读取——这一点在 README 的已知缺口里记着，
 * 更稳妥的做法是 HttpOnly Cookie，但那需要后端改成 Cookie 认证。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('cd_token') || '',
    username: localStorage.getItem('cd_user') || '',
    id: null,
    role: '',
    email: '',
    profileLoaded: false,
    quotaBytes: 0,
    usedBytes: 0,
    availableBytes: 0
  }),

  getters: {
    loggedIn: (state) => Boolean(state.token),
    isAdmin: (state) => state.role === 'ADMIN',
    usedPercent: (state) =>
      state.quotaBytes > 0 ? Math.min(100, Math.round((state.usedBytes * 100) / state.quotaBytes)) : 0
  },

  actions: {
    async login(username, password) {
      const data = await authApi.login(username, password)
      this.token = data.accessToken
      this.username = username
      localStorage.setItem('cd_token', this.token)
      localStorage.setItem('cd_user', username)
      try {
        await this.loadProfile()
      } catch (error) {
        this.logout()
        throw error
      }
    },

    async register(username, password, email, code) {
      await authApi.register(username, password, email, code)
    },

    async loadProfile() {
      const data = await authApi.me()
      this.username = data.username
      this.id = data.id
      this.role = data.role
      this.email = data.email || ''
      this.profileLoaded = true
      this.quotaBytes = data.quotaBytes
      this.usedBytes = data.usedBytes
      this.availableBytes = data.availableBytes
      localStorage.setItem('cd_user', data.username)
    },

    logout() {
      this.token = ''
      this.username = ''
      this.id = null
      this.role = ''
      this.email = ''
      this.profileLoaded = false
      this.quotaBytes = 0
      this.usedBytes = 0
      this.availableBytes = 0
      localStorage.removeItem('cd_token')
      localStorage.removeItem('cd_user')
    }
  }
})
