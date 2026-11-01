import assert from 'node:assert/strict'
import test from 'node:test'
import { copyText } from '../src/utils.js'

function browser(t, { clipboard, select, execCommand = () => true } = {}) {
  const attached = new Set()
  const textarea = {
    style: {},
    setAttribute() {},
    select: select || (() => {}),
    remove() { attached.delete(textarea) }
  }
  const globals = {
    navigator: { clipboard },
    window: { isSecureContext: true },
    document: {
      createElement: () => textarea,
      body: {
        appendChild: (element) => attached.add(element),
        removeChild: (element) => attached.delete(element)
      },
      execCommand
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
  return { attached, textarea }
}

for (const failure of ['none', 'denied', 'selection', 'copy']) {
  test(`cleans up the fallback textarea after ${failure}`, async (t) => {
    const { attached, textarea } = browser(t, {
      select() {
        if (failure === 'selection') throw new Error('selection failed')
      },
      execCommand(command) {
        assert.equal(command, 'copy')
        if (failure === 'copy') throw new Error('copy failed')
        return failure !== 'denied'
      }
    })
    assert.equal(await copyText('分享链接'), failure === 'none')
    assert.equal(textarea.value, '分享链接')
    assert.equal(attached.size, 0)
  })
}

test('falls back when the modern clipboard rejects access', async (t) => {
  let copies = 0
  const { attached } = browser(t, {
    clipboard: { writeText: async () => { throw new Error('permission denied') } },
    execCommand: () => { copies += 1; return true }
  })
  assert.equal(await copyText('分享链接'), true)
  assert.equal(copies, 1)
  assert.equal(attached.size, 0)
})
