/**
 * 解析分享链接、分享码，或附带提取码的粘贴文本。
 * @returns {{token: string, passcode: string}} 无法解析的部分返回空串
 */
export function extractShare(input) {
  const text = String(input || '').trim()
  if (!text) return { token: '', passcode: '' }

  let token = ''
  let passcode = ''

  const fromUrl = text.match(/\/s\/([A-Za-z0-9_-]{8,})\S*/)
  if (fromUrl) token = fromUrl[1]

  // 只读取当前分享链接的查询参数；锚点和其他链接不能提供提取码。
  const query = fromUrl?.[0].match(/^[^?#]*\?([^#]*)/)
  const fromQuery = query?.[1].match(/(?:^|&)p=([^&\s]+)/)
  if (fromQuery) {
    try {
      passcode = decodeURIComponent(fromQuery[1])
    } catch {
      // 损坏的百分号编码不应中断粘贴；继续尝试独立的提取码。
      passcode = ''
    }
  }

  // 独立提取码只从链接外读取，URL 的其他参数不参与猜测。
  const withoutLinks = text.replace(/https?:\/\/\S+|\/s\/[A-Za-z0-9_-]{8,}\S*/gi, ' ')
  if (!passcode) {
    const labeled = withoutLinks.match(/(?:提取码|访问码|密码|pwd|code)\s*[:：=]?\s*([A-Za-z0-9]{4,32})/i)
    if (labeled) passcode = labeled[1]
  }

  if (!token) {
    const bare = text.match(/[A-Za-z0-9_-]{8,}/)
    if (bare) token = bare[0]
  }

  if (!passcode && token) {
    // 先移除完整链接，避免替换 token 后把查询参数留下来当成提取码。
    const rest = withoutLinks.replace(token, ' ')
    const candidate = rest.match(/\b[A-Za-z0-9]{4,32}\b/)
    if (candidate) passcode = candidate[0]
  }

  return { token, passcode }
}

/** 保留仅提取分享码的旧接口。 */
export function extractToken(input) {
  return extractShare(input).token
}
