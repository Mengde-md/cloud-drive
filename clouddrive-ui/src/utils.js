/** 字节数转人类可读单位；未知或无效的数值使用占位符。 */
export function formatBytes(bytes) {
  if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes < 0) return '-'
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB']
  const index = Math.min(units.length - 1, Math.max(0, Math.floor(Math.log(bytes) / Math.log(1024))))
  const value = bytes / Math.pow(1024, index)
  return `${value.toFixed(index === 0 ? 0 : 1)} ${units[index]}`
}

/** 把后端返回的 LocalDateTime 字符串收拾成易读格式 */
export function formatTime(text) {
  if (!text) return '-'
  return String(text).replace('T', ' ').slice(0, 19)
}

/** 用 blob 触发浏览器下载（下载接口需要带令牌，不能用普通链接） */
export function saveBlob(blob, filename) {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  // 必须释放，否则这个 blob 会一直占着内存直到页面关闭
  window.URL.revokeObjectURL(url)
}

/**
 * 复制文本到剪贴板。
 *
 * navigator.clipboard 只在安全上下文（HTTPS 或 localhost）可用。
 * 部署到服务器后用 http://IP 访问时它是 undefined，
 * 直接调用会抛 TypeError——这正是"本地测试没问题、上线就失效"的典型来源。
 * 所以这里退回到老办法：临时 textarea + execCommand('copy')。
 *
 * @returns {Promise<boolean>} 是否复制成功
 */
export async function copyText(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch (error) {
    // 落到下面的兜底方案
  }

  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    // 放到视口外而不是 display:none —— 隐藏元素无法被选中，复制会静默失败
    textarea.style.position = 'fixed'
    textarea.style.left = '-9999px'
    textarea.setAttribute('readonly', '')
    document.body.appendChild(textarea)
    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    return ok
  } catch (error) {
    return false
  }
}
