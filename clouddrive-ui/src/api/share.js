import request from './request'
export { extractShare, extractToken } from '../shareParser.js'

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
