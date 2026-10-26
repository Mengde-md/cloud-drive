import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { useUserStore } from '../stores/user'

/**
 * axios 实例：把"带令牌"和"错误提示"这两件每个请求都要做的事收敛到一处。
 *
 * baseURL 用相对路径 /api：开发期由 Vite 代理转发到 8080，
 * 生产期由 Nginx 反代转发。两种环境下前端代码完全一致，不需要按环境切域名。
 */
const request = axios.create({
  baseURL: '/api',
  timeout: 60000
})

// 请求拦截：统一带上 JWT
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('cd_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截：统一解析后端的 ApiError { status, error, message }
request.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error.response?.status
    const apiMessage = error.response?.data?.message

    if (status === 401 && error.config?.url === '/auth/login') {
      // 登录凭据错误需要明确提示，与已有登录状态失效分别处理。
      ElMessage.error('用户名或密码错误，请重新输入')
      return Promise.reject(error)
    }

    if (status === 401) {
      // 令牌失效或未登录：清掉本地状态并回登录页。
      // 这里不弹"401"这种技术词，用户看不懂。
      localStorage.removeItem('cd_token')
      localStorage.removeItem('cd_user')
      useUserStore().logout()
      if (router.currentRoute.value.name !== 'login') {
        ElMessage.warning('登录状态已失效，请重新登录')
        router.push({ name: 'login' })
      }
      return Promise.reject(error)
    }

    if (status) {
      ElMessage.error(apiMessage || `请求失败（${status}）`)
    } else {
      // 没有 response 说明请求根本没到服务端：后端没启动或网络断了
      ElMessage.error('暂时无法连接，请检查网络后重试')
    }
    return Promise.reject(error)
  }
)

export default request
