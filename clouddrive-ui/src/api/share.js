import request from './request'

/* ---------- 所有者视角 ---------- */

export const createShare = (documentId, passcode, expiresAt) =>
  request.post('/shares', { documentId, passcode, expiresAt: expiresAt || null })

export const listShares = () => request.get('/shares')

export const revokeShare = (token) => request.delete(`/shares/${token}`)

/**
 * 重置提取码。
 * token 不变，所以已经发出去的链接继续可用，只是旧提取码立即失效。
 */
export const resetPasscode = (token, passcode) =>
  request.put(`/shares/${token}/passcode`, { passcode })

/* ---------- 接收者视角 ---------- */

/** 匿名查看分享的文件信息（下载前先让人知道这是什么文件） */
export const getShareInfo = (token, passcode) =>
  request.get(`/shares/${token}/info`, { params: { passcode } })

/** 转存到自己的网盘，需要登录 */
export const saveShare = (token, passcode, parentId, displayName) =>
  request.post(`/shares/${token}/save`, {
    passcode,
    parentId: parentId || null,
    displayName: displayName || null
  })

/**
 * 分享文件的直链。
 * 这个接口是匿名的（提取码就是凭证），可以直接用普通链接打开。
 */
export const sharedDownloadUrl = (token, passcode) =>
  `/api/shares/${token}/download?passcode=${encodeURIComponent(passcode)}`

/* ---------- 链接与文本的生成、解析 ---------- */

/** 普通分享页链接（对方需要自己输提取码） */
export const shareLinkOf = (token) => `${window.location.origin}/s/${token}`

/**
 * 免填链接：把提取码放进查询参数，对方点开就直接看到文件。
 *
 * 方便是有代价的——提取码会进入浏览器历史、可能出现在 Referer 头和服务端访问日志里。
 * 所以界面上把两种链接都给出来，由用户自己决定用哪种，而不是替他做主。
 */
export const autoFillLinkOf = (token, passcode) =>
  `${shareLinkOf(token)}?p=${encodeURIComponent(passcode)}`

/** 供"复制链接和提取码"用的一段文本，格式固定，接收端能反解析出来 */
export const shareTextOf = (token, passcode) =>
  `链接：${shareLinkOf(token)} 提取码：${passcode}`

/**
 * 从用户粘贴的任意内容里解析出 token 和提取码。
 *
 * 这是"一键粘贴"的关键：用户可能粘进来的是
 *   - 完整的一段话：链接：http://host/s/abc123 提取码：1234
 *   - 免填链接：    http://host/s/abc123?p=1234
 *   - 只有链接：    http://host/s/abc123
 *   - 只有分享码：  abc123
 *   - 分享码和提取码分两行 / 用空格隔开
 * 与其要求用户"必须按某种格式粘贴"——那种要求在实际使用中一定会被违反——
 * 不如在这里把常见形态都认下来。
 *
 * @returns {{token: string, passcode: string}} 解析不出来的部分返回空串
 */
export function extractShare(input) {
  const text = String(input || '').trim()
  if (!text) {
    return { token: '', passcode: '' }
  }

  let token = ''
  let passcode = ''

  // 1) 先找链接里的 token：/s/xxxx
  const fromUrl = text.match(/\/s\/([A-Za-z0-9_-]{8,})/)
  if (fromUrl) {
    token = fromUrl[1]
  }

  // 2) 免填链接里的提取码：?p=xxxx 或 &p=xxxx
  const fromQuery = text.match(/[?&]p=([^&\s]+)/)
  if (fromQuery) {
    passcode = decodeURIComponent(fromQuery[1])
  }

  // 3) "提取码：1234" / "密码: 1234" / "pwd=1234" 这类写法
  if (!passcode) {
    const labeled = text.match(/(?:提取码|访问码|密码|pwd|code)\s*[:：=]?\s*([A-Za-z0-9]{4,32})/i)
    if (labeled) {
      passcode = labeled[1]
    }
  }

  // 4) 没有链接时，把第一段长串当 token
  if (!token) {
    const bare = text.match(/[A-Za-z0-9_-]{8,}/)
    if (bare) {
      token = bare[0]
    }
  }

  // 5) 仍然没有提取码：如果输入里除了 token 之外只剩一段短字符串，就认为它是提取码
  if (!passcode && token) {
    const rest = text.replace(token, ' ').replace(/https?:\/\/\S+/g, ' ')
    const candidate = rest.match(/\b[A-Za-z0-9]{4,32}\b/)
    if (candidate) {
      passcode = candidate[0]
    }
  }

  return { token, passcode }
}

/** 旧名保留，内部改调 extractShare，避免其它地方引用失效 */
export function extractToken(input) {
  return extractShare(input).token
}
