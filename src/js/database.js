/**
 * WA Recovery Pro — IndexedDB Database Layer
 * Handles all local storage of recovered messages, media, voice notes, and view-once captures.
 */

const DB_NAME = 'wa_recovery_pro';
const DB_VERSION = 1;

class RecoveryDatabase {
  constructor() {
    this.db = null;
    this._ready = this._init();
  }

  async _init() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);

      request.onupgradeneeded = (event) => {
        const db = event.target.result;

        // Messages store
        if (!db.objectStoreNames.contains('messages')) {
          const msgStore = db.createObjectStore('messages', { keyPath: 'id', autoIncrement: true });
          msgStore.createIndex('contact', 'contact', { unique: false });
          msgStore.createIndex('timestamp', 'timestamp', { unique: false });
          msgStore.createIndex('isDeleted', 'isDeleted', { unique: false });
          msgStore.createIndex('type', 'type', { unique: false });
          msgStore.createIndex('isViewOnce', 'isViewOnce', { unique: false });
        }

        // Media store
        if (!db.objectStoreNames.contains('media')) {
          const mediaStore = db.createObjectStore('media', { keyPath: 'id', autoIncrement: true });
          mediaStore.createIndex('contact', 'contact', { unique: false });
          mediaStore.createIndex('timestamp', 'timestamp', { unique: false });
          mediaStore.createIndex('mediaType', 'mediaType', { unique: false });
          mediaStore.createIndex('isDeleted', 'isDeleted', { unique: false });
        }

        // Voice notes store
        if (!db.objectStoreNames.contains('voiceNotes')) {
          const voiceStore = db.createObjectStore('voiceNotes', { keyPath: 'id', autoIncrement: true });
          voiceStore.createIndex('contact', 'contact', { unique: false });
          voiceStore.createIndex('timestamp', 'timestamp', { unique: false });
        }

        // Contacts store
        if (!db.objectStoreNames.contains('contacts')) {
          const contactStore = db.createObjectStore('contacts', { keyPath: 'name' });
          contactStore.createIndex('lastActive', 'lastActive', { unique: false });
        }

        // Settings store
        if (!db.objectStoreNames.contains('settings')) {
          db.createObjectStore('settings', { keyPath: 'key' });
        }
      };

      request.onsuccess = (event) => {
        this.db = event.target.result;
        resolve(this.db);
      };

      request.onerror = (event) => {
        console.error('Database error:', event.target.error);
        reject(event.target.error);
      };
    });
  }

  async ready() {
    await this._ready;
    return this;
  }

  // ---- Messages ----

  async addMessage(message) {
    await this._ready;
    const msg = {
      contact: message.contact || 'Unknown',
      text: message.text || '',
      timestamp: message.timestamp || Date.now(),
      isDeleted: message.isDeleted || false,
      isViewOnce: message.isViewOnce || false,
      type: message.type || 'text', // text, image, video, voice, document, sticker
      mediaUrl: message.mediaUrl || null,
      mediaThumbnail: message.mediaThumbnail || null,
      direction: message.direction || 'received', // sent, received
      groupName: message.groupName || null,
      raw: message.raw || null
    };
    return this._add('messages', msg);
  }

  async getMessages(filter = {}) {
    await this._ready;
    let messages = await this._getAll('messages');
    
    if (filter.contact) {
      messages = messages.filter(m => m.contact === filter.contact);
    }
    if (filter.isDeleted !== undefined) {
      messages = messages.filter(m => m.isDeleted === filter.isDeleted);
    }
    if (filter.isViewOnce !== undefined) {
      messages = messages.filter(m => m.isViewOnce === filter.isViewOnce);
    }
    if (filter.type) {
      messages = messages.filter(m => m.type === filter.type);
    }
    if (filter.search) {
      const query = filter.search.toLowerCase();
      messages = messages.filter(m => 
        (m.text && m.text.toLowerCase().includes(query)) ||
        (m.contact && m.contact.toLowerCase().includes(query))
      );
    }

    return messages.sort((a, b) => b.timestamp - a.timestamp);
  }

  async getMessagesByContact(contact) {
    await this._ready;
    const messages = await this._getAll('messages');
    return messages
      .filter(m => m.contact === contact)
      .sort((a, b) => a.timestamp - b.timestamp);
  }

  async markAsDeleted(messageId) {
    await this._ready;
    const msg = await this._get('messages', messageId);
    if (msg) {
      msg.isDeleted = true;
      msg.deletedAt = Date.now();
      await this._put('messages', msg);
    }
  }

  // ---- Contacts (aggregated) ----

  async getContacts() {
    await this._ready;
    const messages = await this._getAll('messages');
    const contactMap = new Map();

    for (const msg of messages) {
      const existing = contactMap.get(msg.contact);
      if (!existing || msg.timestamp > existing.lastActive) {
        const contactMessages = messages.filter(m => m.contact === msg.contact);
        const deletedCount = contactMessages.filter(m => m.isDeleted).length;
        contactMap.set(msg.contact, {
          name: msg.contact,
          lastMessage: msg.text || (msg.type === 'voice' ? '🎤 Voice message' : '📎 Media'),
          lastActive: msg.timestamp,
          messageCount: contactMessages.length,
          deletedCount: deletedCount,
          hasDeleted: deletedCount > 0
        });
      }
    }

    return Array.from(contactMap.values()).sort((a, b) => b.lastActive - a.lastActive);
  }

  // ---- Media ----

  async addMedia(media) {
    await this._ready;
    const item = {
      contact: media.contact || 'Unknown',
      mediaType: media.mediaType || 'image', // image, video, document, sticker, gif
      url: media.url || '',
      thumbnail: media.thumbnail || '',
      filename: media.filename || '',
      size: media.size || 0,
      timestamp: media.timestamp || Date.now(),
      isDeleted: media.isDeleted || false,
      mimeType: media.mimeType || ''
    };
    return this._add('media', item);
  }

  async getMedia(filter = {}) {
    await this._ready;
    let media = await this._getAll('media');

    if (filter.mediaType) {
      media = media.filter(m => m.mediaType === filter.mediaType);
    }
    if (filter.contact) {
      media = media.filter(m => m.contact === filter.contact);
    }
    if (filter.isDeleted !== undefined) {
      media = media.filter(m => m.isDeleted === filter.isDeleted);
    }

    return media.sort((a, b) => b.timestamp - a.timestamp);
  }

  // ---- Voice Notes ----

  async addVoiceNote(voice) {
    await this._ready;
    const item = {
      contact: voice.contact || 'Unknown',
      url: voice.url || '',
      duration: voice.duration || 0,
      waveform: voice.waveform || [],
      timestamp: voice.timestamp || Date.now(),
      isDeleted: voice.isDeleted || false,
      isPlayed: false
    };
    return this._add('voiceNotes', item);
  }

  async getVoiceNotes(filter = {}) {
    await this._ready;
    let notes = await this._getAll('voiceNotes');

    if (filter.contact) {
      notes = notes.filter(n => n.contact === filter.contact);
    }

    return notes.sort((a, b) => b.timestamp - a.timestamp);
  }

  // ---- Statistics ----

  async getStats() {
    await this._ready;
    const messages = await this._getAll('messages');
    const media = await this._getAll('media');
    const voiceNotes = await this._getAll('voiceNotes');

    return {
      totalMessages: messages.length,
      deletedRecovered: messages.filter(m => m.isDeleted).length,
      viewOnceCaptures: messages.filter(m => m.isViewOnce).length,
      totalMedia: media.length,
      totalVoiceNotes: voiceNotes.length,
      totalPhotos: media.filter(m => m.mediaType === 'image').length,
      totalVideos: media.filter(m => m.mediaType === 'video').length,
      totalDocuments: media.filter(m => m.mediaType === 'document').length,
    };
  }

  // ---- Settings ----

  async getSetting(key, defaultValue = null) {
    await this._ready;
    const record = await this._get('settings', key);
    return record ? record.value : defaultValue;
  }

  async setSetting(key, value) {
    await this._ready;
    return this._put('settings', { key, value });
  }

  // ---- Export ----

  async exportAllData() {
    await this._ready;
    const data = {
      exportDate: new Date().toISOString(),
      messages: await this._getAll('messages'),
      media: await this._getAll('media'),
      voiceNotes: await this._getAll('voiceNotes'),
    };
    return data;
  }

  // ---- Clear ----

  async clearAll() {
    await this._ready;
    await this._clear('messages');
    await this._clear('media');
    await this._clear('voiceNotes');
    await this._clear('contacts');
  }

  // ---- Internal Helpers ----

  _add(storeName, data) {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction(storeName, 'readwrite');
      const store = tx.objectStore(storeName);
      const request = store.add(data);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }

  _put(storeName, data) {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction(storeName, 'readwrite');
      const store = tx.objectStore(storeName);
      const request = store.put(data);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }

  _get(storeName, key) {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction(storeName, 'readonly');
      const store = tx.objectStore(storeName);
      const request = store.get(key);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }

  _getAll(storeName) {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction(storeName, 'readonly');
      const store = tx.objectStore(storeName);
      const request = store.getAll();
      request.onsuccess = () => resolve(request.result || []);
      request.onerror = () => reject(request.error);
    });
  }

  _clear(storeName) {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction(storeName, 'readwrite');
      const store = tx.objectStore(storeName);
      const request = store.clear();
      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  }
}

// Singleton export
const db = new RecoveryDatabase();
export default db;
