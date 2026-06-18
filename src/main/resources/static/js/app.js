// ============================================================================
// Sidebar search — full-text across all transcripts
// ============================================================================
(() => {
  const input = document.getElementById('globalSearch');
  if (!input) return;
  const wrap = input.closest('.sidebar-search');
  if (!wrap) return;

  const dropdown = document.createElement('div');
  dropdown.className = 'search-dropdown';
  dropdown.hidden = true;
  wrap.appendChild(dropdown);

  const debounce = (fn, ms) => {
    let t; return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
  };

  let selectedIndex = -1;
  let currentHits = [];
  let inflight = null;

  const escapeHtml = (s) => (s || '').replace(/[&<>"']/g,
    c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const renderSnippet = (raw) =>
    escapeHtml(raw).replace(/⟪/g, '<mark>').replace(/⟫/g, '</mark>');
  const fmtDate = (iso) => {
    if (!iso) return '';
    try { return new Date(iso).toLocaleDateString(undefined,
      { month:'short', day:'numeric', hour:'2-digit', minute:'2-digit' }); }
    catch (e) { return ''; }
  };

  function render() {
    if (!currentHits.length) {
      dropdown.innerHTML = `<div class="search-empty">No matches.</div>`;
      dropdown.hidden = false;
      return;
    }
    dropdown.innerHTML = currentHits.map((h, i) => `
      <a href="/recordings/${h.recordingId}"
         class="search-hit ${i === selectedIndex ? 'is-selected' : ''}"
         data-i="${i}">
        <div class="search-hit-row">
          <span class="search-hit-name">${escapeHtml(h.filename || 'Untitled recording')}</span>
          ${h.matchedTranscript ? '<span class="search-hit-tag">transcript</span>' : '<span class="search-hit-tag muted">filename</span>'}
        </div>
        <div class="search-hit-snippet">${renderSnippet(h.snippet)}</div>
        <div class="search-hit-meta">${fmtDate(h.createdAt)}</div>
      </a>
    `).join('');
    dropdown.hidden = false;
  }

  const performSearch = debounce(async () => {
    const q = input.value.trim();
    if (q.length === 0) { dropdown.hidden = true; currentHits = []; selectedIndex = -1; return; }
    if (inflight) inflight.abort();
    inflight = new AbortController();
    try {
      const r = await fetch('/api/search?q=' + encodeURIComponent(q), { signal: inflight.signal });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      currentHits = await r.json();
      selectedIndex = currentHits.length ? 0 : -1;
      render();
    } catch (e) {
      if (e.name !== 'AbortError') {
        dropdown.innerHTML = `<div class="search-empty">Search error</div>`;
        dropdown.hidden = false;
      }
    }
  }, 200);

  input.addEventListener('input', performSearch);
  input.addEventListener('keydown', (e) => {
    if (dropdown.hidden) return;
    if (e.key === 'ArrowDown') { e.preventDefault(); if (currentHits.length) { selectedIndex = (selectedIndex + 1) % currentHits.length; render(); } }
    else if (e.key === 'ArrowUp') { e.preventDefault(); if (currentHits.length) { selectedIndex = (selectedIndex - 1 + currentHits.length) % currentHits.length; render(); } }
    else if (e.key === 'Enter') { if (selectedIndex >= 0 && currentHits[selectedIndex]) { e.preventDefault(); window.location.href = '/recordings/' + currentHits[selectedIndex].recordingId; } }
    else if (e.key === 'Escape') { dropdown.hidden = true; input.blur(); }
  });
  document.addEventListener('click', (e) => { if (!wrap.contains(e.target)) dropdown.hidden = true; });
  input.addEventListener('focus', () => { if (currentHits.length && input.value.trim()) dropdown.hidden = false; });
})();

// ============================================================================
// Upload (dropzone) — only present on /upload page
// ============================================================================
(() => {
  const dropzone = document.getElementById('dropzone');
  const fileInput = document.getElementById('fileInput');
  const statusEl = document.getElementById('uploadStatus');
  if (!dropzone || !fileInput || !statusEl) return;

  function setStatus(msg, kind) {
    statusEl.hidden = false;
    statusEl.className = 'upload-status' + (kind ? ' ' + kind : '');
    statusEl.textContent = msg;
  }

  async function upload(file) {
    setStatus(`Uploading ${file.name} (${(file.size / 1048576).toFixed(1)} MB)…`);
    const fd = new FormData();
    fd.append('file', file);
    try {
      const r = await fetch('/api/recordings', { method: 'POST', body: fd });
      if (!r.ok) {
        const t = await r.text();
        setStatus(`Upload failed (HTTP ${r.status}): ${t.slice(0, 300)}`, 'error');
        return;
      }
      const data = await r.json();
      setStatus(`Uploaded. Transcribing… reloading in a moment.`, 'success');
      setTimeout(() => { window.location.href = `/recordings/${data.id}`; }, 800);
    } catch (e) {
      setStatus(`Upload error: ${e.message || e}`, 'error');
    }
  }

  dropzone.addEventListener('click', () => fileInput.click());
  fileInput.addEventListener('change', () => { if (fileInput.files && fileInput.files[0]) upload(fileInput.files[0]); });
  ['dragenter','dragover'].forEach(ev => dropzone.addEventListener(ev, e => { e.preventDefault(); dropzone.classList.add('dragover'); }));
  ['dragleave','drop'].forEach(ev => dropzone.addEventListener(ev, e => { e.preventDefault(); dropzone.classList.remove('dragover'); }));
  dropzone.addEventListener('drop', e => { if (e.dataTransfer.files && e.dataTransfer.files[0]) upload(e.dataTransfer.files[0]); });
})();

// ============================================================================
// Schedule bot — only present on /meetings/new page
// ============================================================================
(() => {
  const scheduleForm = document.getElementById('scheduleForm');
  if (!scheduleForm) return;
  const scheduleStatus = document.getElementById('scheduleStatus');
  function setSchedStatus(msg, kind) {
    scheduleStatus.hidden = false;
    scheduleStatus.className = 'upload-status' + (kind ? ' ' + kind : '');
    scheduleStatus.textContent = msg;
  }
  scheduleForm.addEventListener('submit', async e => {
    e.preventDefault();
    const meetingUrl = document.getElementById('meetingUrl').value.trim();
    const botName = document.getElementById('botName').value.trim();
    setSchedStatus('Sending bot…');
    try {
      const r = await fetch('/api/meetings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ meetingUrl, botName: botName || null })
      });
      const data = await r.json();
      if (!r.ok) { setSchedStatus(`Failed (HTTP ${r.status}): ${data.error || JSON.stringify(data)}`, 'error'); return; }
      setSchedStatus('Bot scheduled. Redirecting…', 'success');
      setTimeout(() => { window.location.href = '/'; }, 800);
    } catch (err) {
      setSchedStatus('Error: ' + (err.message || err), 'error');
    }
  });
})();

// ============================================================================
// AI Chat — works for per-recording AND global chat.
// Each chat section declares its endpoint via:
//   data-chat-endpoint    (POST/GET URL)
//   data-chat-clear-endpoint (DELETE URL, optional)
// ============================================================================
(() => {
  const sections = document.querySelectorAll('.chat-section');
  if (!sections.length) return;

  sections.forEach(section => {
    const endpoint = section.dataset.chatEndpoint;
    const clearEndpoint = section.dataset.chatClearEndpoint || endpoint;
    if (!endpoint) return;

    const messagesEl = section.querySelector('.chat-messages');
    const form = section.querySelector('.chat-form');
    const input = section.querySelector('.chat-input');
    const sendBtn = section.querySelector('.chat-send');
    const clearBtn = section.querySelector('.clear-chat-btn');

    if (!messagesEl || !form || !input || !sendBtn) return;

    function appendMessage(role, content, opts) {
      const empty = messagesEl.querySelector('.chat-empty');
      if (empty) empty.remove();
      const row = document.createElement('div');
      row.className = 'chat-msg chat-msg-' + role + (opts && opts.loading ? ' chat-msg-loading' : '');
      const bubble = document.createElement('div');
      bubble.className = 'chat-bubble';
      if (opts && opts.typing) {
        bubble.innerHTML = '<span class="typing-dot"></span><span class="typing-dot"></span><span class="typing-dot"></span>';
      } else {
        bubble.textContent = content;
      }
      row.appendChild(bubble);
      messagesEl.appendChild(row);
      messagesEl.scrollTop = messagesEl.scrollHeight;
      return row;
    }

    async function sendQuestion(q) {
      appendMessage('user', q);
      const loadingRow = appendMessage('assistant', '', { loading: true, typing: true });
      sendBtn.disabled = true;
      input.disabled = true;
      try {
        const r = await fetch(endpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ question: q })
        });
        const data = await r.json();
        loadingRow.remove();
        if (!r.ok) {
          appendMessage('assistant', `(${r.status}) ${data.error || 'Request failed'}`);
        } else {
          appendMessage('assistant', data.content || '');
        }
      } catch (e) {
        loadingRow.remove();
        appendMessage('assistant', 'Network error: ' + (e.message || e));
      } finally {
        sendBtn.disabled = false;
        input.disabled = false;
        input.focus();
      }
    }

    form.addEventListener('submit', e => {
      e.preventDefault();
      const q = input.value.trim();
      if (!q) return;
      input.value = '';
      sendQuestion(q);
    });

    section.querySelectorAll('.chat-suggestion').forEach(btn => {
      btn.addEventListener('click', () => sendQuestion(btn.dataset.q));
    });

    if (clearBtn) {
      clearBtn.addEventListener('click', async () => {
        if (!confirm('Clear this conversation?')) return;
        await fetch(clearEndpoint, { method: 'DELETE' });
        window.location.reload();
      });
    }
  });
})();

// ============================================================================
// Toast notifications — window.showToast(message, type) + server flash messages
// ============================================================================
(() => {
  function ensureContainer() {
    let c = document.querySelector('.toast-container');
    if (!c) {
      c = document.createElement('div');
      c.className = 'toast-container';
      document.body.appendChild(c);
    }
    return c;
  }

  window.showToast = function (message, type, timeout) {
    if (!message) return;
    const kind = type === 'error' ? 'error' : 'success';
    const t = document.createElement('div');
    t.className = 'toast toast-' + kind;
    t.setAttribute('role', 'status');
    t.textContent = message;
    ensureContainer().appendChild(t);
    requestAnimationFrame(() => t.classList.add('toast-show'));
    const ms = timeout || 4500;
    setTimeout(() => {
      t.classList.remove('toast-show');
      setTimeout(() => t.remove(), 300);
    }, ms);
  };

  // Surface any server-rendered flash messages (e.g. Integrations connect/test) as toasts.
  document.querySelectorAll('[data-toast-message]').forEach(el => {
    window.showToast(el.getAttribute('data-toast-message'), el.getAttribute('data-toast-type') || 'success');
  });
})();

// ============================================================================
// Share Minutes of Meeting — present on /recordings/{id} when insights exist
// ============================================================================
(() => {
  const section = document.querySelector('.share-section');
  if (!section) return;
  const endpoint = section.dataset.shareEndpoint;
  const form = section.querySelector('.share-form');
  if (!form || !endpoint) return;

  const submit = form.querySelector('.share-submit');
  const emailInput = form.querySelector('input[name="email"]');
  const noteInput = form.querySelector('textarea[name="note"]');
  const slackInput = form.querySelector('input[name="slack"]');

  form.addEventListener('submit', async e => {
    e.preventDefault();
    const email = emailInput ? emailInput.value.trim() : '';
    const slack = slackInput ? slackInput.checked : false;
    if (!email && !slack) {
      window.showToast("Enter a manager's email, or enable Slack, to share.", 'error');
      return;
    }
    const originalHtml = submit.innerHTML;
    submit.disabled = true;
    submit.textContent = 'Sending…';
    try {
      const r = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: email || null, note: noteInput ? noteInput.value : null, slack })
      });
      const data = await r.json().catch(() => ({}));
      if (!r.ok) {
        window.showToast(data.error || ('Failed to send (HTTP ' + r.status + ')'), 'error');
      } else {
        window.showToast(data.message || 'Minutes of Meeting sent successfully.', 'success');
        if (noteInput) noteInput.value = '';
        if (slackInput) slackInput.checked = false;
      }
    } catch (err) {
      window.showToast('Network error: ' + (err.message || err), 'error');
    } finally {
      submit.disabled = false;
      submit.innerHTML = originalHtml;
    }
  });
})();

// ============================================================================
// Delete recording — buttons with data-delete-recording-id
// ============================================================================
(() => {
  document.querySelectorAll('[data-delete-recording-id]').forEach(btn => {
    btn.addEventListener('click', async (e) => {
      e.preventDefault();
      const id = btn.dataset.deleteRecordingId;
      const name = btn.dataset.recordingName || 'this recording';
      if (!confirm(`Delete ${name}?\n\nThis removes the audio file, transcript, AI insights, and chat history. This cannot be undone.`)) return;
      btn.disabled = true;
      try {
        const r = await fetch('/api/recordings/' + id, { method: 'DELETE' });
        if (!r.ok && r.status !== 204) {
          const t = await r.text();
          alert('Delete failed (' + r.status + '): ' + t.slice(0, 200));
          btn.disabled = false;
          return;
        }
        const redirect = btn.dataset.redirectAfter || '/';
        window.location.href = redirect;
      } catch (err) {
        alert('Delete error: ' + (err.message || err));
        btn.disabled = false;
      }
    });
  });
})();
