/**
 * bridge.js — thin wrapper over Interconnect.
 *
 * Request protocol (watch -> phone):
 * {
 *   id:      string,   // unique request ID (to match the reply)
 *   action:  string,   // 'get_states' | 'call_service' | 'send_ir'
 *   payload: object,   // depends on action
 * }
 *
 * Reply protocol (phone -> watch):
 * {
 *   id:      string,   // same ID
 *   ok:      boolean,
 *   data:    object,   // result (for get_states — an array of {entity_id, state, attributes})
 *   error:   string,   // present when ok=false
 * }
 */

import interconnect from '@system.interconnect';

const TIMEOUT_MS = 12000;

let _conn       = null;
let _pending    = {};   // id -> { resolve, reject, timer }
let _connected  = false;
let _onConnect  = null; // callback fired when the connection is established
let _lastError  = '';   // last channel error
let _opened     = false; // onopen has fired at least once
let _gotMsg     = false; // onmessage has fired at least once
let _inited     = false; // _init() has been called
let _lastSend   = '';    // result of the last send attempt

function _uid() {
  return Math.random().toString(36).slice(2, 9);
}

function _init() {
  if (_conn) return;
  _inited = true;
  // Official API is interconnect.instance(); keep getInstance() as a fallback
  // for older firmware.
  _conn = (typeof interconnect.instance === 'function')
    ? interconnect.instance()
    : interconnect.getInstance();

  _conn.onopen = () => {
    _opened = true;
    _connected = true;
    if (_onConnect) _onConnect();
  };

  _conn.onclose = () => {
    _connected = false;
    _conn = null;
    // reject all pending requests
    Object.values(_pending).forEach(p => {
      clearTimeout(p.timer);
      p.reject(new Error('Connection closed'));
    });
    _pending = {};
  };

  _conn.onmessage = (evt) => {
    _gotMsg = true;
    let msg;
    try { msg = typeof evt.data === 'string' ? JSON.parse(evt.data) : evt.data; }
    catch (e) { return; }

    const p = _pending[msg.id];
    if (!p) return;
    clearTimeout(p.timer);
    delete _pending[msg.id];
    if (msg.ok) p.resolve(msg.data);
    else        p.reject(new Error(msg.error || 'Unknown error'));
  };

  _conn.onerror = (e) => {
    _connected = false;
    _lastError = (e && (e.data || e.code)) ? ('err ' + (e.code || '') + ' ' + (e.data || '')) : 'error';
  };
}

/**
 * Real channel state (diagnosis() is unreliable on Band 8 Pro).
 * open = did interconnect open, msg = did a reply arrive, err = last error.
 */
export function channelState() {
  if (!_inited) return 'no channel';
  return 'open=' + (_opened ? 'Y' : 'N')
    + ' conn=' + (_connected ? 'Y' : 'N')
    + ' msg=' + (_gotMsg ? 'Y' : 'N')
    + (_lastSend ? ' send[' + _lastSend + ']' : '')
    + (_lastError ? ' [' + _lastError + ']' : '');
}

/**
 * Diagnose the watch<->phone channel.
 * Returns status: 0 = ok, 204 = timeout, 1001 = paired app not found
 * (often a signature/package mismatch), 1000 = other.
 */
export function diagnose(timeout = 8000) {
  _init();
  return new Promise((resolve) => {
    try {
      _conn.diagnosis({
        timeout,
        success: (d) => resolve(d && typeof d.status === 'number' ? d.status : -1),
        fail:    (d, code) => resolve(typeof code === 'number' ? code : -1),
      });
    } catch (e) {
      resolve(-2);
    }
  });
}

/**
 * Send a request and return a Promise that resolves with the reply.
 */
export function request(action, payload = {}) {
  _init();
  return new Promise((resolve, reject) => {
    const id  = _uid();

    const timer = setTimeout(() => {
      delete _pending[id];
      reject(new Error('Timeout (last error: ' + (_lastSend || '—') + ')'));
    }, TIMEOUT_MS);

    _pending[id] = { resolve, reject, timer };

    // IMPORTANT: data must be an OBJECT, not a string (a string => code=202 invalid data).
    // The connection comes up lazily: the first send()s may return 401 not connected —
    // retry every 500ms until the channel is up (or the timeout fires).
    const trySend = (attempt) => {
      if (!_pending[id]) return; // reply already arrived via onmessage
      _conn.send({
        data: { id, action, payload },
        fail: (d, code) => {
          const c = (code !== undefined && code !== null) ? code
                    : (d && d.code !== undefined ? d.code : null);
          const txt = (d && (d.data || d.msg)) || '';
          _lastSend = 'code=' + c + ' ' + txt + ' #' + (attempt + 1);
          const notConnected = c === 401 || /not connect/i.test(txt);
          if (notConnected && attempt < 22 && _pending[id]) {
            setTimeout(() => trySend(attempt + 1), 500);  // channel still coming up
            return;
          }
          if (!_pending[id]) return;
          clearTimeout(timer);
          delete _pending[id];
          reject(new Error('send fail ' + _lastSend));
        },
      });
    };

    trySend(0);
  });
}

// ─── Convenience helpers ──────────────────────────────────────────────────────

export function getStates(entityIds) {
  return request('get_states', { entity_ids: entityIds });
}

export function callService(domain, service, data) {
  return request('call_service', { domain, service, data });
}

export function sendIR(command) {
  return request('send_ir', { command });
}
