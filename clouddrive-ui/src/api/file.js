import request from './request'

/* ---------- 目录与文件元数据 ---------- */

export const listEntries = () => request.get('/entries')

export const getEntry = (id) => request.get(`/entries/${id}`)

export const createEntry = (payload) => request.post('/entries', payload)

export const renameEntry = (id, displayName) =>
  request.put(`/entries/${id}/name`, { displayName })

export const deleteEntry = (id) => request.delete(`/entries/${id}`)

/* ---------- 回收站 ---------- */

export const listTrash = (page = 0, size = 20) =>
  request.get('/entries/trash', { params: { page, size } })

export const restoreEntry = (id) => request.post(`/entries/trash/${id}/restore`)

export const purgeTrash = () => request.delete('/entries/trash')

/* ---------- 上传 ---------- */

/**
 * 普通上传。
 * 注意不要手工设置 Content-Type：浏览器需要自己生成带 boundary 的
 * multipart/form-data 头，手工写死会让后端解析不出分隔符。
 */
export const uploadFile = (file, displayName, parentId, onProgress) => {
  const form = new FormData()
  form.append('file', file)
  form.append('displayName', displayName)
  if (parentId) {
    form.append('parentId', parentId)
  }
  return request.post('/files/upload', form, {
    onUploadProgress: (event) => {
      if (onProgress && event.total) {
        onProgress(Math.round((event.loaded * 100) / event.total))
      }
    }
  })
}

/** 秒传：只报 MD5，不传文件体。未命中时后端返回 404。 */
export const instantUpload = (md5, displayName, parentId) =>
  request.post('/files/instant', { md5, displayName, parentId: parentId || null })

/* ---------- 下载 ---------- */

/**
 * 下载本人文件。
 *
 * 为什么不能直接用 <a href> ：下载接口要求 Authorization 头，
 * 而浏览器发起的普通导航请求带不上自定义头。
 * 所以走 axios 拿到 blob，再用 ObjectURL 触发保存。
 */
export const downloadFile = (id) =>
  request.get(`/files/${id}/download`, { responseType: 'blob' })
