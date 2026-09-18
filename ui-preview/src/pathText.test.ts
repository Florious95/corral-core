import assert from 'node:assert/strict'
import test from 'node:test'
import { truncatePath } from './pathText.ts'

test('keeps short paths intact', () => {
  assert.equal(truncatePath('/short'), '/short')
})

test('keeps root segment and last segment', () => {
  assert.equal(
    truncatePath('/Volumes/nvme/Projects/远程Agent安卓'),
    '/Volumes/nvme/…/远程Agent安卓',
  )
  assert.equal(
    truncatePath('/Users/alauda/Documents/前沿探索/多agent协作'),
    '/Users/alauda/…/多agent协作',
  )
})
