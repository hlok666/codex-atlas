import assert from 'node:assert/strict'
import test from 'node:test'

import { floatingMessageCommandArgs } from '../src/lib/atlasBridge.ts'
import { FloatingSessionTargetLock } from '../src/lib/floatingSessionTarget.ts'
import { appendFloatingReply, normalizeFloatingReply, splitFloatingReply } from '../src/lib/floatingReply.ts'

test('floating message IPC nests the request under the Rust command argument', () => {
  const attachments = [{ kind: 'image' as const, name: 'screen.png', path: 'C:\\tmp\\screen.png' }]

  assert.deepEqual(
    floatingMessageCommandArgs('thread-1', 'continue', 'queue', attachments),
    {
      request: {
        sessionId: 'thread-1',
        input: 'continue',
        mode: 'queue',
        attachments,
      },
    },
  )
})

test('floating input keeps its session target until the editor closes', () => {
  const target = new FloatingSessionTargetLock()

  assert.equal(target.selectAutomatically('thread-a'), 'thread-a')
  assert.equal(target.openInput(), 'thread-a')
  assert.equal(target.selectAutomatically('thread-b'), 'thread-a')
  assert.equal(target.submissionTarget(), 'thread-a')

  target.closeInput()
  assert.equal(target.selectAutomatically('thread-b'), 'thread-b')
  assert.equal(target.submissionTarget(), 'thread-b')
})

test('manual session switching updates an open floating input target', () => {
  const target = new FloatingSessionTargetLock()

  target.selectAutomatically('thread-a')
  target.openInput()
  assert.equal(target.selectManually('thread-c'), 'thread-c')
  assert.equal(target.submissionTarget(), 'thread-c')
})

test('floating replies keep line breaks and do not use the old preview clamp', () => {
  const reply = `first line\nsecond line\n${'x'.repeat(600)}`

  assert.equal(normalizeFloatingReply(reply), reply)
  assert.equal(splitFloatingReply(reply, 700).join('\n\n'), reply)
})

test('idle floating reply pages preserve the complete response in order', () => {
  const reply = ['one', 'two', 'three'].map((part) => `${part} ${'content '.repeat(80)}`).join('\n\n')
  const normalized = normalizeFloatingReply(reply)
  const pages = splitFloatingReply(reply, 180)

  assert.ok(pages.length > 1)
  assert.equal(pages.join(' ').replace(/\s+/g, ' '), normalized.replace(/\s+/g, ' '))
})

test('streaming reply deltas accumulate without duplicating a replayed prefix', () => {
  let reply = ''
  reply = appendFloatingReply(reply, 'Hello ')
  reply = appendFloatingReply(reply, 'world')
  reply = appendFloatingReply(reply, 'Hello world')

  assert.equal(reply, 'Hello world')
})
