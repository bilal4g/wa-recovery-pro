/**
 * WA Recovery Pro — Multi-Format Export & Sharing Manager
 * Handles TXT, HTML, PDF/Print exports and smart single-item sharing.
 */

import db from './database.js';
import { showToast, formatTime, formatTimeShort, formatDuration, getInitials, getAvatarColor, escapeHtml } from './ui-components.js';
import voiceOptions from './voice-options.js';

class ExportManager {
  constructor() {
    this.currentChat = null;
    this.currentMessages = [];
    this._initModal();
  }

  _initModal() {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', () => this._bindEvents());
    } else {
      this._bindEvents();
    }
  }

  _bindEvents() {
    const overlay = document.getElementById('export-modal-overlay');
    const closeBtn = document.getElementById('export-modal-close');

    if (closeBtn) closeBtn.addEventListener('click', () => this.closeExportModal());
    if (overlay) {
      overlay.addEventListener('click', (e) => {
        if (e.target === overlay) this.closeExportModal();
      });
    }

    const btnTxt = document.getElementById('export-btn-txt');
    const btnHtml = document.getElementById('export-btn-html');
    const btnPdf = document.getElementById('export-btn-pdf');
    const btnShareSummary = document.getElementById('export-btn-share-summary');

    if (btnTxt) btnTxt.addEventListener('click', () => this.exportAsTxt());
    if (btnHtml) btnHtml.addEventListener('click', () => this.exportAsHtml());
    if (btnPdf) btnPdf.addEventListener('click', () => this.exportAsPdf());
    if (btnShareSummary) btnShareSummary.addEventListener('click', () => this.shareSummary());
  }

  /**
   * Opens the Export modal for the current active chat.
   */
  async openExportModal(contactName) {
    if (!contactName) return;
    this.currentChat = contactName;
    this.currentMessages = await db.getMessagesByContact(contactName);

    const overlay = document.getElementById('export-modal-overlay');
    const titleEl = document.getElementById('export-modal-contact');
    const countEl = document.getElementById('export-modal-count');

    if (titleEl) titleEl.textContent = contactName;
    if (countEl) {
      const deleted = this.currentMessages.filter(m => m.isDeleted).length;
      countEl.textContent = `${this.currentMessages.length} total messages (${deleted} deleted recovered)`;
    }

    if (overlay) overlay.classList.remove('hidden');
  }

  closeExportModal() {
    const overlay = document.getElementById('export-modal-overlay');
    if (overlay) overlay.classList.add('hidden');
  }

  /**
   * 1. Export as Plain Text (.txt)
   */
  exportAsTxt() {
    if (!this.currentChat || !this.currentMessages.length) {
      showToast('No messages to export', 'info');
      return;
    }

    const contact = this.currentChat;
    const dateStr = new Date().toLocaleString();
    let content = `====================================================\n`;
    content += ` WA RECOVERY PRO — CHAT EXPORT LOG\n`;
    content += ` Contact: ${contact}\n`;
    content += ` Exported: ${dateStr}\n`;
    content += ` Total Messages: ${this.currentMessages.length}\n`;
    content += `====================================================\n\n`;

    for (const msg of this.currentMessages) {
      const time = new Date(msg.timestamp).toLocaleString();
      const sender = msg.direction === 'sent' ? 'You' : msg.contact;
      let tags = [];
      if (msg.isDeleted) tags.push('[DELETED]');
      if (msg.isViewOnce) tags.push('[VIEW ONCE]');
      const tagStr = tags.length ? ` ${tags.join(' ')}` : '';

      let body = msg.text || '';
      if (msg.type === 'voice') body = `[Voice Message - Duration: ${formatDuration(msg.duration || 10)}]`;
      if (msg.type === 'image') body = `[Photo / Media Message]`;
      if (msg.type === 'video') body = `[Video Message]`;
      if (msg.type === 'document') body = `[Document: ${msg.filename || 'File'}]`;

      content += `[${time}] ${sender}${tagStr}:\n${body}\n\n`;
    }

    this._downloadFile(content, `wa_chat_${this._sanitize(contact)}.txt`, 'text/plain;charset=utf-8');
    showToast('TXT chat export downloaded', 'success');
    this.closeExportModal();
  }

  /**
   * 2. Export as Standalone Styled HTML (.html)
   */
  exportAsHtml() {
    if (!this.currentChat || !this.currentMessages.length) {
      showToast('No messages to export', 'info');
      return;
    }

    const contact = this.currentChat;
    const initials = getInitials(contact);
    const avatarColor = getAvatarColor(contact);
    const dateStr = new Date().toLocaleString();
    const deletedCount = this.currentMessages.filter(m => m.isDeleted).length;

    let messageHtml = '';
    for (const msg of this.currentMessages) {
      const time = formatTimeShort(msg.timestamp);
      const isSent = msg.direction === 'sent';
      const bubbleClass = isSent ? 'msg msg-sent' : 'msg msg-received';
      const deletedBadge = msg.isDeleted ? '<span class="tag tag-deleted">🗑️ Deleted</span>' : '';
      const viewOnceBadge = msg.isViewOnce ? '<span class="tag tag-viewonce">👁️ View Once</span>' : '';

      let mediaHtml = '';
      const photoSrc = msg.mediaThumbnail || msg.mediaUrl || msg.thumbnailBase64;
      if (photoSrc && (msg.type === 'image' || msg.isViewOnce)) {
        mediaHtml = `<div class="media-wrap"><img src="${photoSrc}" alt="Recovered Media" /></div>`;
      } else if (msg.type === 'voice') {
        mediaHtml = `<div class="voice-box">🎤 Voice Message (${formatDuration(msg.duration || 10)})</div>`;
      }

      let textHtml = msg.text && msg.text !== '📷 Photo' ? `<div class="msg-text">${escapeHtml(msg.text)}</div>` : '';

      messageHtml += `
        <div class="${bubbleClass}">
          <div class="msg-header">
            <span class="sender">${escapeHtml(isSent ? 'You' : msg.contact)}</span>
            ${deletedBadge}
            ${viewOnceBadge}
          </div>
          ${mediaHtml}
          ${textHtml}
          <div class="msg-time">${time}</div>
        </div>
      `;
    }

    const fullHtml = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Recovered Chat with ${escapeHtml(contact)} — WA Recovery Pro</title>
  <style>
    :root {
      --bg: #0b141a;
      --card-bg: #111b21;
      --bubble-in: #202c33;
      --bubble-out: #005c4b;
      --text: #e9edef;
      --text-muted: #8696a0;
      --green: #00a884;
      --deleted: #ef4444;
      --viewonce: #a855f7;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
    body { background: var(--bg); color: var(--text); padding: 20px; display: flex; justify-content: center; }
    .chat-container { width: 100%; max-width: 680px; background: var(--card-bg); border-radius: 16px; overflow: hidden; box-shadow: 0 10px 30px rgba(0,0,0,0.5); }
    .chat-header { background: #202c33; padding: 16px 20px; display: flex; align-items: center; gap: 14px; border-bottom: 1px solid rgba(255,255,255,0.06); }
    .avatar { width: 44px; height: 44px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-weight: bold; color: white; font-size: 18px; }
    .header-info h2 { font-size: 17px; margin-bottom: 3px; }
    .header-info p { font-size: 12px; color: var(--text-muted); }
    .chat-body { padding: 20px 16px; display: flex; flex-direction: column; gap: 10px; background: radial-gradient(circle at center, rgba(32,44,51,0.4) 0%, #0b141a 100%); min-height: 400px; }
    .msg { max-width: 80%; padding: 8px 12px 6px; border-radius: 12px; position: relative; font-size: 14px; line-height: 1.4; word-wrap: break-word; }
    .msg-received { align-self: flex-start; background: var(--bubble-in); border-bottom-left-radius: 2px; }
    .msg-sent { align-self: flex-end; background: var(--bubble-out); border-bottom-right-radius: 2px; }
    .msg-header { font-size: 11px; font-weight: 600; color: var(--green); margin-bottom: 4px; display: flex; align-items: center; gap: 6px; }
    .tag { font-size: 10px; padding: 2px 6px; border-radius: 4px; font-weight: bold; }
    .tag-deleted { background: rgba(239, 68, 68, 0.2); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.4); }
    .tag-viewonce { background: rgba(168, 85, 247, 0.2); color: #c084fc; border: 1px solid rgba(168, 85, 247, 0.4); }
    .media-wrap { margin-top: 4px; margin-bottom: 4px; border-radius: 8px; overflow: hidden; max-width: 100%; }
    .media-wrap img { width: 100%; max-height: 300px; object-fit: cover; display: block; border-radius: 6px; }
    .voice-box { background: rgba(255,255,255,0.06); padding: 8px 12px; border-radius: 8px; font-size: 13px; font-weight: 500; display: flex; align-items: center; gap: 6px; margin: 4px 0; }
    .msg-time { font-size: 10px; color: var(--text-muted); text-align: right; margin-top: 4px; }
    .footer { text-align: center; padding: 14px; font-size: 11px; color: var(--text-muted); background: #202c33; }
    @media print {
      body { background: white; color: black; padding: 0; }
      .chat-container { box-shadow: none; max-width: 100%; }
      .msg-received { background: #f0f2f5; color: black; }
      .msg-sent { background: #d9fdd3; color: black; }
      .chat-body { background: white; }
    }
  </style>
</head>
<body>
  <div class="chat-container">
    <div class="chat-header">
      <div class="avatar" style="background: ${avatarColor}">${initials}</div>
      <div class="header-info">
        <h2>${escapeHtml(contact)}</h2>
        <p>${this.currentMessages.length} Messages · ${deletedCount} Deleted Recovered · Exported on ${dateStr}</p>
      </div>
    </div>
    <div class="chat-body">
      ${messageHtml}
    </div>
    <div class="footer">
      Recovered with <strong>WA Recovery Pro</strong> · Local Offline Export
    </div>
  </div>
</body>
</html>`;

    this._downloadFile(fullHtml, `wa_chat_${this._sanitize(contact)}.html`, 'text/html;charset=utf-8');
    showToast('HTML chat export downloaded', 'success');
    this.closeExportModal();
  }

  /**
   * 3. Export as PDF (via Print-to-PDF Window)
   */
  exportAsPdf() {
    if (!this.currentChat || !this.currentMessages.length) {
      showToast('No messages to export', 'info');
      return;
    }

    const contact = this.currentChat;
    const dateStr = new Date().toLocaleString();
    const deletedCount = this.currentMessages.filter(m => m.isDeleted).length;

    let rowsHtml = '';
    for (const msg of this.currentMessages) {
      const time = new Date(msg.timestamp).toLocaleString();
      const sender = msg.direction === 'sent' ? 'You' : msg.contact;
      let status = 'Normal';
      if (msg.isDeleted) status = '<span style="color:#dc2626;font-weight:bold;">DELETED</span>';
      if (msg.isViewOnce) status = '<span style="color:#7c3aed;font-weight:bold;">VIEW ONCE</span>';

      let content = msg.text || '';
      if (msg.type === 'voice') content = `[Voice Note - ${formatDuration(msg.duration || 10)}]`;
      if (msg.type === 'image') content = `[Photo / Image]`;
      if (msg.type === 'video') content = `[Video]`;

      rowsHtml += `
        <tr style="border-bottom:1px solid #e5e7eb;">
          <td style="padding:8px 10px;font-size:11px;color:#6b7280;white-space:nowrap;">${time}</td>
          <td style="padding:8px 10px;font-size:12px;font-weight:600;color:#1f2937;">${escapeHtml(sender)}</td>
          <td style="padding:8px 10px;font-size:12px;color:#111827;">${escapeHtml(content)}</td>
          <td style="padding:8px 10px;font-size:11px;text-align:center;">${status}</td>
        </tr>
      `;
    }

    const printWindow = window.open('', '_blank');
    if (!printWindow) {
      showToast('Please allow popups to generate PDF', 'error');
      return;
    }

    printWindow.document.write(`<!DOCTYPE html>
<html>
<head>
  <title>Chat Report - ${escapeHtml(contact)}</title>
  <style>
    body { font-family: Arial, sans-serif; padding: 24px; color: #111827; }
    .header { border-bottom: 2px solid #2563eb; padding-bottom: 12px; margin-bottom: 18px; }
    h1 { font-size: 20px; color: #1e3a8a; margin: 0 0 4px 0; }
    .meta { font-size: 12px; color: #4b5563; }
    table { width: 100%; border-collapse: collapse; margin-top: 10px; }
    th { background: #f3f4f6; padding: 8px 10px; font-size: 12px; text-align: left; border-bottom: 2px solid #d1d5db; }
    @media print {
      body { padding: 0; }
      @page { margin: 1.5cm; }
    }
  </style>
</head>
<body>
  <div class="header">
    <h1>🛡️ WA Recovery Pro — Conversation Transcript</h1>
    <div class="meta">
      <strong>Contact:</strong> ${escapeHtml(contact)} | 
      <strong>Total Messages:</strong> ${this.currentMessages.length} | 
      <strong>Deleted Recovered:</strong> ${deletedCount} | 
      <strong>Date Generated:</strong> ${dateStr}
    </div>
  </div>
  <table>
    <thead>
      <tr>
        <th style="width:140px;">Timestamp</th>
        <th style="width:120px;">Sender</th>
        <th>Message Content</th>
        <th style="width:90px;text-align:center;">Status</th>
      </tr>
    </thead>
    <tbody>
      ${rowsHtml}
    </tbody>
  </table>
  <script>
    window.onload = function() {
      setTimeout(function() {
        window.print();
      }, 300);
    };
  <\/script>
</body>
</html>`);

    printWindow.document.close();
    showToast('Opening PDF Print Preview...', 'info');
    this.closeExportModal();
  }

  /**
   * 4. Quick Share Summary to WhatsApp / Apps
   */
  async shareSummary() {
    if (!this.currentChat || !this.currentMessages.length) return;

    const contact = this.currentChat;
    const deleted = this.currentMessages.filter(m => m.isDeleted);
    let summaryText = `🛡️ WA Recovery Pro Report for ${contact}\n`;
    summaryText += `Total Messages Saved: ${this.currentMessages.length}\n`;
    summaryText += `Deleted Messages Recovered: ${deleted.length}\n\n`;

    if (deleted.length > 0) {
      summaryText += `Last Recovered Deleted Messages:\n`;
      deleted.slice(-5).forEach((d, i) => {
        const time = formatTimeShort(d.timestamp);
        summaryText += `${i + 1}. [${time}] ${d.text || `[${d.type}]`}\n`;
      });
    }

    try {
      if (navigator.share) {
        await navigator.share({
          title: `Recovered Chat with ${contact}`,
          text: summaryText
        });
        showToast('Shared summary successfully', 'success');
      } else {
        await navigator.clipboard.writeText(summaryText);
        showToast('Summary copied to clipboard', 'info');
      }
    } catch (e) {
      if (e.name !== 'AbortError') {
        showToast('Failed to share summary', 'error');
      }
    }

    this.closeExportModal();
  }

  /**
   * 5. Smart Single Item Sharing
   * - If Text: Shares message text directly via Web Share API
   * - If Voice: Opens voice audio options & audio file sharing
   * - If Photo: Opens photo viewer / direct image share
   */
  async shareSingleMessage(messageId) {
    const messages = await db.getMessages();
    const msg = messages.find(m => String(m.id) === String(messageId));

    if (!msg) {
      showToast('Message not found', 'error');
      return;
    }

    // Voice Note -> Delegate to Voice Options Suite
    if (msg.type === 'voice') {
      voiceOptions.openOptions(msg);
      return;
    }

    // Photo / Video -> Delegate to Media Lightbox / Direct View
    if (msg.type === 'image' || msg.isViewOnce || msg.type === 'video') {
      const src = msg.mediaThumbnail || msg.mediaUrl || msg.thumbnailBase64;
      if (src && window.WAApp) {
        window.WAApp.openDirectImage(src, msg.contact);
        return;
      }
    }

    // Text Message -> Native Text Share
    const textToShare = msg.text || '';
    if (!textToShare) {
      showToast('No text to share', 'info');
      return;
    }

    const shareData = {
      title: `Message from ${msg.contact}`,
      text: `${msg.text}\n\n(Recovered from ${msg.contact} via WA Recovery Pro)`
    };

    try {
      if (navigator.share) {
        await navigator.share(shareData);
        showToast('Shared message', 'success');
      } else {
        await navigator.clipboard.writeText(msg.text);
        showToast('Message copied to clipboard', 'success');
      }
    } catch (e) {
      if (e.name !== 'AbortError') {
        await navigator.clipboard.writeText(msg.text);
        showToast('Message copied to clipboard', 'info');
      }
    }
  }

  /**
   * Copies raw text of a message to clipboard.
   */
  async copyMessageText(messageId) {
    const messages = await db.getMessages();
    const msg = messages.find(m => String(m.id) === String(messageId));
    if (!msg || !msg.text) {
      showToast('No text to copy', 'info');
      return;
    }

    try {
      await navigator.clipboard.writeText(msg.text);
      showToast('Copied to clipboard', 'success');
    } catch (e) {
      showToast('Failed to copy', 'error');
    }
  }

  _downloadFile(content, filename, mimeType) {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  _sanitize(name) {
    return (name || 'chat').replace(/[^a-zA-Z0-9_-]/g, '_');
  }
}

const exportManager = new ExportManager();
export default exportManager;
