import request from './request'

export const getCapabilities = () => request.get('/analysis/capabilities')

export const listAnalysis = (entryIds) =>
  request.get('/analysis', { params: { entryIds: entryIds.join(',') } })
export const getAnalysis = (entryId) => request.get(`/analysis/${entryId}`)
export const reanalyze = (entryId) => request.post(`/analysis/${entryId}/reanalyze`)
export const listReviews = (status, page, size) =>
  request.get('/admin/reviews', { params: { ...(status ? { status } : {}), page, size } })
export const reviewFile = (entryId, decision, reason) =>
  request.post(`/admin/reviews/${entryId}`, { decision, reason })
export const getAnalysisSettings = () => request.get('/admin/analysis-settings')
export const setAnalysisSettings = (reviewEnabled) =>
  request.put('/admin/analysis-settings', { reviewEnabled })
