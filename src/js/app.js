/**
 * WA Recovery Pro — Real Message & Media Recovery Controller
 * Fully wired to native Android NotificationListenerService & SQLite database.
 * No dummy data. Real WhatsApp notification recovery.
 */

import db from './database.js';
import {
  renderMessageItem, renderChatItem, renderMessageBubble,
  renderVoiceItem, renderMediaItem, showToast, showModal,
  formatTime, formatFileSize, getInitials, getAvatarColor
} from './ui-components.js';
import mediaManager from './media-manager.js';
import voiceOptions from './voice-options.js';
import exportManager from './export-manager.js';
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
          const el1 = document.getElementById('stat-messages'); if (el1) el1.textContent = stats.totalMessages || 0;
          const el2 = document.getElementById('stat-deleted'); if (el2) el2.textContent = stats.deletedRecovered || 0;
          const el3 = document.getElementById('stat-media'); if (el3) el3.textContent = stats.totalMedia || 0;
          const el4 = document.getElementById('stat-voice'); if (el4) el4.textContent = stats.totalVoiceNotes || 0;
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
              thumbnailBase64: m.thumbnail || m.thumbnail_base64 || m.media_url,
              mediaThumbnail: m.thumbnail || m.thumbnail_base64 || m.media_url,
              mediaUrl: m.media_url || m.thumbnail
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

  openDirectImage(src, contact = 'Recovered Photo') {
    if (!src) return;
    const lightbox = document.getElementById('media-lightbox');
    const image = document.getElementById('lightbox-image');
    const video = document.getElementById('lightbox-video');
    const sender = document.getElementById('lightbox-sender');
    const date = document.getElementById('lightbox-date');

    if (!lightbox || !image) return;

    if (video) {
      video.style.display = 'none';
      video.pause();
    }
    image.style.display = 'block';
    image.src = src;
    if (sender) sender.textContent = contact;
    if (date) date.textContent = 'Recovered Photo';

    mediaManager._currentMedia = { url: src, contact: contact, filename: `wa_photo_${Date.now()}.jpg` };
    lightbox.classList.remove('hidden');
    document.body.style.overflow = 'hidden';
  }

  // =============================================
  // FIRST-LAUNCH ONBOARDING & REAL PERMISSIONS
  // =============================================

  async _checkFirstLaunch() {
    const isGranted = await this._checkPermissionsStatus();
    const completed = localStorage.getItem('wa_onboarding_completed');
    
    // Only show onboarding wizard on initial install if user has not completed or closed it yet
    if (!isGranted && completed !== 'true') {
      this.showOnboarding();
    } else if (isGranted) {
      const banner = document.getElementById('perm-warning-banner');
      if (banner) banner.classList.add('hidden');
    }
  }

  showOnboarding() {
    const modal = document.getElementById('onboarding-modal');
    if (modal) {
      modal.classList.remove('hidden');
      modal.style.display = 'flex';
    }
    this._checkPermissionsStatus();
    this._startPermPolling();
  }

  hideOnboarding() {
    const modal = document.getElementById('onboarding-modal');
    if (modal) {
      modal.classList.add('hidden');
      modal.style.display = 'none';
    }
    this._stopPermPolling();
  }

  _startPermPolling() {
    this._stopPermPolling();
    this._permPollTimer = setInterval(async () => {
      const modal = document.getElementById('onboarding-modal');
      if (modal && !modal.classList.contains('hidden')) {
        await this._checkPermissionsStatus(true);
      } else {
        this._stopPermPolling();
      }
    }, 1200);
  }

  _stopPermPolling() {
    if (this._permPollTimer) {
      clearInterval(this._permPollTimer);
      this._permPollTimer = null;
    }
  }

  _initOnboarding() {
    const btnNotif = document.getElementById('btn-grant-notification');
    const btnStorage = document.getElementById('btn-grant-storage');
    const btnBattery = document.getElementById('btn-grant-battery');
    const btnDone = document.getElementById('btn-onboarding-done');
    const btnRefresh = document.getElementById('btn-onboarding-refresh');
    const btnClose = document.getElementById('btn-onboarding-close');
    const btnBanner = document.getElementById('btn-banner-grant');

    if (btnNotif) {
      btnNotif.addEventListener('click', () => this.openNotificationSettings());
    }
    if (btnStorage) {
      btnStorage.addEventListener('click', () => this.openStorageSettings());
    }
    if (btnBattery) {
      btnBattery.addEventListener('click', () => this.openBatterySettings());
    }
    if (btnRefresh) {
      btnRefresh.addEventListener('click', async () => {
        showToast('Checking permission status...', 'info', 1000);
        const granted = await this._checkPermissionsStatus();
        if (granted) {
          showToast('✅ Notification Access is Active & Enabled!', 'success', 3000);
        } else {
          showToast('⚠️ Notification Access is still disabled in Settings.', 'error', 3000);
        }
      });
    }
    if (btnClose) {
      btnClose.addEventListener('click', () => {
        localStorage.setItem('wa_onboarding_completed', 'true');
        this.hideOnboarding();
      });
    }
    if (btnBanner) {
      btnBanner.addEventListener('click', () => {
        this.openNotificationSettings();
      });
    }
    if (btnDone) {
      btnDone.addEventListener('click', async () => {
        localStorage.setItem('wa_onboarding_completed', 'true');
        this.hideOnboarding();
        const notifGranted = await this._checkPermissionsStatus();
        if (!notifGranted) {
          showToast('⚠️ Tip: Grant Notification Access to start recovering messages.', 'info', 4000);
        } else {
          showToast('✅ Recovery Service active!', 'success', 3000);
        }
        this.loadDashboard();
      });
    }

    // Setting Wizard re-run item
    const rerunWizard = document.getElementById('setting-onboarding-rerun');
    if (rerunWizard) {
      rerunWizard.addEventListener('click', () => this.showOnboarding());
    }
  }

  getBridge() {
    return window.Capacitor?.Plugins?.RecoveryBridge || this.bridge;
  }

  async openNotificationSettings() {
    const bridge = this.getBridge();
    showToast('Opening Android Notification Access Settings...', 'info', 2000);
    this._startPermPolling();
    if (bridge) {
      try {
        await bridge.openNotificationSettings();
      } catch (e) {
        try {
          await bridge.openAppSettings();
        } catch (ignored) {}
      }
    } else {
      console.warn('Native RecoveryBridge not connected.');
      showToast('Please open Settings > Apps > WA Recovery Pro > Permissions', 'info', 4000);
    }
  }

  async openStorageSettings() {
    const bridge = this.getBridge();
    showToast('Opening Storage Access Settings...', 'info', 2000);
    this._startPermPolling();
    if (bridge) {
      try {
        await bridge.openStorageSettings();
      } catch (e) {
        try {
          await bridge.openAppSettings();
        } catch (ignored) {}
      }
    } else {
      console.warn('Native RecoveryBridge not connected.');
      showToast('Please open Settings > Apps > WA Recovery Pro > Permissions', 'info', 4000);
    }
  }

  async openBatterySettings() {
    const bridge = this.getBridge();
    showToast('Opening Battery Optimization Settings...', 'info', 2000);
    this._startPermPolling();
    if (bridge) {
      try {
        await bridge.openBatterySettings();
      } catch (e) {
        try {
          await bridge.openAppSettings();
        } catch (ignored) {}
      }
    } else {
      console.warn('Native RecoveryBridge not connected.');
      showToast('Please open Settings > Apps > WA Recovery Pro > Battery', 'info', 4000);
    }
  }

  async _checkPermissionsStatus(isPolling = false) {
    if (this.bridge) {
      try {
        let notifEnabled = false;
        let storageGranted = false;
        let batteryIgnored = false;

        try {
          const all = await this.bridge.getAllPermissionsStatus();
          if (all) {
            notifEnabled = !!all.notification;
            storageGranted = !!all.storage;
            batteryIgnored = !!all.battery;
          }
        } catch (e) {
          const status = await this.bridge.isNotificationAccessEnabled();
          notifEnabled = !!(status && status.enabled);
          try {
            const st = await this.bridge.isStoragePermissionGranted();
            storageGranted = !!(st && st.granted);
          } catch (ignored) {}
        }

        // Notification Access UI Update
        if (notifEnabled) {
          this._markPermGranted('notif');
        } else {
          this._markPermPending('notif');
        }

        // Storage Access UI Update
        if (storageGranted) {
          this._markPermGranted('storage');
        } else {
          this._markPermPending('storage');
        }

        // Battery Optimization UI Update
        if (batteryIgnored) {
          this._markPermGranted('battery');
        } else {
          this._markPermPending('battery');
        }

        // Dashboard Badges & Banner
        const banner = document.getElementById('perm-warning-banner');
        const badgeNls = document.getElementById('badge-nls');
        const serviceDot = document.querySelector('#service-status .status-dot');
        const serviceText = document.querySelector('#service-status .status-text');

        if (notifEnabled) {
          if (banner) banner.classList.add('hidden');
          if (badgeNls) {
            badgeNls.textContent = 'Running';
            badgeNls.className = 'badge badge-active';
          }
          if (serviceDot) serviceDot.className = 'status-dot active';
          if (serviceText) serviceText.textContent = 'Active';
          return true;
        } else {
          if (banner) banner.classList.remove('hidden');
          if (badgeNls) {
            badgeNls.textContent = 'Setup Needed';
            badgeNls.className = 'badge badge-warning';
          }
          if (serviceDot) serviceDot.className = 'status-dot';
          if (serviceText) serviceText.textContent = 'Permission Required';
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
    const btnDone = document.getElementById('btn-onboarding-done');

    if (badge) {
      badge.textContent = 'Granted';
      badge.className = 'perm-status-badge granted';
    }
    if (card) card.classList.add('granted');
    if (btn) {
      btn.innerHTML = '<span class="material-icons-round">check_circle</span> Enabled on Device';
      btn.classList.add('granted');
    }
    if (type === 'notif' && btnDone) {
      btnDone.innerHTML = '<span class="material-icons-round">verified</span> Start Recovering Messages';
      btnDone.style.background = 'linear-gradient(135deg, var(--green-primary), var(--green-dark))';
    }
  }

  _markPermPending(type) {
    const badge = document.getElementById(`badge-perm-${type}`);
    const card = document.getElementById(`perm-step-${type}`);
    const btn = document.getElementById(`btn-grant-${type === 'notif' ? 'notification' : type}`);

    if (badge) {
      if (type === 'battery') {
        badge.textContent = 'Recommended';
        badge.className = 'perm-status-badge optional';
      } else {
        badge.textContent = 'Required';
        badge.className = 'perm-status-badge pending';
      }
    }
    if (card) card.classList.remove('granted');
    if (btn) {
      const labels = {
        notif: 'Allow Notification Access',
        storage: 'Allow Storage Access',
        battery: 'Disable Battery Limits'
      };
      const icons = {
        notif: 'lock_open',
        storage: 'folder',
        battery: 'power_settings_new'
      };
      btn.innerHTML = `<span class="material-icons-round">${icons[type] || 'settings'}</span> ${labels[type] || 'Grant Permission'}`;
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

    const s1 = document.getElementById('stat-messages'); if (s1) s1.textContent = stats.totalMessages || 0;
    const s2 = document.getElementById('stat-deleted'); if (s2) s2.textContent = stats.deletedRecovered || 0;
    const s3 = document.getElementById('stat-media'); if (s3) s3.textContent = stats.totalMedia || 0;
    const s4 = document.getElementById('stat-voice'); if (s4) s4.textContent = stats.totalVoiceNotes || 0;

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
    exportManager.openExportModal(this.currentChat);
  }

  shareMessage(messageId) {
    exportManager.shareSingleMessage(messageId);
  }

  copyMessage(messageId) {
    exportManager.copyMessageText(messageId);
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

  async playVoice(voiceId) {
    const playerEl = document.querySelector(`[data-voice-id="${voiceId}"] .voice-play-btn`);
    if (!playerEl) return;

    const icon = playerEl.querySelector('.material-icons-round');
    const waveform = playerEl.closest('.voice-player')?.querySelector('.voice-waveform');
    const bars = waveform ? waveform.querySelectorAll('.wave-bar') : [];
    const speed = this.voiceSpeeds[voiceId] || 1.0;

    if (this.audioPlayers[voiceId]) {
      if (this.bridge) {
        try { await this.bridge.stopVoiceNote(); } catch (e) {}
      }
      if (this.audioPlayers[voiceId].pause) this.audioPlayers[voiceId].pause();
      delete this.audioPlayers[voiceId];
      if (icon) icon.textContent = 'play_arrow';
      bars.forEach(b => b.classList.remove('active'));
      return;
    }

    // Stop any other currently playing audio
    Object.keys(this.audioPlayers).forEach(key => {
      if (this.audioPlayers[key] && this.audioPlayers[key].pause) this.audioPlayers[key].pause();
      delete this.audioPlayers[key];
    });
    if (this.bridge) {
      try { await this.bridge.stopVoiceNote(); } catch (e) {}
    }
    document.querySelectorAll('.voice-play-btn .material-icons-round').forEach(i => i.textContent = 'play_arrow');
    document.querySelectorAll('.wave-bar').forEach(b => b.classList.remove('active'));

    const voiceItem = document.querySelector(`[data-voice-id="${voiceId}"]`);
    const audioSrc = voiceItem ? voiceItem.dataset.audioUrl : null;

    if (icon) icon.textContent = 'pause';

    let barIndex = 0;
    const intervalTime = Math.max(50, Math.round(150 / speed));
    const animInterval = setInterval(() => {
      if (barIndex >= bars.length) barIndex = 0;
      bars.forEach(b => b.classList.remove('active'));
      if (bars[barIndex]) bars[barIndex].classList.add('active');
      barIndex++;
    }, intervalTime);

    // 1. Play with native hardware audio player on Android
    if (this.bridge && window.Capacitor?.isNativePlatform()) {
      try {
        await this.bridge.playVoiceNote({ path: audioSrc, speed: speed });
        this.audioPlayers[voiceId] = {
          pause: async () => {
            clearInterval(animInterval);
            try { await this.bridge.stopVoiceNote(); } catch (e) {}
          }
        };
        return;
      } catch (err) {
        console.log('Native player fallback:', err);
      }
    }

    // 2. Web fallback
    if (audioSrc && audioSrc !== 'null' && audioSrc !== 'undefined') {
      try {
        const audio = new Audio(audioSrc);
        audio.playbackRate = speed;
        audio.play();
        audio.onended = () => {
          clearInterval(animInterval);
          if (icon) icon.textContent = 'play_arrow';
          bars.forEach(b => b.classList.remove('active'));
          delete this.audioPlayers[voiceId];
        };
        this.audioPlayers[voiceId] = {
          pause: () => { audio.pause(); clearInterval(animInterval); }
        };
      } catch (e) {
        clearInterval(animInterval);
        if (icon) icon.textContent = 'play_arrow';
        showToast('Could not play audio file', 'error');
      }
    } else {
      if (this.bridge) {
        try {
          await this.bridge.scanVoiceNotes();
          await this.bridge.playVoiceNote({ speed: speed });
        } catch (e) {}
      }
    }
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

    const simulatorBtn = document.getElementById('setting-run-simulator');
    if (simulatorBtn) {
      simulatorBtn.addEventListener('click', () => this.runTestSimulator());
    }

    if (enableBtn) {
      enableBtn.addEventListener('click', () => this.showOnboarding());
    }

    // Floating Capture Assistant Toggle & Dashboard Launch Button
    const toggleFloating = document.getElementById('toggle-floating-assistant');
    const btnLaunchFloating = document.getElementById('btn-toggle-floating-assistant');

    const handleToggleFloating = async (shouldStart) => {
      if (!this.bridge) {
        showToast(shouldStart ? '🎈 Floating Spy Bubble active! Open WhatsApp to capture' : 'Floating Assistant stopped', 'info');
        if (btnLaunchFloating) {
          btnLaunchFloating.innerHTML = shouldStart
            ? '<span class="material-icons-round">stop</span> Stop Floating Bubble'
            : '<span class="material-icons-round">play_arrow</span> Launch Floating Bubble';
          btnLaunchFloating.classList.toggle('btn-active', shouldStart);
        }
        return;
      }
      try {
        if (shouldStart) {
          const perm = await this.bridge.checkOverlayPermission();
          if (!perm || !perm.granted) {
            showToast('⚠️ Please allow "Display over other apps" permission', 'warning', 3000);
            await this.bridge.requestOverlayPermission();
            if (toggleFloating) toggleFloating.checked = false;
            return;
          }
          const res = await this.bridge.startFloatingAssistant();
          if (res && res.success) {
            showToast('🎈 Floating Spy Bubble is active! Open WhatsApp to capture', 'success', 3500);
            if (toggleFloating) toggleFloating.checked = true;
            if (btnLaunchFloating) {
              btnLaunchFloating.innerHTML = '<span class="material-icons-round">stop</span> Stop Floating Bubble';
              btnLaunchFloating.classList.add('btn-active');
            }
          }
        } else {
          await this.bridge.stopFloatingAssistant();
          showToast('Floating Assistant stopped', 'info');
          if (toggleFloating) toggleFloating.checked = false;
          if (btnLaunchFloating) {
            btnLaunchFloating.innerHTML = '<span class="material-icons-round">play_arrow</span> Launch Floating Bubble';
            btnLaunchFloating.classList.remove('btn-active');
          }
        }
      } catch (e) {
        showToast('Assistant error: ' + e, 'error');
      }
    };

    if (toggleFloating) {
      toggleFloating.addEventListener('change', (e) => handleToggleFloating(e.target.checked));
    }
    if (btnLaunchFloating) {
      btnLaunchFloating.addEventListener('click', () => {
        const isRunning = btnLaunchFloating.classList.contains('btn-active');
        handleToggleFloating(!isRunning);
      });
    }
  }

  async runTestSimulator() {
    showToast('🧪 Running Recovery Simulator...', 'info', 1500);

    // 1. Simulate a realistic deleted WhatsApp message
    await db.addMessage({
      contact: 'Sarah Jenkins',
      text: 'Hey! Are we still meeting for dinner tonight? Let me know ASAP! 🍕',
      timestamp: Date.now() - 30000,
      isDeleted: true,
      direction: 'received',
      type: 'text'
    });

    // 2. Simulate a realistic recovered voice note
    await db.addMessage({
      contact: 'Omar Al-Hassan',
      text: 'Voice note (0:14)',
      timestamp: Date.now() - 120000,
      isDeleted: true,
      direction: 'received',
      type: 'voice'
    });

    await db.addVoiceNote({
      contact: 'Omar Al-Hassan',
      duration: '0:14',
      timestamp: Date.now() - 120000,
      isDeleted: true,
      audioUrl: null
    });

    // 3. Update stats and UI
    await this.syncNativeData();
    showToast('✅ Test Recovery Complete! Check Messages & Voice tabs.', 'success', 3500);
    this.navigateTo('messages');
  }

  async loadSettings() {
    const storage = await mediaManager.getStorageUsage();
    const settingsStorage = document.getElementById('settings-storage');
    if (settingsStorage) {
      settingsStorage.textContent = `${storage.totalFiles} files · ${storage.formattedSize}`;
    }

    const versionLabel = document.getElementById('app-version-label');
    if (versionLabel) {
      versionLabel.textContent = `${autoUpdater.currentVersion} (Build ${autoUpdater.currentBuild || 10})`;
    }

    const versionHeader = document.getElementById('settings-version-header') || document.querySelector('.settings-version');
    if (versionHeader) {
      versionHeader.textContent = `Version ${autoUpdater.currentVersion} (Build ${autoUpdater.currentBuild || 10})`;
    }

    const versionBadge = document.getElementById('settings-version-badge');
    if (versionBadge) {
      versionBadge.textContent = `v${autoUpdater.currentVersion}`;
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
