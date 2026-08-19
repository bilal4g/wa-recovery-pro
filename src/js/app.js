/**
 * WA Recovery Pro — Main Application Controller
 * Handles navigation, data flow, demo data seeding, first-launch onboarding,
 * voice player speed/options, in-app auto-updater, and Capacitor bridge.
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
    this.voiceSpeeds = {}; // map of voiceId -> speed
  }

  async init() {
    // Check if running in Capacitor native context
    this.isNative = !!window.Capacitor?.isNativePlatform();
    
    // Initialize database
    await db.ready();

    // Seed demo data if database is empty
    const stats = await db.getStats();
    if (stats.totalMessages === 0) {
      await this._seedDemoData();
    }

    // Initialize UI
    this._initNavigation();
    this._initSearch();
    this._initFilters();
    this._initMediaTabs();
    this._initSettings();
    this._initChatDetail();
    this._initOnboarding();
    this._initHeaderButtons();

    // Load initial data
    await this.loadDashboard();

    // Hide splash, show app
    setTimeout(() => {
      const splash = document.getElementById('splash-screen');
      const app = document.getElementById('app');
      splash.classList.add('fade-out');
      app.classList.remove('hidden');
      setTimeout(() => splash.remove(), 600);

      // Check first launch onboarding
      this._checkFirstLaunch();

      // Check for updates in background after 3s
      setTimeout(() => {
        autoUpdater.checkForUpdates(true);
      }, 3000);
    }, 1800);

    // Listen for app resume to re-check permissions
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        this._checkPermissionsStatus();
      }
    });

    // Expose for inline event handlers
    window.WAApp = this;

    console.log('🛡️ WA Recovery Pro initialized with Voice Suite & Auto-Updater');
  }

  // =============================================
  // FIRST-LAUNCH ONBOARDING WIZARD
  // =============================================

  _checkFirstLaunch() {
    const completed = localStorage.getItem('wa_onboarding_completed');
    if (!completed) {
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
      btnDone.addEventListener('click', () => {
        localStorage.setItem('wa_onboarding_completed', 'true');
        const modal = document.getElementById('onboarding-modal');
        if (modal) modal.classList.add('hidden');
        showToast('Recovery Service active & ready!', 'success');
        this.loadDashboard();
      });
    }
  }

  openNotificationSettings() {
    if (this.isNative && window.Capacitor?.Plugins?.RecoveryBridge) {
      window.Capacitor.Plugins.RecoveryBridge.openNotificationSettings();
    } else {
      // Web simulation
      showToast('Opening System Notification Access Settings...', 'info');
      setTimeout(() => {
        this._markPermGranted('notif');
      }, 1500);
    }
  }

  openStorageSettings() {
    if (this.isNative && window.Capacitor?.Plugins?.RecoveryBridge) {
      window.Capacitor.Plugins.RecoveryBridge.openStorageSettings();
    } else {
      showToast('Opening System Storage & Files Access Settings...', 'info');
      setTimeout(() => {
        this._markPermGranted('storage');
      }, 1500);
    }
  }

  openBatterySettings() {
    if (this.isNative && window.Capacitor?.Plugins?.RecoveryBridge) {
      window.Capacitor.Plugins.RecoveryBridge.openBatterySettings();
    } else {
      showToast('Opening Battery Optimization Exemption...', 'info');
      setTimeout(() => {
        this._markPermGranted('battery');
      }, 1500);
    }
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
    showToast(`${type.toUpperCase()} permission granted!`, 'success');
  }

  async _checkPermissionsStatus() {
    if (this.isNative && window.Capacitor?.Plugins?.RecoveryBridge) {
      try {
        const status = await window.Capacitor.Plugins.RecoveryBridge.isNotificationAccessEnabled();
        if (status?.enabled) {
          this._markPermGranted('notif');
        }
      } catch (e) {
        console.log('Perm check error:', e);
      }
    }
  }

  // =============================================
  // VOICE PLAYBACK & OPTIONS
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

    if (btnEl) {
      btnEl.textContent = `${nextSpeed}x`;
    }
    showToast(`Speed: ${nextSpeed}x`, 'info', 1200);
  }

  shareVoice(voiceId) {
    voiceOptions.openOptions(voiceId);
  }

  transcribeVoice(voiceId) {
    voiceOptions.openOptions(voiceId);
    setTimeout(() => {
      voiceOptions.transcribeVoice();
    }, 200);
  }

  // =============================================
  // NAVIGATION
  // =============================================

  _initNavigation() {
    const navItems = document.querySelectorAll('.nav-item');
    navItems.forEach(item => {
      item.addEventListener('click', () => {
        const page = item.dataset.page;
        this.navigateTo(page);
      });
    });

    // View all messages shortcut from dashboard
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
    // Update active nav item
    document.querySelectorAll('.nav-item').forEach(item => {
      item.classList.toggle('active', item.dataset.page === page);
    });

    // Update active page
    document.querySelectorAll('.page').forEach(p => {
      p.classList.remove('active');
    });
    const pageEl = document.getElementById(`page-${page}`);
    if (pageEl) {
      pageEl.classList.add('active');
    }

    this.currentPage = page;

    // Load page data
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

    // Animate stat counters
    animateCounter(document.getElementById('stat-messages'), stats.totalMessages);
    animateCounter(document.getElementById('stat-deleted'), stats.deletedRecovered);
    animateCounter(document.getElementById('stat-media'), stats.totalMedia);
    animateCounter(document.getElementById('stat-voice'), stats.totalVoiceNotes);

    // Storage usage
    const storage = await mediaManager.getStorageUsage();
    document.getElementById('storage-used').textContent = storage.formattedSize || '0 MB';

    // Recent messages
    const messages = await db.getMessages();
    const recentContainer = document.getElementById('recent-messages');
    const emptyContainer = document.getElementById('empty-recent');

    if (messages.length > 0) {
      recentContainer.innerHTML = messages.slice(0, 8).map(m => renderMessageItem(m)).join('');
      emptyContainer.classList.add('hidden');
      recentContainer.classList.remove('hidden');

      // Add click handlers to open chat
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

    // Update nav badge
    const newCount = messages.filter(m => {
      const age = Date.now() - m.timestamp;
      return age < 86400000; // less than 24h
    }).length;
    const badge = document.getElementById('nav-badge-messages');
    if (newCount > 0) {
      badge.textContent = newCount > 99 ? '99+' : newCount;
      badge.classList.remove('hidden');
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
    if (backBtn) {
      backBtn.addEventListener('click', () => this.closeChat());
    }
    
    const exportBtn = document.getElementById('btn-chat-export');
    if (exportBtn) {
      exportBtn.addEventListener('click', () => this.exportChat());
    }
  }

  async openChat(contactName) {
    this.currentChat = contactName;
    const messages = await db.getMessagesByContact(contactName);
    
    const detail = document.getElementById('chat-detail');
    const nameEl = document.getElementById('chat-detail-name');
    const statusEl = document.getElementById('chat-detail-status');
    const avatarEl = document.getElementById('chat-detail-avatar');
    const messagesContainer = document.getElementById('chat-detail-messages');

    nameEl.textContent = contactName;
    const deletedCount = messages.filter(m => m.isDeleted).length;
    statusEl.textContent = `${messages.length} messages${deletedCount > 0 ? ` · ${deletedCount} deleted recovered` : ''}`;
    
    const initials = getInitials(contactName);
    avatarEl.textContent = initials;
    avatarEl.style.background = getAvatarColor(contactName);

    messagesContainer.innerHTML = messages.map(m => renderMessageBubble(m)).join('');
    detail.classList.remove('hidden');

    setTimeout(() => {
      messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }, 100);
  }

  closeChat() {
    const detail = document.getElementById('chat-detail');
    detail.style.animation = 'none';
    detail.style.transform = 'translateX(100%)';
    detail.style.transition = 'transform 0.3s cubic-bezier(0.22, 1, 0.36, 1)';
    
    setTimeout(() => {
      detail.classList.add('hidden');
      detail.style.animation = '';
      detail.style.transform = '';
      detail.style.transition = '';
      this.currentChat = null;
    }, 300);
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
        if (!searchBar.classList.contains('hidden')) {
          searchInput.focus();
        }
      });
    }

    if (searchClose) {
      searchClose.addEventListener('click', () => {
        searchBar.classList.add('hidden');
        searchInput.value = '';
        this._onSearchClear();
      });
    }

    if (searchInput) {
      let debounceTimer;
      searchInput.addEventListener('input', () => {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(() => {
          this._onSearch(searchInput.value);
        }, 300);
      });
    }
  }

  async _onSearch(query) {
    if (!query.trim()) {
      this._onSearchClear();
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

  _onSearchClear() {
    if (this.currentPage === 'messages') {
      this.loadMessages();
    }
  }

  // =============================================
  // MEDIA
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

  // =============================================
  // VOICE NOTES
  // =============================================

  async loadVoiceNotes() {
    const notes = await db.getVoiceNotes();
    const list = document.getElementById('voice-list');
    const emptyState = document.getElementById('empty-voice');
    const countBadge = document.getElementById('voice-count');

    countBadge.textContent = `${notes.length} notes`;

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
    document.querySelectorAll('.voice-play-btn .material-icons-round').forEach(i => {
      i.textContent = 'play_arrow';
    });
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
      
      const totalDuration = bars.length * 0.15;
      const currentTime = (barIndex * 0.15);
      const remaining = Math.max(0, totalDuration - currentTime);
      const mins = Math.floor(remaining / 60);
      const secs = Math.floor(remaining % 60);
      timeEl.textContent = `${mins}:${secs.toString().padStart(2, '0')}`;
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
      checkUpdatesBtn.addEventListener('click', () => {
        autoUpdater.checkForUpdates(false);
      });
    }

    if (rerunOnboardingBtn) {
      rerunOnboardingBtn.addEventListener('click', () => {
        this.showOnboarding();
      });
    }

    if (exportBtn) {
      exportBtn.addEventListener('click', () => this.exportAllData());
    }

    if (clearBtn) {
      clearBtn.addEventListener('click', () => {
        showModal(
          'Clear All Data',
          'This will permanently delete all recovered messages, media, and voice notes. This action cannot be undone.',
          async () => {
            await db.clearAll();
            showToast('All data cleared', 'success');
            this.loadDashboard();
          }
        );
      });
    }

    if (enableBtn) {
      enableBtn.addEventListener('click', () => {
        this.showOnboarding();
      });
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
      console.error('Export error:', err);
    }
  }

  // =============================================
  // DEMO DATA SEEDING
  // =============================================

  async _seedDemoData() {
    const contacts = [
      'Ahmed Hassan', 'Sara Ali', 'Mohamed Khalid', 'Fatima Nour',
      'Omar Youssef', 'Layla Ibrahim', 'Karim Adel', 'Nadia Samir'
    ];

    const sampleMessages = [
      { text: "Hey, are you coming to the meeting?", type: "text" },
      { text: "I'll send you the photos from yesterday", type: "text" },
      { text: "Check this out! 😂", type: "text" },
      { text: "Can you call me when you're free?", type: "text" },
      { text: "Happy birthday! 🎂🎉", type: "text" },
      { text: "The project deadline has been extended to Friday", type: "text" },
      { text: "I just saw your message, sorry for the late reply", type: "text" },
      { text: "Where should we meet tomorrow?", type: "text" },
      { text: "That's a great idea! Let's do it", type: "text" },
      { text: "Thanks for letting me know 👍", type: "text" }
    ];

    const deletedMessages = [
      "I shouldn't have said that...",
      "Actually, forget what I just sent",
      "That was embarrassing 😳",
      "Delete this before anyone sees it",
      "Wrong chat, ignore that",
      "Please pretend you didn't read this"
    ];

    const now = Date.now();
    const day = 86400000;

    for (let i = 0; i < 35; i++) {
      const contact = contacts[Math.floor(Math.random() * contacts.length)];
      const msg = sampleMessages[Math.floor(Math.random() * sampleMessages.length)];
      await db.addMessage({
        contact: contact,
        text: msg.text,
        type: msg.type,
        timestamp: now - (Math.random() * day * 5),
        isDeleted: false,
        direction: Math.random() > 0.3 ? 'received' : 'sent',
      });
    }

    for (let i = 0; i < 10; i++) {
      const contact = contacts[Math.floor(Math.random() * contacts.length)];
      const text = deletedMessages[Math.floor(Math.random() * deletedMessages.length)];
      await db.addMessage({
        contact: contact,
        text: text,
        type: 'text',
        timestamp: now - (Math.random() * day * 3),
        isDeleted: true,
        direction: 'received',
      });
    }

    for (let i = 0; i < 8; i++) {
      const contact = contacts[Math.floor(Math.random() * contacts.length)];
      const duration = Math.floor(Math.random() * 60) + 8;
      const waveform = Array.from({ length: 32 }, () => Math.random());
      const timeOffset = Math.random() * day * 4;

      await db.addMessage({
        contact: contact,
        text: '🎤 Voice message',
        type: 'voice',
        duration: duration,
        waveform: waveform,
        timestamp: now - timeOffset,
        direction: 'received',
      });

      await db.addVoiceNote({
        contact: contact,
        duration: duration,
        waveform: waveform,
        timestamp: now - timeOffset,
      });
    }

    console.log('✅ Demo data seeded');
  }
}

// ---- Boot ----
const app = new WARecoveryApp();
document.addEventListener('DOMContentLoaded', () => app.init());

export default app;
