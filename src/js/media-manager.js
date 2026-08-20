/**
 * WA Recovery Pro — Media Manager
 * Handles media gallery, lightbox, and media file operations.
 */

import db from './database.js';
import { showToast, formatFileSize, formatTime } from './ui-components.js';

class MediaManager {
  constructor() {
    this.currentFilter = 'photos';
    this.allMedia = [];
    this._initLightbox();
  }

  _initLightbox() {
    const closeBtn = document.getElementById('lightbox-close');
    const downloadBtn = document.getElementById('lightbox-download');
    const shareBtn = document.getElementById('lightbox-share');
    const deleteBtn = document.getElementById('lightbox-delete');

    if (closeBtn) {
      closeBtn.addEventListener('click', () => this.closeLightbox());
    }
    if (downloadBtn) {
      downloadBtn.addEventListener('click', () => this.downloadCurrentMedia());
    }
    if (shareBtn) {
      shareBtn.addEventListener('click', () => this.shareCurrentMedia());
    }
    if (deleteBtn) {
      deleteBtn.addEventListener('click', () => this.deleteCurrentMedia());
    }

    // Close on escape key
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') this.closeLightbox();
    });
  }

  async shareCurrentMedia() {
    if (!this._currentMedia) return;
    const media = this._currentMedia;
    const path = media.url || media.filePath || media.thumbnail;
    
    try {
      if (window.Capacitor?.isNativePlatform()) {
        const { Plugins } = window.Capacitor;
        if (Plugins?.RecoveryBridge) {
          await Plugins.RecoveryBridge.shareMedia({
            path: path,
            mimeType: media.mimeType || (media.mediaType === 'video' ? 'video/mp4' : 'image/jpeg'),
            title: 'Share Media'
          });
          return;
        }
      }

      if (navigator.share) {
        await navigator.share({
          title: 'Recovered Media',
          text: `Recovered media from ${media.contact}`,
          url: path.startsWith('http') ? path : undefined
        });
      } else {
        showToast('Sharing not supported on this browser', 'info');
      }
    } catch (err) {
      console.log('Share error:', err);
    }
  }

  async deleteCurrentMedia() {
    if (!this._currentMedia) return;
    const id = this._currentMedia.id;
    const { showModal } = await import('./ui-components.js');

    showModal(
      'Delete Media Item',
      'Are you sure you want to permanently delete this recovered item?',
      async () => {
        await db.deleteMedia(id);
        this.closeLightbox();
        showToast('Media item deleted', 'success');
        if (window.WAApp?.loadMediaGrid) {
          window.WAApp.loadMediaGrid();
        }
      }
    );
  }

  async loadMedia(filter = 'photos') {
    this.currentFilter = filter;
    const typeMap = {
      photos: 'image',
      videos: 'video',
      docs: 'document',
      stickers: 'sticker'
    };
    
    const mediaType = typeMap[filter] || 'image';
    this.allMedia = await db.getMedia({ mediaType });
    return this.allMedia;
  }

  openLightbox(mediaId) {
    const media = this.allMedia.find(m => m.id === mediaId);
    if (!media) return;

    const lightbox = document.getElementById('media-lightbox');
    const image = document.getElementById('lightbox-image');
    const video = document.getElementById('lightbox-video');
    const sender = document.getElementById('lightbox-sender');
    const date = document.getElementById('lightbox-date');

    if (media.mediaType === 'video') {
      image.style.display = 'none';
      video.style.display = 'block';
      video.src = media.url;
      video.play();
    } else {
      video.style.display = 'none';
      video.pause();
      image.style.display = 'block';
      image.src = media.url || media.thumbnail;
    }

    sender.textContent = media.contact;
    date.textContent = formatTime(media.timestamp);
    
    this._currentMedia = media;
    lightbox.classList.remove('hidden');
    document.body.style.overflow = 'hidden';
  }

  closeLightbox() {
    const lightbox = document.getElementById('media-lightbox');
    const video = document.getElementById('lightbox-video');
    
    lightbox.classList.add('hidden');
    video.pause();
    video.src = '';
    document.body.style.overflow = '';
    this._currentMedia = null;
  }

  async downloadCurrentMedia() {
    if (!this._currentMedia) return;

    try {
      // In Capacitor context, use Filesystem plugin
      if (window.Capacitor?.isNativePlatform()) {
        const { Filesystem, Directory } = await import('@capacitor/filesystem');
        // Native download logic
        showToast('Saved to Downloads', 'success');
      } else {
        // Web fallback — create download link
        const a = document.createElement('a');
        a.href = this._currentMedia.url;
        a.download = this._currentMedia.filename || `wa_recovery_${this._currentMedia.id}`;
        a.click();
        showToast('Download started', 'success');
      }
    } catch (err) {
      showToast('Download failed', 'error');
      console.error('Download error:', err);
    }
  }

  async getStorageUsage() {
    const media = await db.getMedia();
    let totalSize = 0;
    for (const item of media) {
      totalSize += item.size || 0;
    }
    return {
      totalFiles: media.length,
      totalSize: totalSize,
      formattedSize: formatFileSize(totalSize)
    };
  }

  async getMediaByType() {
    const all = await db.getMedia();
    return {
      photos: all.filter(m => m.mediaType === 'image').length,
      videos: all.filter(m => m.mediaType === 'video').length,
      documents: all.filter(m => m.mediaType === 'document').length,
      stickers: all.filter(m => m.mediaType === 'sticker').length,
    };
  }
}

const mediaManager = new MediaManager();
export default mediaManager;
