import request from './request'

export const listUsers = (page, size, query) =>
  request.get('/admin/users', { params: { page, size, query } })
export const resetUserPassword = (id, password) =>
  request.patch(`/admin/users/${id}/password`, { password })
export const setUserEnabled = (id, enabled) =>
  request.patch(`/admin/users/${id}/status`, { enabled })
export const setUserRole = (id, role) =>
  request.patch(`/admin/users/${id}/role`, { role })
export const expandUserQuota = (id, quotaBytes) =>
  request.patch(`/admin/users/${id}/quota`, { quotaBytes })
