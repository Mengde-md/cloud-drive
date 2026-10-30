import assert from 'node:assert/strict'
import test from 'node:test'
import { extractShare, extractToken } from '../src/shareParser.js'

const token = 'Share_123456'
const link = `https://drive.example/s/${token}`
const relativeLink = `/s/${token}`

test('returns empty fields for missing input and accepts a bare share token', () => {
  for (const input of ['', ' \n ', null, undefined]) {
    assert.deepEqual(extractShare(input), { token: '', passcode: '' })
  }
  assert.deepEqual(extractShare(token), { token, passcode: '' })
  assert.deepEqual(extractShare(link), { token, passcode: '' })
  assert.deepEqual(extractShare(relativeLink), { token, passcode: '' })
  assert.equal(extractToken(link), token)
})

test('parses links, labeled passcodes and separate unlabelled passcodes', () => {
  for (const input of [
    `${token} 2468`,
    `${token}\n2468`,
    `链接：${link} 提取码：2468`,
    `${link} pwd=2468`,
    `${link} 2468`,
    `${relativeLink} 2468`
  ]) {
    assert.deepEqual(extractShare(input), { token, passcode: '2468' }, input)
  }
})

test('decodes passcodes in query parameters', () => {
  for (const input of [
    `${link}?p=2468`,
    `${relativeLink}?p=%32%34%36%38`,
    `${link}?source=share&p=%32%34%36%38&view=preview`
  ]) {
    assert.deepEqual(extractShare(input), { token, passcode: '2468' }, input)
  }
})

test('reads passcodes only from the share URL query, excluding fragments', () => {
  for (const base of [link, relativeLink]) {
    assert.deepEqual(extractShare(`${base}?p=2468#preview`), { token, passcode: '2468' })
    assert.deepEqual(extractShare(`${base}#preview?p=9999`), { token, passcode: '' })
    assert.deepEqual(extractShare(`${base}#preview?p=9999 提取码：2468`), {
      token, passcode: '2468'
    })
    assert.deepEqual(extractShare(`${base}?p=12%2334#preview`), { token, passcode: '12#34' })
    assert.deepEqual(extractShare(`https://other.example/?p=9999 ${base}?p=2468`), {
      token, passcode: '2468'
    })
  }
})

test('keeps the share token when a query passcode has malformed encoding', () => {
  for (const base of [link, relativeLink]) {
    for (const passcode of ['%', '%ZZ', '%E0%A4%A', '%broken']) {
      assert.deepEqual(extractShare(`${base}?p=${passcode}`), { token, passcode: '' })
    }
  }
})

test('uses a separate passcode if the query passcode cannot be decoded', () => {
  for (const base of [link, relativeLink]) {
    assert.deepEqual(extractShare(`${base}?p=%ZZ 提取码：2468`), { token, passcode: '2468' })
    assert.deepEqual(extractShare(`${base}?p=%ZZ 2468`), { token, passcode: '2468' })
  }
})

test('does not guess a passcode from unrelated URL parameters', () => {
  for (const base of [link, relativeLink]) {
    assert.deepEqual(extractShare(`${base}?tracking=campaign2026`), { token, passcode: '' })
    assert.deepEqual(extractShare(`${base}?code=2468`), { token, passcode: '' })
    assert.deepEqual(extractShare(`${base}?pwd=2468`), { token, passcode: '' })
    assert.deepEqual(extractShare(`${base}?tracking=campaign2026 2468`), { token, passcode: '2468' })
  }
})
