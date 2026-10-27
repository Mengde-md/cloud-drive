import assert from 'node:assert/strict'
import test from 'node:test'
import { formatBytes } from '../src/utils.js'

test('formats byte counts across supported units', () => {
  const cases = [
    [0, '0 B'],
    [1, '1 B'],
    [1023, '1023 B'],
    [1024, '1.0 KB'],
    [1536, '1.5 KB'],
    [1024 ** 2, '1.0 MB'],
    [3.5 * 1024 ** 3, '3.5 GB'],
    [1024 ** 4, '1.0 TB'],
    [1024 ** 5, '1.0 PB'],
    [1024 ** 6, '1.0 EB']
  ]
  for (const [bytes, expected] of cases) assert.equal(formatBytes(bytes), expected)
})

test('uses a placeholder for missing or invalid byte counts', () => {
  for (const value of [null, undefined, -1, NaN, Infinity, -Infinity, '', '0', '1024', false, {}, [], 1n]) {
    assert.equal(formatBytes(value), '-', `invalid input: ${String(value)}`)
  }
})

test('rounds fractional bytes without choosing an invalid unit', () => {
  assert.equal(formatBytes(0.25), '0 B')
  assert.equal(formatBytes(0.5), '1 B')
  assert.equal(formatBytes(1.5), '2 B')
})

test('keeps the largest unit defined for unusually large finite values', () => {
  const formatted = formatBytes(Number.MAX_VALUE)
  assert.match(formatted, / EB$/)
  assert.doesNotMatch(formatted, /NaN|undefined|Infinity/)
})
