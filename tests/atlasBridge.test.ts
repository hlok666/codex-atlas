import assert from 'node:assert/strict'
import test from 'node:test'

import { floatingMessageCommandArgs } from '../src/lib/atlasBridge.ts'

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
