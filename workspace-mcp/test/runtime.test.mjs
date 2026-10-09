import assert from 'node:assert/strict';
import { test } from 'node:test';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { runClaude, wakePrompt } from '../src/runtime.mjs';

const binding = {
  agentId: '550e8400-e29b-41d4-a716-446655440000',
  workspaceId: '550e8400-e29b-41d4-a716-446655440001', cwd: process.cwd()
};

test('Claude wake resumes the agent session without bypassing permission settings', async () => {
  let args;
  const fakeSpawn = (_command, values, options) => {
    args = values;
    assert.equal(options.cwd, binding.cwd);
    assert.equal(options.shell, false);
    const child = new EventEmitter();
    child.stdout = new PassThrough();
    child.stderr = new PassThrough();
    child.kill = () => {};
    setImmediate(() => {
      child.stdout.end(JSON.stringify({ session_id: 'new-session', is_error: false }));
      child.stderr.end();
      child.emit('close', 0);
    });
    return child;
  };
  const session = await runClaude(binding, [{ id: 'msg-1' }], 'previous-session', {
    spawn: fakeSpawn, command: 'fake-claude'
  });
  assert.equal(session, 'new-session');
  assert.deepEqual(args.slice(0, 5), ['-p', '--output-format', 'json', '--resume', 'previous-session']);
  assert.equal(args.some(arg => arg.includes('dangerously-skip-permissions')), false);
  assert.match(wakePrompt(binding, [{ id: 'msg-1' }]), /msg-1/);
});
