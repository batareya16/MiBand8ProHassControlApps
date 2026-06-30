/**
 * bridge.js — thin wrapper over Interconnect (watch -> phone coordinator).
 *
 * This watch app is bound to a phone coordinator (com.elli.halights) via MiWearBridge.
 * Every request carries `src` = this app's package, so the coordinator knows which
 * watch app to reply to (it addresses the reply with the "@w:<src>" header).
 */
import interconnect from '@system.interconnect';

const TIMEOUT_MS = 12000;
const SRC = 'com.elli.plants';   // this watch app's package (== coordinator binding key)

let _conn       = null;
let _pending    = {};
let _connected  = false;
let _opened     = false;
let _gotMsg     = false;
let _inited     = false;
let _lastError  = '';
let _lastSend   = '';

function _uid() {
  return Math.random().toString(36).slice(2, 9);
}

function _init() {
  if (_conn) return;
  _inited = true;
  _conn = (typeof interconnect.instance === 'function')
    ? interconnect.instance()
    : interconnect.getInstance();

  _conn.onopen = () => { _opened = true; _connected = true; };
  _conn.onclose = () => {
    _connected = false; _conn = null;
    Object.values(_pending).forEach(p => { clearTimeout(p.timer); p.reject(new Error('Connection closed')); });
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

/** Real channel state (diagnosis() is unreliable on Band 8 Pro). */
export function channelState() {
  if (!_inited) return 'no channel';
  return 'open=' + (_opened ? 'Y' : 'N')
    + ' conn=' + (_connected ? 'Y' : 'N')
    + ' msg=' + (_gotMsg ? 'Y' : 'N')
    + (_lastSend ? ' send[' + _lastSend + ']' : '')
    + (_lastError ? ' [' + _lastError + ']' : '');
}

export function request(action, payload = {}) {
  _init();
  return new Promise((resolve, reject) => {
    const id = _uid();
    const timer = setTimeout(() => {
      delete _pending[id];
      reject(new Error('Timeout (last: ' + (_lastSend || '-') + ')'));
    }, TIMEOUT_MS);
    _pending[id] = { resolve, reject, timer };

    // data must be an OBJECT (string => code=202 invalid data). src lets the
    // coordinator route the reply back to this app.
    const trySend = (attempt) => {
      if (!_pending[id]) return;
      _conn.send({
        data: { id, action, payload, src: SRC },
        fail: (d, code) => {
          const c = (code !== undefined && code !== null) ? code
                    : (d && d.code !== undefined ? d.code : null);
          const txt = (d && (d.data || d.msg)) || '';
          _lastSend = 'code=' + c + ' ' + txt + ' #' + (attempt + 1);
          const notConnected = c === 401 || /not connect/i.test(txt);
          if (notConnected && attempt < 22 && _pending[id]) {
            setTimeout(() => trySend(attempt + 1), 500);
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

export function getStates(entityIds) {
  return request('get_states', { entity_ids: entityIds });
}
