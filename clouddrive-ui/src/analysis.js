export const categories = {
  DOCUMENT: '文档', IMAGE: '图片', AUDIO: '音频', VIDEO: '视频',
  ARCHIVE: '压缩包', CODE: '代码', SPREADSHEET: '表格', PRESENTATION: '演示文稿', OTHER: '其他'
}
export const analysisStatuses = {
  NOT_ANALYZED: '尚未分析', PENDING: '分析中', COMPLETED: '已分析', FALLBACK: '后缀分类'
}
export const reviewStatuses = {
  DISABLED: '未启用辅助审核', UNREVIEWED: '未复核', PENDING_REVIEW: '待人工复核',
  APPROVED: '人工确认通过', REJECTED: '人工标记问题'
}
export const reviewTagType = (status) => ({
  PENDING_REVIEW: 'warning', APPROVED: 'success', REJECTED: 'danger'
}[status] || 'info')
export const sourceLabel = (source) => source === 'DEEPSEEK' ? '智能分析' : '文件后缀'
