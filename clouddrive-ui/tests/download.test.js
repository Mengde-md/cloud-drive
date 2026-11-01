import assert from 'node:assert/strict'
import test from 'node:test'
import { saveBlob } from '../src/utils.js'

for (const failure of ['none', 'url', 'create', 'append', 'click']) {
  test(`cleans up download resources after ${failure}`, (t) => {
    const error = new Error('download setup failed')
    const blob = new Blob(['文件正文'])
    const activeUrls = new Set()
    const attached = new Set()
    const downloads = []
    const revoked = []
    const link = {
      click() {
        if (failure === 'click') throw error
        assert.equal(attached.has(link), true)
        assert.equal(activeUrls.has(link.href), true)
        downloads.push(link.download)
      },
      remove() { attached.delete(link) }
    }
    const globals = {
      window: { URL: {
        createObjectURL(value) {
          assert.equal(value, blob)
          if (failure === 'url') throw error
          activeUrls.add('blob:test-download')
          return 'blob:test-download'
        },
        revokeObjectURL(url) { revoked.push(url); activeUrls.delete(url) }
      } },
      document: {
        createElement(tag) {
          assert.equal(tag, 'a')
          if (failure === 'create') throw error
          return link
        },
        body: {
          appendChild(element) {
            if (failure === 'append') throw error
            attached.add(element)
          },
          removeChild(element) { attached.delete(element) }
        }
      }
    }
    for (const [name, value] of Object.entries(globals)) {
      const original = Object.getOwnPropertyDescriptor(globalThis, name)
      Object.defineProperty(globalThis, name, { configurable: true, value })
      t.after(() => {
        if (original) Object.defineProperty(globalThis, name, original)
        else delete globalThis[name]
      })
    }
    if (failure === 'none') saveBlob(blob, '下载文件.txt')
    else assert.throws(() => saveBlob(blob, '下载文件.txt'), (caught) => caught === error)

    assert.deepEqual(downloads, failure === 'none' ? ['下载文件.txt'] : [])
    assert.equal(attached.size, 0)
    assert.equal(activeUrls.size, 0)
    assert.deepEqual(revoked, failure === 'url' ? [] : ['blob:test-download'])
  })
}
