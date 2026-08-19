/**
 * WA Recovery Pro — Real Message & Media Recovery Controller
 * Fully wired to native Android NotificationListenerService & SQLite database.
 * No dummy data. Real WhatsApp notification recovery.
 */

import db from './database.js';
import {
  renderMessageItem, renderChatItem, renderMessageBubble,
  renderVoiceItem, renderMediaItem, showToast, showModal,
  animateCounter, formatTime, formatFileSize, getInitials, getAvatarColor
} from './ui-components.js';
import mediaManager from './media-manager.js';
import voiceOptions from './voice-options.js';
import autoUpdater from './auto-updater.js';

class WARecoveryApp {
  constructor() {
    this.currentPage = 'dashboard';
    this.currentFilter = 'all';
    this.currentMediaTab = 'photos';
    this.currentChat = null;
    this.audioPlayers = {};
    this.isNative = false;
    this.voiceSpeeds = {};
    this.bridge = null;
  }

  async init() {
    // Check if running on Android native device
    this.isNative = !!(window.Capacitor && window.Capacitor.isNativePlatform());
    this.bridge = window.Capacitor?.Plugins?.RecoveryBridge;
    
    // Initialize local database
    await db.ready();

    // Setup UI components
    this._initNavigation();
    this._initSearch();
    this._initFilters();
    this._initMediaTabs();
    this._initSettings();
    this._initChatDetail();
    this._initOnboarding();
    this._initHeaderButtons();

    // Listen for native events (new WhatsApp messages / deleted messages)
    if (this.bridge) {
      try {
        this.bridge.addListener('newMessage', (event) => {
          console.log('📬 Native new WhatsApp message received:', event);
          this.syncNativeData();
        });
        this.bridge.addListener('messageDeleted', (event) => {
          console.log('🗑️ Native WhatsApp message deleted by sender:', event);
          showToast(`Recovered deleted message from ${event.data || 'sender'}!`, 'info', 4000);
          this.syncNativeData();
        });
      } catch (e) {
        console.log('Bridge listener error:', e);
      }
    }

    // Load initial real data
    await this.syncNativeData();

    // Hide splash screen
    setTimeout(() => {
      const splash = document.getElementById('splash-screen');
      const app = document.getElementById('app');
      if (splash) {
        splash.classList.add('fade-out');
        setTimeout(() => splash.remove(), 600);
      }
      if (app) app.classList.remove('hidden');

      // Check onboarding / permission status
      this._checkFirstLaunch();

      // Check for updates after 3s
      setTimeout(() => {
        autoUpdater.checkForUpdates(true);
      }, 3000);
    }, 1200);

    // Re-check permissions whenever user returns to the app from System Settings
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        this._checkPermissionsStatus();
        this.syncNativeData();
      }
    });

    window.WAApp = this;
    console.log('🛡️ WA Recovery Pro active (Native Mode:', this.isNative, ')');
  }

  // =============================================
  // DATA SYNC (Native SQLite -> Web UI)
  // =============================================

  async syncNativeData() {
    if (this.bridge) {
      try {
        const stats = await this.bridge.getStats();
        if (stats) {
          animateCounter(document.getElementById('stat-messages'), stats.totalMessages || 0);
          animateCounter(document.getElementById('stat-deleted'), stats.deletedRecovered || 0);
          animateCounter(document.getElementById('stat-media'), stats.totalMedia || 0);
          animateCounter(document.getElementById('stat-voice'), stats.totalVoiceNotes || 0);
        }

        const msgResult = await this.bridge.getMessages({ filter: 'all' });
        if (msgResult && msgResult.messages) {
          const nativeMessages = JSON.parse(msgResult.messages);
          for (const m of nativeMessages) {
            await db.addMessage({
              contact: m.contact,
              text: m.text,
              type: m.type,
              timestamp: m.timestamp,
              isDeleted: !!m.is_deleted,
              direction: m.direction || 'received',
              groupName: m.group_name,
              isViewOnce: !!m.is_view_once,
              thumbnailBase64: m.thumbnail_base64
            });
          }
        }
      } catch (err) {
        console.log('Sync error:', err);
      }
    }

    // Refresh current page view
    if (this.currentPage === 'dashboard') await this.loadDashboard();
    else if (this.currentPage === 'messages') await this.loadMessages();
    else if (this.currentPage === 'media') await this.loadMediaGrid();
    else if (this.currentPage === 'voice') await this.loadVoiceNotes();
  }

  // =============================================
  // FIRST-LAUNCH ONBOARDING & REAL PERMISSIONS
  // =============================================

  async _checkFirstLaunch() {
    const isGranted = await this._checkPermissionsStatus();
    const completed = localStorage.getItem('wa_onboarding_completed');
    if (!completed || !isGranted) {
      this.showOnboarding();
    }
  }

  showOnboarding() {
    const modal = document.getElementById('onboarding-modal');
    if (modal) modal.classList.remove('hidden');
    this._checkPermissionsStatus();
  }

  _initOnboarding() {
    const btnNotif = document.getElementById('btn-grant-notification');
    const btnStorage = document.getElementById('btn-grant-storage');
    const btnBattery = document.getElementById('btn-grant-battery');
    const btnDone = document.getElementById('btn-onboarding-done');

    if (btnNotif) {
      btnNotif.addEventListener('click', () => this.openNotificationSettings());
    }
    if (btnStorage) {
      btnStorage.addEventListener('click', () => this.openStorageSettings());
    }
    if (btnBattery) {
      btnBattery.addEventListener('click', () => this.openBatterySettings());
    }
    if (btnDone) {
      btnDone.addEventListener('click', async () => {
        const notifGranted = await this._checkPermissionsStatus();
        if (!notifGranted && this.isNative) {
          showToast('Please enable Notification Access in settings first!', 'error', 3000);
          this.openNotificationSettings();
          return;
        }
        localStorage.setItem('wa_onboarding_completed', 'true');
        const modal = document.getElementById('onboarding-modal');
        if (modal) modal.classList.add('hidden');
        showToast('Recovery Service active! Waiting for messages...', 'success');
        this.loadDashboard();
      });
    }
  }

  async openNotificationSettings() {
    showToast('Opening Android Notification Settings...', 'info', 2000);
    if (this.bridge) {
      try {
        await this.bridge.openNotificationSettings();
      } catch (e) {
        this.bridge.openAppSettings();
      }
    } else {
      setTimeout(() => this._markPermGranted('notif'), 1000);
    }
  }

  async openStorageSettings() {
    showToast('Opening Storage Access Settings...', 'info', 2000);
    if (this.bridge) {
      try {
        await this.bridge.openStorageSettings();
      } catch (e) {
        this.bridge.openAppSettings();
      }
    } else {
      setTimeout(() => this._markPermGranted('storage'), 1000);
    }
  }

  async openBatterySettings() {
    showToast('Opening Battery Settings...', 'info', 2000);
    if (this.bridge) {
      try {
        await this.bridge.openBatterySettings();
      } catch (e) {
        this.bridge.openAppSettings();
      }
    } else {
      setTimeout(() => this._markPermGranted('battery'), 1000);
    }
  }

  async _checkPermissionsStatus() {
    if (this.bridge) {
      try {
        const status = await this.bridge.isNotificationAccessEnabled();
        if (status && status.enabled) {
          this._markPermGranted('notif');
          const badgeNls = document.getElementById('badge-nls');
          if (badgeNls) {
            badgeNls.textContent = 'Running';
            badgeNls.className = 'badge badge-active';
          }
          return true;
        } else {
          this._markPermPending('notif');
          const badgeNls = document.getElementById('badge-nls');
          if (badgeNls) {
            badgeNls.textContent = 'Disabled';
            badgeNls.className = 'badge badge-warning';
          }
          return false;
        }
      } catch (e) {
        console.log('Perm check error:', e);
      }
    }
    return false;
  }

  _markPermGranted(type) {
    const badge = document.getElementById(`badge-perm-${type}`);
    const card = document.getElementById(`perm-step-${type}`);
    const btn = document.getElementById(`btn-grant-${type === 'notif' ? 'notification' : type}`);

    if (badge) {
      badge.textContent = 'Granted';
      badge.className = 'perm-status-badge granted';
    }
    if (card) card.classList.add('granted');
    if (btn) {
      btn.innerHTML = '<span class="material-icons-round">check_circle</span> Enabled on System';
      btn.classList.add('granted');
    }
  }

  _markPermPending(type) {
    const badge = document.getElementById(`badge-perm-${type}`);
    const card = document.getElementById(`perm-step-${type}`);
    const btn = document.getElementById(`btn-grant-${type === 'notif' ? 'notification' : type}`);

    if (badge) {
      badge.textContent = 'Required';
      badge.className = 'perm-status-badge pending';
    }
    if (card) card.classList.remove('granted');
    if (btn) {
      btn.innerHTML = '<span class="material-icons-round">lock_open</span> Allow Notification Access';
      btn.classList.remove('granted');
    }
  }

  // =============================================
  // VOICE & SPEED CONTROLS
  // =============================================

  openVoiceOptions(voiceId) {
    voiceOptions.openOptions(voiceId);
  }

  toggleVoiceSpeed(voiceId, btnEl) {
    const current = this.voiceSpeeds[voiceId] || 1.0;
    const speeds = [1.0, 1.25, 1.5, 2.0];
    const nextIdx = (speeds.indexOf(current) + 1) % speeds.length;
    const nextSpeed = speeds[nextIdx];
    this.voiceSpeeds[voiceId] = nextSpeed;

    if (btnEl) btnEl.textContent = `${nextSpeed}x`;
    showToast(`Speed: ${nextSpeed}x`, 'info', 1200);
  }

  shareVoice(voiceId) {
    voiceOptions.openOptions(voiceId);
  }

  transcribeVoice(voiceId) {
    voiceOptions.openOptions(voiceId);
    setTimeout(() => voiceOptions.transcribeVoice(), 200);
  }

  // =============================================
  // NAVIGATION
  // =============================================

  _initNavigation() {
    document.querySelectorAll('.nav-item').forEach(item => {
      item.addEventListener('click', () => {
        this.navigateTo(item.dataset.page);
      });
    });

    const viewAllBtn = document.getElementById('btn-view-all-messages');
    if (viewAllBtn) {
      viewAllBtn.addEventListener('click', () => this.navigateTo('messages'));
    }
  }

  _initHeaderButtons() {
    const btnUpdates = document.getElementById('btn-check-updates');
    if (btnUpdates) {
      btnUpdates.addEventListener('click', () => {
        autoUpdater.checkForUpdates(false);
      });
    }
  }

  navigateTo(page) {
    document.querySelectorAll('.nav-item').forEach(item => {
      item.classList.toggle('active', item.dataset.page === page);
    });

    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    const pageEl = document.getElementById(`page-${page}`);
    if (pageEl) pageEl.classList.add('active');

    this.currentPage = page;

    switch (page) {
      case 'dashboard': this.loadDashboard(); break;
      case 'messages': this.loadMessages(); break;
      case 'media': this.loadMediaGrid(); break;
      case 'voice': this.loadVoiceNotes(); break;
      case 'settings': this.loadSettings(); break;
    }
  }

  // =============================================
  // DASHBOARD
  // =============================================

  async loadDashboard() {
    const stats = await db.getStats();

    animateCounter(document.getElementById('stat-messages'), stats.totalMessages);
    animateCounter(document.getElementById('stat-deleted'), stats.deletedRecovered);
    animateCounter(document.getElementById('stat-media'), stats.totalMedia);
    animateCounter(document.getElementById('stat-voice'), stats.totalVoiceNotes);

    const storage = await mediaManager.getStorageUsage();
    const storageEl = document.getElementById('storage-used');
    if (storageEl) storageEl.textContent = storage.formattedSize || '0 MB';

    const messages = await db.getMessages();
    const recentContainer = document.getElementById('recent-messages');
    const emptyContainer = document.getElementById('empty-recent');

    if (messages.length > 0) {
      recentContainer.innerHTML = messages.slice(0, 8).map(m => renderMessageItem(m)).join('');
      emptyContainer.classList.add('hidden');
      recentContainer.classList.remove('hidden');

      recentContainer.querySelectorAll('.message-item').forEach(item => {
        item.addEventListener('click', () => {
          const contact = item.dataset.contact;
          this.navigateTo('messages');
          setTimeout(() => this.openChat(contact), 100);
        });
      });
    } else {
      recentContainer.classList.add('hidden');
      emptyContainer.classList.remove('hidden');
    }
  }

  // =============================================
  // MESSAGES
  // =============================================

  _initFilters() {
    const chips = document.querySelectorAll('.filter-chips .chip');
    chips.forEach(chip => {
      chip.addEventListener('click', () => {
        chips.forEach(c => c.classList.remove('active'));
        chip.classList.add('active');
        this.currentFilter = chip.dataset.filter;
        this.loadMessages();
      });
    });
  }

  async loadMessages() {
    const contacts = await db.getContacts();
    const chatList = document.getElementById('chat-list');
    const emptyState = document.getElementById('empty-messages');

    if (contacts.length > 0) {
      let filtered = contacts;
      if (this.currentFilter === 'deleted') {
        filtered = contacts.filter(c => c.hasDeleted);
      }

      chatList.innerHTML = filtered.map(c => renderChatItem(c)).join('');
      chatList.classList.remove('hidden');
      emptyState.classList.add('hidden');

      chatList.querySelectorAll('.chat-item').forEach(item => {
        item.addEventListener('click', () => {
          this.openChat(item.dataset.contact);
        });
      });
    } else {
      chatList.classList.add('hidden');
      emptyState.classList.remove('hidden');
    }
  }

  // =============================================
  // CHAT DETAIL
  // =============================================

  _initChatDetail() {
    const backBtn = document.getElementById('btn-chat-back');
    if (backBtn) backBtn.addEventListener('click', () => this.closeChat());
    
    const exportBtn = document.getElementById('btn-chat-export');
    if (exportBtn) exportBtn.addEventListener('click', () => this.exportChat());
  }

  async openChat(contactName) {
    this.currentChat = contactName;
    const messages = await db.getMessagesByContact(contactName);
    
    const detail = document.getElementById('chat-detail');
    const nameEl = document.getElementById('chat-detail-name');
    const statusEl = document.getElementById('chat-detail-status');
    const avatarEl = document.getElementById('chat-detail-avatar');
    const messagesContainer = document.getElementById('chat-detail-messages');

    if (nameEl) nameEl.textContent = contactName;
    const deletedCount = messages.filter(m => m.isDeleted).length;
    if (statusEl) {
      statusEl.textContent = `${messages.length} messages${deletedCount > 0 ? ` · ${deletedCount} deleted recovered` : ''}`;
    }
    
    if (avatarEl) {
      avatarEl.textContent = getInitials(contactName);
      avatarEl.style.background = getAvatarColor(contactName);
    }

    if (messagesContainer) {
      messagesContainer.innerHTML = messages.map(m => renderMessageBubble(m)).join('');
    }
    if (detail) detail.classList.remove('hidden');

    setTimeout(() => {
      if (messagesContainer) messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }, 100);
  }

  closeChat() {
    const detail = document.getElementById('chat-detail');
    if (detail) {
      detail.classList.add('hidden');
      this.currentChat = null;
    }
  }

  async exportChat() {
    if (!this.currentChat) return;
    const messages = await db.getMessagesByContact(this.currentChat);
    let text = `Chat Export: ${this.currentChat}\nExported: ${new Date().toLocaleString()}\n${'='.repeat(40)}\n\n`;
    
    for (const msg of messages) {
      const time = new Date(msg.timestamp).toLocaleString();
      const deleted = msg.isDeleted ? ' [DELETED]' : '';
      const viewOnce = msg.isViewOnce ? ' [VIEW ONCE]' : '';
      text += `[${time}] ${msg.direction === 'sent' ? 'You' : msg.contact}${deleted}${viewOnce}: ${msg.text || `[${msg.type}]`}\n`;
    }

    const blob = new Blob([text], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `wa_chat_${this.currentChat.replace(/\s+/g, '_')}.txt`;
    a.click();
    URL.revokeObjectURL(url);
    showToast('Chat exported successfully', 'success');
  }

  // =============================================
  // SEARCH
  // =============================================

  _initSearch() {
    const searchBtn = document.getElementById('btn-search');
    const searchBar = document.getElementById('search-bar');
    const searchInput = document.getElementById('search-input');
    const searchClose = document.getElementById('search-close');

    if (searchBtn) {
      searchBtn.addEventListener('click', () => {
        searchBar.classList.toggle('hidden');
        if (!searchBar.classList.contains('hidden')) searchInput.focus();
      });
    }

    if (searchClose) {
      searchClose.addEventListener('click', () => {
        searchBar.classList.add('hidden');
        searchInput.value = '';
        this.loadMessages();
      });
    }

    if (searchInput) {
      let debounceTimer;
      searchInput.addEventListener('input', () => {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(() => this._onSearch(searchInput.value), 300);
      });
    }
  }

  async _onSearch(query) {
    if (!query.trim()) {
      this.loadMessages();
      return;
    }

    const messages = await db.getMessages({ search: query });
    this.navigateTo('messages');
    
    const chatList = document.getElementById('chat-list');
    const emptyState = document.getElementById('empty-messages');

    if (messages.length > 0) {
      chatList.innerHTML = messages.slice(0, 30).map(m => renderMessageItem(m)).join('');
      chatList.classList.remove('hidden');
      emptyState.classList.add('hidden');

      chatList.querySelectorAll('.message-item').forEach(item => {
        item.addEventListener('click', () => {
          this.openChat(item.dataset.contact);
        });
      });
    } else {
      chatList.innerHTML = '';
      emptyState.querySelector('.empty-title').textContent = 'No results found';
      emptyState.querySelector('.empty-desc').textContent = `No messages matching "${query}"`;
      emptyState.classList.remove('hidden');
    }
  }

  // =============================================
  // MEDIA & VOICE
  // =============================================

  _initMediaTabs() {
    const tabs = document.querySelectorAll('.sub-tab');
    tabs.forEach(tab => {
      tab.addEventListener('click', () => {
        tabs.forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        this.currentMediaTab = tab.dataset.media;
        this.loadMediaGrid();
      });
    });
  }

  async loadMediaGrid() {
    const media = await mediaManager.loadMedia(this.currentMediaTab);
    const grid = document.getElementById('media-grid');
    const emptyState = document.getElementById('empty-media');

    if (media.length > 0) {
      grid.innerHTML = media.map(m => renderMediaItem(m)).join('');
      grid.classList.remove('hidden');
      emptyState.classList.add('hidden');

      grid.querySelectorAll('.media-item').forEach(item => {
        item.addEventListener('click', () => {
          const id = parseInt(item.dataset.mediaId);
          mediaManager.openLightbox(id);
        });
      });
    } else {
      grid.innerHTML = '';
      grid.classList.add('hidden');
      emptyState.classList.remove('hidden');
    }
  }

  async loadVoiceNotes() {
    const notes = await db.getVoiceNotes();
    const list = document.getElementById('voice-list');
    const emptyState = document.getElementById('empty-voice');
    const countBadge = document.getElementById('voice-count');

    if (countBadge) countBadge.textContent = `${notes.length} notes`;

    if (notes.length > 0) {
      list.innerHTML = notes.map(v => renderVoiceItem(v)).join('');
      list.classList.remove('hidden');
      emptyState.classList.add('hidden');
    } else {
      list.innerHTML = '';
      list.classList.add('hidden');
      emptyState.classList.remove('hidden');
    }
  }

  playVoice(voiceId) {
    const playerEl = document.querySelector(`[data-voice-id="${voiceId}"] .voice-play-btn`);
    if (!playerEl) return;

    const icon = playerEl.querySelector('.material-icons-round');
    const waveform = playerEl.closest('.voice-player').querySelector('.voice-waveform');
    const timeEl = playerEl.closest('.voice-player').querySelector('.voice-time');
    const bars = waveform.querySelectorAll('.wave-bar');
    const speed = this.voiceSpeeds[voiceId] || 1.0;

    if (this.audioPlayers[voiceId]) {
      this.audioPlayers[voiceId].pause();
      delete this.audioPlayers[voiceId];
      icon.textContent = 'play_arrow';
      bars.forEach(b => b.classList.remove('active'));
      return;
    }

    Object.keys(this.audioPlayers).forEach(key => {
      this.audioPlayers[key].pause();
      delete this.audioPlayers[key];
    });
    document.querySelectorAll('.voice-play-btn .material-icons-round').forEach(i => i.textContent = 'play_arrow');
    document.querySelectorAll('.wave-bar').forEach(b => b.classList.remove('active'));

    icon.textContent = 'pause';
    let barIndex = 0;
    const intervalTime = Math.max(50, Math.round(150 / speed));

    const animInterval = setInterval(() => {
      if (barIndex >= bars.length) {
        clearInterval(animInterval);
        icon.textContent = 'play_arrow';
        bars.forEach(b => b.classList.remove('active'));
        delete this.audioPlayers[voiceId];
        return;
      }
      bars[barIndex].classList.add('active');
      barIndex++;
    }, intervalTime);

    this.audioPlayers[voiceId] = {
      pause: () => clearInterval(animInterval)
    };
  }

  // =============================================
  // SETTINGS
  // =============================================

  _initSettings() {
    const exportBtn = document.getElementById('setting-export');
    const clearBtn = document.getElementById('setting-clear');
    const enableBtn = document.getElementById('btn-enable-service');
    const checkUpdatesBtn = document.getElementById('setting-check-updates');
    const rerunOnboardingBtn = document.getElementById('setting-onboarding-rerun');

    if (checkUpdatesBtn) {
      checkUpdatesBtn.addEventListener('click', () => autoUpdater.checkForUpdates(false));
    }

    if (rerunOnboardingBtn) {
      rerunOnboardingBtn.addEventListener('click', () => this.showOnboarding());
    }

    if (exportBtn) {
      exportBtn.addEventListener('click', () => this.exportAllData());
    }

    if (clearBtn) {
      clearBtn.addEventListener('click', () => {
        showModal(
          'Clear All Data',
          'This will permanently delete all recovered messages and media saved by this app. Your real WhatsApp will NOT be touched.',
          async () => {
            await db.clearAll();
            if (this.bridge) {
              try { await this.bridge.clearAll(); } catch (e) {}
            }
            showToast('All recovery data cleared', 'success');
            this.loadDashboard();
          }
        );
      });
    }

    if (enableBtn) {
      enableBtn.addEventListener('click', () => this.showOnboarding());
    }
  }

  async loadSettings() {
    const storage = await mediaManager.getStorageUsage();
    const settingsStorage = document.getElementById('settings-storage');
    if (settingsStorage) {
      settingsStorage.textContent = `${storage.totalFiles} files · ${storage.formattedSize}`;
    }
  }

  async exportAllData() {
    try {
      const data = await db.exportAllData();
      const json = JSON.stringify(data, null, 2);
      const blob = new Blob([json], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `wa_recovery_backup_${new Date().toISOString().split('T')[0]}.json`;
      a.click();
      URL.revokeObjectURL(url);
      showToast('Data exported successfully', 'success');
    } catch (err) {
      showToast('Export failed', 'error');
    }
  }
}

const app = new WARecoveryApp();
document.addEventListener('DOMContentLoaded', () => app.init());

export default app;
