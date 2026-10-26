import request from './request'

export const register = (username, password, email, code) =>
  request.post('/auth/register', { username, password, email, code })

export const sendEmailCode = (email, purpose) =>
  request.post('/auth/email-code', { email, purpose })

export const resetPassword = (email, code, password) =>
  request.post('/auth/reset-password', { email, code, password })

export const login = (username, password) =>
  request.post('/auth/login', { username, password })

/** 当前身份 + 配额使用情况 */
export const me = () => request.get('/auth/me')
