/**
 * WA Recovery Pro — UI Components
 * Reusable UI elements: message bubbles, audio player, toast, modal, etc.
 */

// ---- Time Formatting ----

export function formatTime(timestamp) {
  const date = new Date(timestamp);
  const now = new Date();
  const diffMs = now - date;
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMs / 3600000);
  const diffDays = Math.floor(diffMs / 86400000);

  if (diffMins < 1) return 'Just now';
  if (diffMins < 60) return `${diffMins}m ago`;
  if (diffHours < 24) return `${diffHours}h ago`;
  if (diffDays < 7) return `${diffDays}d ago`;

  return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

export function formatTimeShort(timestamp) {
  const date = new Date(timestamp);
  return date.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: true });
}

export function formatDuration(seconds) {
  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return `${mins}:${secs.toString().padStart(2, '0')}`;
}

export function formatFileSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB';
  return (bytes / 1073741824).toFixed(2) + ' GB';
}

// ---- Avatar ----

export function getInitials(name) {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  if (parts.length >= 2) {
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }
  return name.charAt(0).toUpperCase();
}

const avatarColors = [
  'linear-gradient(135deg, #25D366, #128C7E)',
  'linear-gradient(135deg, #667eea, #764ba2)',
  'linear-gradient(135deg, #f093fb, #f5576c)',
  'linear-gradient(135deg, #4facfe, #00f2fe)',
  'linear-gradient(135deg, #fa709a, #fee140)',
  'linear-gradient(135deg, #a18cd1, #fbc2eb)',
  'linear-gradient(135deg, #fccb90, #d57eeb)',
  'linear-gradient(135deg, #66a6ff, #89f7fe)',
];

export function getAvatarColor(name) {
  let hash = 0;
  for (let i = 0; i < (name || '').length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash);
  }
  return avatarColors[Math.abs(hash) % avatarColors.length];
}

// ---- Message Item (for recent activity list) ----

export function renderMessageItem(message) {
  const initials = getInitials(message.contact);
  const color = getAvatarColor(message.contact);
  const time = formatTime(message.timestamp);
  
  let preview = message.text || '';
  if (message.type === 'voice') preview = '🎤 Voice message';
  if (message.type === 'image') preview = '📷 Photo';
  if (message.type === 'video') preview = '🎥 Video';
  if (message.type === 'document') preview = '📄 Document';
  if (message.type === 'sticker') preview = '🎨 Sticker';

  let badges = '';
  if (message.isDeleted) {
    badges += '<span class="badge badge-deleted" style="font-size:10px;margin-left:4px;">Deleted</span>';
  }
  if (message.isViewOnce) {
    badges += '<span class="badge" style="font-size:10px;margin-left:4px;background:rgba(168,85,247,0.12);color:#a855f7;">View Once</span>';
  }

  return `
    <div class="message-item" data-contact="${escapeHtml(message.contact)}">
      <div class="message-avatar" style="background:${color}">${initials}</div>
      <div class="message-content">
        <div class="message-sender">${escapeHtml(message.contact)}${badges}</div>
        <div class="message-text">${message.isDeleted ? '🗑️ ' : ''}${escapeHtml(preview)}</div>
      </div>
      <span class="message-timestamp">${time}</span>
    </div>
  `;
}

// ---- Chat Item (for chat list) ----

export function renderChatItem(contact) {
  const initials = getInitials(contact.name);
  const color = getAvatarColor(contact.name);
  const time = formatTime(contact.lastActive);

  return `
    <div class="chat-item selectable-item" data-contact="${escapeHtml(contact.name)}" data-id="${escapeHtml(contact.name)}">
      <div class="item-select-checkbox"><span class="material-icons-round">check</span></div>
      <div class="chat-avatar ${contact.hasDeleted ? 'has-deleted' : ''}" style="background:${color}">${initials}</div>
      <div class="chat-info">
        <div class="chat-name">${escapeHtml(contact.name)}</div>
        <div class="chat-preview">
          ${contact.hasDeleted ? '<span class="material-icons-round deleted-indicator">delete</span>' : ''}
          ${escapeHtml(contact.lastMessage)}
        </div>
      </div>
      <div class="chat-meta">
        <span class="chat-time">${time}</span>
        ${contact.messageCount > 0 ? `<span class="chat-count">${contact.messageCount}</span>` : ''}
      </div>
    </div>
  `;
}

// ---- Message Bubble (for chat detail view) ----

export function renderMessageBubble(message) {
  const time = formatTimeShort(message.timestamp);
  let cssClass = `message-bubble ${message.direction || 'received'}`;
  if (message.isDeleted) cssClass += ' deleted';
  if (message.isViewOnce) cssClass += ' view-once';

  let content = '';
  
  const photoSrc = message.mediaThumbnail || message.mediaUrl || message.thumbnailBase64 || message.raw;
  if (photoSrc) {
    if (message.type === 'image' || message.type === 'sticker' || message.isViewOnce) {
      content += `
        <div class="message-media-container" onclick="window.WAApp?.openDirectImage('${escapeHtml(photoSrc)}', '${escapeHtml(message.contact)}')" style="cursor:pointer;position:relative;margin-bottom:6px;border-radius:10px;overflow:hidden;">
          <img class="message-media" src="${photoSrc}" alt="Photo" style="display:block;max-width:100%;border-radius:8px;max-height:260px;object-fit:cover;">
          <div style="font-size:11px;color:var(--green-primary);margin-top:4px;display:flex;align-items:center;gap:4px;font-weight:600;">
            <span class="material-icons-round" style="font-size:15px;">visibility</span> Tap to view full photo
          </div>
        </div>
      `;
    } else if (message.type === 'video') {
      content += `<div style="position:relative;display:inline-block;width:100%;cursor:pointer;" onclick="window.WAApp?.openDirectImage('${escapeHtml(photoSrc)}', '${escapeHtml(message.contact)}')">
        <img class="message-media" src="${photoSrc}" alt="Video" style="display:block;max-width:100%;border-radius:8px;">
        <div style="position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:44px;height:44px;background:rgba(0,0,0,0.6);border-radius:50%;display:flex;align-items:center;justify-content:center;">
          <span class="material-icons-round" style="color:white;font-size:26px;">play_arrow</span>
        </div>
      </div>`;
    }
  } else if (message.type === 'image' || message.isViewOnce) {
    content += `
      <div class="message-photo-card" onclick="window.WAApp?.navigateTo('media')" style="cursor:pointer;padding:10px 12px;background:rgba(255,255,255,0.06);border-radius:10px;margin-bottom:6px;display:flex;align-items:center;gap:10px;">
        <span class="material-icons-round" style="font-size:28px;color:var(--accent-purple);">photo_camera</span>
        <div>
          <div style="font-weight:600;font-size:13px;">${message.isViewOnce ? 'View-Once Photo' : 'Photo Message'}</div>
          <div style="font-size:11px;color:var(--text-muted);">Tap to check Media Gallery</div>
        </div>
      </div>
    `;
  }

  if (message.text && message.text !== '📷 Photo' && message.text !== 'photo') {
    content += `<div>${escapeHtml(message.text)}</div>`;
  }

  if (message.type === 'voice') {
    content += renderInlineVoicePlayer(message);
  }

  let actions = `
    <div class="bubble-actions">
      <button class="bubble-action-btn" onclick="window.WAApp?.copyMessage('${message.id}')" title="Copy">
        <span class="material-icons-round">content_copy</span> Copy
      </button>
      <button class="bubble-action-btn" onclick="window.WAApp?.shareMessage('${message.id}')" title="Share">
        <span class="material-icons-round">share</span> Share
      </button>
      <button class="bubble-action-btn action-delete" onclick="window.WAApp?.deleteMessageItem('${message.id}')" title="Delete">
        <span class="material-icons-round">delete</span>
      </button>
    </div>
  `;

  return `
    <div class="${cssClass} selectable-item" data-id="${message.id}">
      <div class="item-select-checkbox"><span class="material-icons-round">check</span></div>
      ${content}
      <div class="bubble-footer">
        ${actions}
        <span class="message-time">${time}</span>
      </div>
    </div>
  `;
}

// ---- Inline Voice Player (within chat bubbles) ----

function renderInlineVoicePlayer(message) {
  const duration = message.duration || 12;
  const bars = generateWaveformBars(message.waveform || [], 20);
  const audioPath = message.filePath || message.mediaUrl || message.audioUrl || message.url || '';
  
  return `
    <div class="voice-player" data-voice-id="${message.id}" data-audio-url="${escapeHtml(audioPath)}">
      <button class="voice-play-btn" onclick="window.WAApp?.playVoice('${message.id}')" aria-label="Play">
        <span class="material-icons-round">play_arrow</span>
      </button>
      <div class="voice-waveform">${bars}</div>
      <span class="voice-time">${formatDuration(duration)}</span>
      <button class="voice-speed-pill" onclick="window.WAApp?.toggleVoiceSpeed('${message.id}', this)" title="Speed">1x</button>
    </div>
  `;
}

// ---- Voice Note Card (for voice tab) ----

export function renderVoiceItem(voice) {
  const initials = getInitials(voice.contact);
  const color = getAvatarColor(voice.contact);
  const date = formatTime(voice.timestamp);
  const duration = formatDuration(voice.duration || 14);
  const bars = generateWaveformBars(voice.waveform || [], 32);

  const audioPath = voice.audioUrl || voice.url || voice.path || voice.filePath || '';

  return `
    <div class="voice-item selectable-item" data-voice-id="${voice.id}" data-id="${voice.id}" data-audio-url="${escapeHtml(audioPath)}">
      <div class="item-select-checkbox"><span class="material-icons-round">check</span></div>
      <div class="voice-item-header">
        <div class="voice-avatar" style="background:${color}">${initials}</div>
        <div class="voice-info">
          <div class="voice-sender">${escapeHtml(voice.contact)}</div>
          <div class="voice-date">${date}</div>
        </div>
        <div class="voice-header-actions">
          <span class="voice-duration">${duration}</span>
          <button class="voice-action-icon-btn" onclick="window.WAApp?.openVoiceOptions('${voice.id}')" title="More Options">
            <span class="material-icons-round">more_vert</span>
          </button>
        </div>
      </div>
      
      <div class="voice-player" data-voice-id="${voice.id}" data-audio-url="${escapeHtml(audioPath)}">
        <button class="voice-play-btn" onclick="window.WAApp?.playVoice('${voice.id}')" aria-label="Play Voice">
          <span class="material-icons-round">play_arrow</span>
        </button>
        <div class="voice-waveform">${bars}</div>
        <span class="voice-time">${duration}</span>
        <button class="voice-speed-pill" onclick="window.WAApp?.toggleVoiceSpeed('${voice.id}', this)" title="Speed">1x</button>
      </div>

      <!-- Quick Action Pills -->
      <div class="voice-quick-actions">
        <button class="voice-chip-btn" onclick="window.WAApp?.transcribeVoice('${voice.id}')">
          <span class="material-icons-round">subtitles</span> Transcribe
        </button>
        <button class="voice-chip-btn" onclick="window.WAApp?.shareVoice('${voice.id}')">
          <span class="material-icons-round">share</span> Share
        </button>
        <button class="voice-chip-btn action-delete" onclick="window.WAApp?.deleteVoiceItem('${voice.id}')" title="Delete">
          <span class="material-icons-round">delete</span> Delete
        </button>
        <button class="voice-chip-btn" onclick="window.WAApp?.openVoiceOptions('${voice.id}')">
          <span class="material-icons-round">tune</span> Effects
        </button>
      </div>
    </div>
  `;
}

// ---- Waveform Generator ----

function generateWaveformBars(waveform, count) {
  const bars = [];
  for (let i = 0; i < count; i++) {
    const height = waveform[i] !== undefined 
      ? Math.max(4, Math.min(28, waveform[i] * 28))
      : Math.max(4, Math.random() * 24 + 4);
    bars.push(`<div class="wave-bar" style="height:${height}px;" data-index="${i}"></div>`);
  }
  return bars.join('');
}

// ---- Media Grid Item ----

export function renderMediaItem(media) {
  const isVideo = media.mediaType === 'video';
  const isDoc = media.mediaType === 'document';

  if (isDoc) {
    return `
      <div class="media-item selectable-item" data-media-id="${media.id}" data-id="${media.id}" style="display:flex;align-items:center;justify-content:center;background:var(--bg-elevated);">
        <div class="item-select-checkbox"><span class="material-icons-round">check</span></div>
        <div style="text-align:center;padding:8px;">
          <span class="material-icons-round" style="font-size:28px;color:var(--accent-blue);">description</span>
          <div style="font-size:10px;color:var(--text-muted);margin-top:4px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${escapeHtml(media.filename || 'Document')}</div>
        </div>
        ${media.isDeleted ? '<div class="deleted-badge"><span class="material-icons-round">delete</span></div>' : ''}
      </div>
    `;
  }

  return `
    <div class="media-item selectable-item" data-media-id="${media.id}" data-id="${media.id}">
      <div class="item-select-checkbox"><span class="material-icons-round">check</span></div>
      <img src="${media.thumbnail || media.url}" alt="" loading="lazy">
      ${isVideo ? '<div class="video-indicator"><span class="material-icons-round">play_arrow</span></div>' : ''}
      ${media.isDeleted ? '<div class="deleted-badge"><span class="material-icons-round">delete</span></div>' : ''}
      <div class="media-overlay">
        <span class="media-overlay-text">${escapeHtml(media.contact)}</span>
      </div>
    </div>
  `;
}

// ---- Toast Notification ----

export function showToast(message, type = 'info', duration = 3000) {
  const container = document.getElementById('toast-container');
  const icons = { success: 'check_circle', error: 'error', info: 'info' };
  
  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  toast.innerHTML = `
    <span class="material-icons-round">${icons[type] || 'info'}</span>
    <span>${escapeHtml(message)}</span>
  `;
  
  container.appendChild(toast);

  setTimeout(() => {
    toast.classList.add('fade-out');
    setTimeout(() => toast.remove(), 300);
  }, duration);
}

// ---- Confirmation Modal ----

export function showModal(title, message, onConfirm, icon = 'warning') {
  const overlay = document.getElementById('modal-overlay');
  const modalIcon = document.getElementById('modal-icon');
  const modalTitle = document.getElementById('modal-title');
  const modalMessage = document.getElementById('modal-message');
  const confirmBtn = document.getElementById('modal-confirm');
  const cancelBtn = document.getElementById('modal-cancel');

  modalIcon.innerHTML = `<span class="material-icons-round">${icon}</span>`;
  modalTitle.textContent = title;
  modalMessage.textContent = message;
  overlay.classList.remove('hidden');

  const cleanup = () => {
    overlay.classList.add('hidden');
    confirmBtn.replaceWith(confirmBtn.cloneNode(true));
    cancelBtn.replaceWith(cancelBtn.cloneNode(true));
  };

  document.getElementById('modal-confirm').addEventListener('click', () => {
    cleanup();
    if (onConfirm) onConfirm();
  });

  document.getElementById('modal-cancel').addEventListener('click', cleanup);
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay) cleanup();
  });
}

// ---- Animated Counter ----

export function animateCounter(element, target, duration = 1200) {
  const start = parseInt(element.textContent) || 0;
  const diff = target - start;
  if (diff === 0) return;

  const startTime = performance.now();

  function update(currentTime) {
    const elapsed = currentTime - startTime;
    const progress = Math.min(elapsed / duration, 1);
    
    // Ease out cubic
    const eased = 1 - Math.pow(1 - progress, 3);
    const current = Math.round(start + diff * eased);
    
    element.textContent = current.toLocaleString();
    
    if (progress < 1) {
      requestAnimationFrame(update);
    }
  }

  requestAnimationFrame(update);
}

// ---- HTML Escape ----

function escapeHtml(text) {
  if (!text) return '';
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

export { escapeHtml };
