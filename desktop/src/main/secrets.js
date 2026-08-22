'use strict';

/**
 * Secret-at-rest handling for connection profiles.
 *
 * Secrets (SSH key passphrases and SSH passwords) are encrypted with Electron's
 * safeStorage, which is backed by the OS keychain: Keychain on macOS, DPAPI on
 * Windows, libsecret/kwallet on Linux. The ciphertext is stored base64 in
 * profiles.json alongside the profile; the key never leaves the OS store.
 *
 * On a Linux box with no secret service available safeStorage falls back to a
 * weak "basic text" backend. We refuse to pretend that is encryption: in that
 * case nothing is written to disk and the secret lives in memory for the
 * session only, and the launcher tells the user why it will ask again.
 */

const { safeStorage } = require('electron');
const log = require('./logger');

// profileId:field -> plaintext, for the session-only fallback path.
const memory = new Map();

function available() {
  try {
    if (!safeStorage.isEncryptionAvailable()) return false;
    // Linux: "basic_text" means XOR-with-a-constant, not encryption.
    if (typeof safeStorage.getSelectedStorageBackend === 'function') {
      const backend = safeStorage.getSelectedStorageBackend();
      if (backend === 'basic_text' || backend === 'unknown') return false;
    }
    return true;
  } catch {
    return false;
  }
}

function memKey(profileId, field) {
  return `${profileId}:${field}`;
}

/**
 * @returns {{ stored: boolean, cipher: string|null }} `stored:false` means the
 * value is held in memory only and must be re-entered next launch.
 */
function seal(profileId, field, plaintext) {
  if (!plaintext) {
    memory.delete(memKey(profileId, field));
    return { stored: false, cipher: null };
  }
  if (!available()) {
    memory.set(memKey(profileId, field), plaintext);
    log.warn('secrets', 'OS keychain unavailable; secret kept in memory only', {
      profileId,
      field,
    });
    return { stored: false, cipher: null };
  }
  memory.set(memKey(profileId, field), plaintext);
  return {
    stored: true,
    cipher: safeStorage.encryptString(plaintext).toString('base64'),
  };
}

function open(profileId, field, cipher) {
  const cached = memory.get(memKey(profileId, field));
  if (cached) return cached;
  if (!cipher || !available()) return null;
  try {
    const plaintext = safeStorage.decryptString(Buffer.from(cipher, 'base64'));
    memory.set(memKey(profileId, field), plaintext);
    return plaintext;
  } catch (err) {
    log.error('secrets', 'failed to decrypt stored secret', {
      profileId,
      field,
      message: err.message,
    });
    return null;
  }
}

function forget(profileId) {
  for (const key of [...memory.keys()]) {
    if (key.startsWith(`${profileId}:`)) memory.delete(key);
  }
}

module.exports = { available, seal, open, forget };
