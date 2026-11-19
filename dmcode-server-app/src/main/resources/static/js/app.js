/* ═══════════════════════════════════════════════════════════
   DaMaoCode Web 控制台 — 前端逻辑 v3
   参考 deepseek-harness 前端实现的功能升级版：
   - 多会话管理（搜索 / 重命名 / 置顶 / 删除 / 日期分组 / 导出）
   - Markdown 渲染（marked + DOMPurify + highlight.js，代码块一键复制）
   - 流式渲染容错（孤立 ** 转义、未闭合代码围栏临时补齐）
   - 工具调用卡片（IN/OUT、实时输出、进度状态）
   - ReAct 过程展示（轮次分隔、步骤信息、状态/警告行）
   - 完成统计（步数 / 工具次数 / 耗时）、错误卡片、重新生成
   - 输入历史（↑/↓）、消息编辑回填、快捷键（⌘N/⌘B/Esc）
   - 连接心跳（30s 探测，断线顶栏红点）、消息排队、停止生成
   - 模型设置 CRUD + 连接测试、Toast、双主题（默认浅色）

   启动流程：
   1. GET /webui/bootstrap 获取运行时 token（local profile 下需要 Bearer 鉴权）
   2. 加载智能体列表 / 模型列表
   3. 会话与消息按 agentId 维度持久化于 localStorage
   4. 对话走 POST /api/v1/chat_stream，NDJSON 逐行 JSON 事件流
   ═══════════════════════════════════════════════════════════ */
'use strict';

const API = '/api/v1/';

// ───────────────────────── 错误翻译 ─────────────────────────

const ERROR_MAP = {
  'HTTP 400': '请求格式有误，请检查输入',
  'HTTP 401': '鉴权失败，请刷新页面重新获取 token',
  'HTTP 403': '无访问权限',
  'HTTP 404': '接口不存在，请检查服务版本',
  'HTTP 429': '请求过于频繁，请稍后重试',
  'HTTP 500': '服务暂时不可用，请稍后重试',
  'HTTP 502': '网关错误，请稍后重试',
  'HTTP 503': '服务不可用，请稍后重试',
};

function translateError(msg) {
  if (!msg) return '未知错误';
  for (const key in ERROR_MAP) {
    if (msg.indexOf(key) !== -1) return ERROR_MAP[key];
  }
  return msg;
}

// ───────────────────────── 全局状态 ─────────────────────────

const state = {
  token: '',
  agents: [],
  agentId: null,
  sessions: [],          // 当前智能体的会话元信息 [{id,title,createdAt,lastAt,pinned}]
  sessionId: null,
  models: [],
  modelId: null,
  streaming: false,
  abortCtrl: null,
  queue: [],             // 流式期间排队的用户消息
  messages: [],          // 当前会话消息 [{type:'user',text}|{type:'ai',...}|{type:'status',...}]
  waitTimer: null,
  waitStart: null,
  ihIndex: null,         // 输入历史游标（↑/↓ 翻历史）
  ihStash: '',           // 翻历史前的输入暂存
  pendingImages: [],     // 待发送图片 [{dataUrl, b64, mime, name}]（仅内存，不持久化）
  pendingFiles: [],      // 待发送文本文件 [{name, content}]（仅内存，不持久化）
  stick: true,           // 滚动跟随：用户位于底部附近时新内容自动下滚
  projects: [],          // 项目空间 [{id, name, dirs:[绝对路径]}]（localStorage 全局）
  currentProjectId: null,// 当前项目（null = 默认空间）
  mentionDirs: [],       // @ 选择的工程 [{name, dir, projectId}]（随当条消息发送）
  editingProjectId: null,// 项目编辑弹窗正在编辑的项目
  wsProjectId: null,     // 右侧面板当前展示的项目
  wsNodes: {},           // 目录树懒加载缓存 {path: node}
  wsActivePath: null,    // 面板当前打开的文件路径
};

// ───────────────────────── 基础工具 ─────────────────────────

const $ = (sel) => document.querySelector(sel);

function escapeHtml(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function fmtTime(ts) {
  const d = ts ? new Date(ts) : new Date();
  return d.toLocaleTimeString('zh-CN', { hour12: false });
}

function fmtRel(ts) {
  if (!ts) return '';
  const diff = (Date.now() - ts) / 1000;
  if (diff < 60) return '刚刚';
  if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前';
  if (diff < 86400) return Math.floor(diff / 3600) + ' 小时前';
  return Math.floor(diff / 86400) + ' 天前';
}

function fmtElapsed(sec) {
  return sec < 60 ? sec.toFixed(1) + 's' : Math.floor(sec / 60) + 'm' + Math.round(sec % 60) + 's';
}

function uid() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}

// ───────────────────────── Toast ─────────────────────────

function toast(msg, kind = 'ok', duration = 3200) {
  const box = $('#toast-container');
  const t = document.createElement('div');
  t.className = 'toast ' + kind;
  t.textContent = msg;
  box.appendChild(t);
  setTimeout(() => {
    t.classList.add('out');
    setTimeout(() => t.remove(), 300);
  }, duration);
}

// ───────────────────────── API ─────────────────────────

async function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (state.token) headers['Authorization'] = 'Bearer ' + state.token;
  const res = await fetch(API + path, { ...options, headers });
  if (res.status === 401) throw new Error('鉴权失败（401）：运行时 token 无效，请刷新页面');
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.json();
}

// ───────────────────────── Markdown 渲染 ─────────────────────────

if (typeof marked !== 'undefined') {
  marked.setOptions({ breaks: true, gfm: true });

  const _renderer = new marked.Renderer();
  _renderer.code = function () {
    // 兼容 marked 新旧签名：v12+ 传 token 对象，旧版传 (code, infostring)
    let code, lang;
    const a0 = arguments[0];
    if (a0 && typeof a0 === 'object') {
      code = a0.text || '';
      lang = a0.lang || '';
    } else {
      code = a0 || '';
      lang = arguments[1] || '';
    }
    const language = (lang || '').trim().split(/\s+/)[0] || 'text';
    // mermaid 代码块：输出占位容器，渲染后由 renderMermaids 转图
    if (language === 'mermaid') {
      return '<div class="code-block-wrapper mermaid-block" data-mermaid-src="'
        + escapeHtml(code) + '">'
        + '<span class="code-lang-label">mermaid</span>'
        + '<pre class="mermaid-src"><code>' + escapeHtml(code) + '</code></pre>'
        + '</div>';
    }
    let highlighted;
    try {
      if (typeof hljs !== 'undefined' && language !== 'text') {
        highlighted = hljs.getLanguage(language)
          ? hljs.highlight(code, { language }).value
          : hljs.highlightAuto(code).value;
      } else { highlighted = escapeHtml(code); }
    } catch (e) { highlighted = escapeHtml(code); }
    return '<div class="code-block-wrapper">'
      + '<span class="code-lang-label">' + escapeHtml(language) + '</span>'
      + '<button class="code-copy-btn" data-action="copy">复制</button>'
      + '<pre><code class="hljs language-' + escapeHtml(language) + '">' + highlighted + '</code></pre>'
      + '</div>';
  };
  marked.use({ renderer: _renderer });
}

function renderMarkdown(src) {
  let html;
  if (typeof marked !== 'undefined') {
    try {
      html = marked.parse(normalizeListMarkers(src || ''));
    } catch (e) {
      html = '<p>' + escapeHtml(src) + '</p>';
    }
  } else {
    html = '<p>' + escapeHtml(src).replace(/\n/g, '<br>') + '</p>';
  }
  if (typeof DOMPurify !== 'undefined') {
    html = DOMPurify.sanitize(html, { ADD_ATTR: ['target'] });
  }
  return html;
}

// 将未闭合的 `**` 加粗标记转义为字面量，避免流式中裸露显示（参考 deepseek-harness）
function neutralizeUnclosedBold(text) {
  if (!text || text.indexOf('**') === -1) return text;
  // 移除行内代码与代码块，避免误伤代码里的 `**`
  const codeSpans = [];
  let stripped = text.replace(/(`{3}[\s\S]*?`{3}|`[^`\n]*`)/g, function (m) {
    codeSpans.push(m);
    return '\u0001CODE' + (codeSpans.length - 1) + '\u0001';
  });
  const matches = stripped.match(/\*\*/g);
  if (!matches || matches.length % 2 === 0) return text; // 成对，正常
  const idx = stripped.lastIndexOf('**');
  if (idx !== -1) {
    stripped = stripped.slice(0, idx) + '\\*\\*' + stripped.slice(idx + 2);
  }
  return stripped.replace(/\u0001CODE(\d+)\u0001/g, function (_, i) { return codeSpans[+i]; });
}

// 流式渲染容错：未闭合代码围栏临时补齐 + 孤立 ** 转义（仅用于渲染，不改原始文本）
function streamingSafeMarkdown(src) {
  let out = neutralizeUnclosedBold(src);
  const fences = out.match(/```/g);
  if (fences && fences.length % 2 === 1) out += '\n```';
  return out;
}

// 双重列表标记归一化：模型常把文件名写成 "- 2. png" 这类「bullet + 序号.」形式，
// marked 会解析成错位的嵌套有序列表（外层 bullet 空悬、内容缩进掉出去）。
// 这里把 list marker 后紧跟的 "N. " 转义为字面量 "N\. "，按普通文本渲染（仅处理非代码段）。
function normalizeListMarkers(src) {
  if (!src) return src;
  const parts = String(src).split(/(`{3}[\s\S]*?`{3}|`[^`\n]*`)/g);
  for (let i = 0; i < parts.length; i += 2) { // 偶数索引为非代码段
    parts[i] = parts[i].replace(/^([ \t]*)([-*+])[ \t]+(\d+)[ \t]*\.[ \t]+/gm, '$1$2 $3\\. ');
  }
  return parts.join('');
}

// 代码复制 / 链接新窗口 —— 事件委托
document.addEventListener('click', function (e) {
  const t = e.target;
  if (!t) return;
  if (t.classList && t.classList.contains('code-copy-btn')) {
    const pre = t.parentElement && t.parentElement.querySelector('pre');
    if (pre) copyText(pre.innerText, t);
  } else if (t.tagName === 'A' && t.getAttribute('href') && t.getAttribute('href').startsWith('http')) {
    t.setAttribute('target', '_blank');
    t.setAttribute('rel', 'noopener');
  }
});

function copyText(text, btn) {
  const done = () => {
    if (!btn) return;
    const old = btn.textContent;
    btn.textContent = '✓ 已复制';
    btn.classList.add('copied');
    setTimeout(() => { btn.textContent = old; btn.classList.remove('copied'); }, 1500);
  };
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).then(done).catch(() => fallbackCopy(text, done));
  } else {
    fallbackCopy(text, done);
  }
}

function fallbackCopy(text, done) {
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.cssText = 'position:fixed;opacity:0;top:0;left:0;';
  document.body.appendChild(ta);
  ta.select();
  try { document.execCommand('copy'); done(); } catch (e) { toast('复制失败', 'err'); }
  ta.remove();
}

// ───────────────────────── 会话持久化 ─────────────────────────

function sessionsKey() { return `wc_sessions_${state.agentId}`; }
function msgsKey() { return `wc_msgs_${state.agentId}_${state.sessionId}`; }

function loadSessions() {
  state.sessions = [];
  try {
    state.sessions = JSON.parse(localStorage.getItem(sessionsKey()) || '[]') || [];
  } catch (e) { state.sessions = []; }
  // 兼容旧版单会话数据
  const legacy = localStorage.getItem(`wc_session_${state.agentId}`);
  if (legacy && !state.sessions.some(s => s.id === legacy)) {
    state.sessions.push({ id: legacy, title: '旧会话', createdAt: Date.now(), lastAt: Date.now(), pinned: false });
  }
}

function saveSessions() {
  try { localStorage.setItem(sessionsKey(), JSON.stringify(state.sessions)); } catch (e) { /* 满 */ }
}

function saveMessages() {
  if (!state.agentId || !state.sessionId) return;
  try {
    const slim = state.messages.slice(-200).map(m => {
      if (m.type === 'user') return { type: 'user', text: String(m.text).slice(0, 20000), time: m.time || null };
      if (m.type === 'status') return { type: 'status', kind: m.kind, text: m.text };
      return {
        type: 'ai', done: true,
        full: (m.full || '').slice(0, 120000),
        segs: (m.segs && m.segs.length ? m.segs : [{ kind: 'text', text: m.full || '' },
          ...(m.tools || []).map(t => ({ kind: 'tool', toolId: t.id }))])
          .map(s => s.kind === 'text'
            ? { kind: 'text', text: String(s.text || '').slice(0, 120000) }
            : { kind: 'tool', toolId: s.toolId }),
        tools: (m.tools || []).map(t => ({
          id: t.id, toolName: t.toolName, status: t.status,
          args: String(t.args || '').slice(0, 5000),
          result: String(t.result || '').slice(0, 8000),
          output: String(t.output || '').slice(0, 8000),
          duration: t.duration || null,
        })),
        summary: m.summary || null,
        stats: m.stats || null,
        error: m.error || null,
        stopped: m.stopped || null,
        time: m.time || null,
        elapsed: m.elapsed || null,
      };
    });
    localStorage.setItem(msgsKey(), JSON.stringify(slim));
  } catch (e) { /* 存储满时静默降级为仅内存 */ }
}

function loadMessages() {
  state.messages = [];
  try {
    const raw = localStorage.getItem(msgsKey());
    if (raw) state.messages = JSON.parse(raw) || [];
  } catch (e) { state.messages = []; }
}

// ───────────────────────── 会话管理 ─────────────────────────

function currentSession() {
  return state.sessions.find(s => s.id === state.sessionId) || null;
}

async function createSession() {
  const resp = await api('create_session', {
    method: 'POST',
    body: JSON.stringify({ agentId: String(state.agentId), userId: 'webui' }),
  });
  if (resp.code !== '0000') throw new Error(resp.info || '创建会话失败');
  return resp.data.sessionId;
}

async function newSession() {
  if (!state.agentId || state.streaming) return;
  try {
    const id = await createSession();
    const now = Date.now();
    state.sessions.push({ id, title: '新对话', createdAt: now, lastAt: now, pinned: false, projectId: state.currentProjectId || null });
    saveSessions();
    switchSession(id);
    toast('已创建新会话', 'ok', 1800);
  } catch (e) {
    toast('创建会话失败：' + translateError(e.message), 'err');
  }
}

function switchSession(id) {
  if (state.streaming) { toast('正在生成中，请先停止或等待完成', 'info'); renderSessionList(); return; }
  state.sessionId = id;
  localStorage.setItem(`wc_active_${state.agentId}`, id);
  loadMessages();
  rerenderAll();
  renderSessionList();
  updateSessionInfo();
}

async function deleteSession(id) {
  const s = state.sessions.find(x => x.id === id);
  if (!s) return;
  if (state.streaming && id === state.sessionId) {
    toast('当前会话正在生成中，请先停止再删除', 'info');
    return;
  }
  if (!confirm(`确定删除会话「${s.title}」？该操作不可恢复。`)) return;
  state.sessions = state.sessions.filter(x => x.id !== id);
  try { localStorage.removeItem(`wc_msgs_${state.agentId}_${id}`); } catch (e) { /* ignore */ }
  saveSessions();
  if (state.sessionId === id) {
    if (state.sessions.length) {
      switchSession(state.sessions[0].id);
    } else {
      state.sessionId = null;
      state.messages = [];
      rerenderAll();
      renderSessionList();
      updateSessionInfo();
      await ensureSession().catch(() => {});
    }
  } else {
    renderSessionList();
  }
  toast('会话已删除', 'ok', 1800);
}

function togglePin(id) {
  const s = state.sessions.find(x => x.id === id);
  if (!s) return;
  s.pinned = !s.pinned;
  saveSessions();
  renderSessionList();
}

function startRename(id) {
  const s = state.sessions.find(x => x.id === id);
  const node = document.querySelector(`.session-item[data-id="${id}"] .session-item-title`);
  if (!s || !node) return;
  const input = document.createElement('input');
  input.className = 'session-rename-input';
  input.value = s.title;
  node.replaceWith(input);
  input.focus();
  input.select();
  const commit = () => {
    const v = input.value.trim();
    if (v) { s.title = v.slice(0, 40); saveSessions(); }
    renderSessionList();
  };
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); input.blur(); }
    if (e.key === 'Escape') { input.value = ''; input.blur(); }
  });
  input.addEventListener('blur', commit);
}

function sessionGroupLabel(ts) {
  const now = new Date();
  const startOfDay = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  if (!ts || ts >= startOfDay) return '今天';
  if (ts >= startOfDay - 86400000) return '昨天';
  if (ts >= startOfDay - 6 * 86400000) return '7 天内';
  return '更早';
}

function renderSessionList() { renderSidebarList(); }

// ── 统一侧边栏：项目分组 + 项目下嵌套对话（对齐 deepseek-harness）──
function renderSidebarList() {
  const box = $('#session-list');
  if (!box) return;
  box.innerHTML = '';
  const kw = ($('#session-search').value || '').trim().toLowerCase();
  const matchKw = (s) => !kw || (s.title || '').toLowerCase().includes(kw) || s.id.includes(kw);

  // 某个项目/默认空间下的会话（仅当前展开）
  const renderSessionsOf = (projId) => {
    let list = state.sessions.filter(s => String(s.projectId || '') === String(projId || ''));
    list = list.filter(matchKw);
    list = list.slice().sort((a, b) => (b.pinned - a.pinned) || (b.lastAt || 0) - (a.lastAt || 0));
    const wrap = document.createElement('div');
    wrap.className = 'proj-sessions';
    if (!list.length) {
      const empty = document.createElement('div');
      empty.className = 'session-empty';
      empty.textContent = kw ? '无匹配会话' : '暂无对话，点 ＋ 新建';
      wrap.appendChild(empty);
      return wrap;
    }
    let lastGroup = null;
    for (const s of list) {
      const group = kw ? null : (s.pinned ? '置顶' : sessionGroupLabel(s.lastAt));
      if (group && group !== lastGroup) {
        lastGroup = group;
        const gh = document.createElement('div');
        gh.className = 'session-group-title';
        gh.textContent = group === '置顶' ? '📌 置顶' : group;
        wrap.appendChild(gh);
      }
      wrap.appendChild(buildSessionItem(s));
    }
    return wrap;
  };

  // 分组头：📁 名称 + 对话数 + ＋新建对话 + ⋯ 菜单
  const renderGroupHeader = (name, iconTxt, projId) => {
    const header = document.createElement('div');
    header.className = 'proj-group' + (String(state.currentProjectId || '') === String(projId || '') ? ' active' : '');
    const icon = document.createElement('span');
    icon.className = 'pg-icon';
    icon.textContent = iconTxt;
    const nm = document.createElement('span');
    nm.className = 'pg-name';
    nm.textContent = name;
    nm.title = name;
    const sessions = state.sessions.filter(s => String(s.projectId || '') === String(projId || ''));
    const cnt = document.createElement('span');
    cnt.className = 'pg-count';
    cnt.textContent = sessions.length ? sessions.length + ' 对话' : '';
    const actions = document.createElement('div');
    actions.className = 'pg-actions';
    const addBtn = document.createElement('button');
    addBtn.className = 'icon-btn pg-btn';
    addBtn.textContent = '＋';
    addBtn.title = '在此项目下新建对话';
    addBtn.onclick = (e) => { e.stopPropagation(); newSessionInProject(projId); };
    const moreBtn = document.createElement('button');
    moreBtn.className = 'icon-btn pg-btn';
    moreBtn.textContent = '⋯';
    moreBtn.title = '项目操作';
    moreBtn.onclick = (e) => {
      e.stopPropagation();
      const p = projId ? state.projects.find(x => x.id === projId) : null;
      toggleProjectMenu(p, moreBtn, projId || null);
    };
    actions.appendChild(addBtn);
    actions.appendChild(moreBtn);
    header.appendChild(icon);
    header.appendChild(nm);
    header.appendChild(cnt);
    header.appendChild(actions);
    header.onclick = () => switchProject(projId || null);
    return header;
  };

  // 项目分组
  for (const p of state.projects) {
    box.appendChild(renderGroupHeader(p.name, '📁', p.id));
    if (String(state.currentProjectId || '') === String(p.id)) box.appendChild(renderSessionsOf(p.id));
  }
  // 默认分组（存在无项目会话或当前处于默认空间时展示）
  const orphan = state.sessions.filter(s => !s.projectId);
  if (orphan.length || !state.currentProjectId) {
    box.appendChild(renderGroupHeader('默认', '🏠', null));
    if (!state.currentProjectId) box.appendChild(renderSessionsOf(null));
  }
  if (!state.projects.length && !orphan.length) {
    const empty = document.createElement('div');
    empty.className = 'session-empty';
    empty.textContent = '暂无项目，点击右上角 ＋ 新建';
    box.appendChild(empty);
  }
}

async function newSessionInProject(projId) {
  if (!state.agentId) { toast('服务尚未就绪', 'info'); return; }
  if (String(state.currentProjectId || '') !== String(projId || '')) {
    state.currentProjectId = projId || null;
    saveProjects();
    renderSidebarList();
  }
  await newSession();
}

// 按项目清空对话（all=true 清空全部，否则只清非今日）
function clearProjectSessions(projId, all) {
  const isToday = (t) => {
    const d = new Date(t || 0), n = new Date();
    return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate();
  };
  const before = state.sessions.length;
  state.sessions = state.sessions.filter(s => {
    if (String(s.projectId || '') !== String(projId || '')) return true;
    if (all) return false;
    return isToday(s.lastAt || s.createdAt);
  });
  const removed = before - state.sessions.length;
  if (state.sessionId && !state.sessions.some(s => s.id === state.sessionId)) {
    if (state.sessions.length) switchSession(state.sessions[0].id);
    else {
      state.sessionId = null;
      state.messages = [];
      rerenderAll();
      renderSidebarList();
      updateSessionInfo();
      ensureSession().catch(() => {});
    }
  }
  saveSessions();
  renderSidebarList();
  toast(removed ? `已清空 ${removed} 个对话` : '该项目暂无可清空的对话', 'ok', 1800);
}

function buildSessionItem(s) {
    const item = document.createElement('div');
    item.className = 'session-item' + (s.id === state.sessionId ? ' active' : '');
    item.dataset.id = s.id;

    const main = document.createElement('div');
    main.className = 'session-item-main';
    const title = document.createElement('div');
    title.className = 'session-item-title';
    title.innerHTML = (s.pinned ? '<span class="pin-mark">📌</span>' : '') + escapeHtml(s.title || '未命名');
    const sub = document.createElement('div');
    sub.className = 'session-item-sub';
    sub.textContent = fmtRel(s.lastAt);
    main.appendChild(title);
    main.appendChild(sub);

    const actions = document.createElement('div');
    actions.className = 'session-item-actions';
    const mkBtn = (txt, title_, fn) => {
      const b = document.createElement('button');
      b.className = 'icon-btn';
      b.textContent = txt;
      b.title = title_;
      b.onclick = (e) => { e.stopPropagation(); fn(); };
      return b;
    };
    actions.appendChild(mkBtn('📌', s.pinned ? '取消置顶' : '置顶', () => togglePin(s.id)));
    actions.appendChild(mkBtn('✎', '重命名', () => startRename(s.id)));
    actions.appendChild(mkBtn('🗑', '删除', () => deleteSession(s.id)));

    item.appendChild(main);
    item.appendChild(actions);
    item.onclick = () => { if (s.id !== state.sessionId) switchSession(s.id); };
    return item;
}

function updateSessionInfo() {
  const s = currentSession();
  const model = state.models.find(m => String(m.id) === String(state.modelId));
  const modelTag = model ? ` · 模型 ${model.name}` : '';
  $('#session-info').textContent = (s
    ? `会话 ${s.id.slice(0, 8)}… · ${state.messages.length} 条消息`
    : '未创建会话') + modelTag;
}

async function ensureSession() {
  if (state.sessionId) return state.sessionId;
  loadSessions();
  const savedActive = localStorage.getItem(`wc_active_${state.agentId}`);
  if (savedActive && state.sessions.some(s => s.id === savedActive)) {
    state.sessionId = savedActive;
  } else if (state.sessions.length) {
    const latest = state.sessions.slice().sort((a, b) => (b.pinned - a.pinned) || (b.lastAt || 0) - (a.lastAt || 0))[0];
    state.sessionId = latest.id;
  } else {
    const id = await createSession();
    state.sessions.push({ id, title: '新对话', createdAt: Date.now(), lastAt: Date.now(), pinned: false });
    saveSessions();
    state.sessionId = id;
  }
  localStorage.setItem(`wc_active_${state.agentId}`, state.sessionId);
  loadMessages();
  rerenderAll();
  renderSessionList();
  updateSessionInfo();
  return state.sessionId;
}

function touchSession() {
  const s = currentSession();
  if (s) { s.lastAt = Date.now(); saveSessions(); renderSessionList(); }
}

// ───────────────────────── 消息渲染 ─────────────────────────

function hideWelcome() {
  const w = $('#welcome');
  if (w) w.remove();
}

function showWelcome() {
  if (!$('#welcome') && !state.messages.length) {
    // 从 index.html 结构复制欢迎页
    const w = document.createElement('div');
    w.className = 'welcome';
    w.id = 'welcome';
    w.innerHTML = `
      <div class="welcome-icon">🤖</div>
      <div class="welcome-title">DaMaoCode Web 控制台</div>
      <div class="welcome-subtitle">AI 编码 · 审查 · 运维排障助手。输入任务，智能体会自动拆解步骤、调用工具并汇报改动。</div>
      <div class="welcome-hints">
        <span class="welcome-hint">Markdown 渲染</span>
        <span class="welcome-hint">代码高亮复制</span>
        <span class="welcome-hint">工具调用过程</span>
        <span class="welcome-hint">多会话管理</span>
      </div>
      <div class="welcome-suggestions">
        <button class="suggestion-card" data-prompt="帮我看看这个项目的结构">
          <span class="suggestion-card-title">了解项目结构</span>
          <span class="suggestion-card-desc">分析当前工作区的目录与模块组成</span>
        </button>
        <button class="suggestion-card" data-prompt="修复 UserService 里的空指针">
          <span class="suggestion-card-title">修复 Bug</span>
          <span class="suggestion-card-desc">定位并修复 UserService 中的空指针异常</span>
        </button>
        <button class="suggestion-card" data-prompt="检查服务器磁盘占用并给出清理建议">
          <span class="suggestion-card-title">运维排障</span>
          <span class="suggestion-card-desc">检查磁盘占用，给出安全的清理建议</span>
        </button>
        <button class="suggestion-card" data-prompt="审查最近一次提交的代码质量">
          <span class="suggestion-card-title">代码审查</span>
          <span class="suggestion-card-desc">审查最近提交，指出质量与安全风险</span>
        </button>
      </div>`;
    $('#messages').appendChild(w);
    w.querySelectorAll('.suggestion-card').forEach(card => {
      card.onclick = () => {
        const input = $('#input');
        input.value = card.dataset.prompt;
        input.dispatchEvent(new Event('input', { bubbles: true }));
        input.focus();
      };
    });
  }
}

function changeSummaryHtml(summary) {
  const files = (arr, cls) => (arr || []).map(f => `<div class="${cls}">${escapeHtml(f)}</div>`).join('');
  return `<div class="change-summary">
    <div class="cs-title">📋 本次改动摘要</div>
    ${summary.description ? `<div>${escapeHtml(summary.description)}</div>` : ''}
    ${files(summary.created, 'cs-add')}${files(summary.modified, 'cs-mod')}${files(summary.deleted, 'cs-del')}
  </div>`;
}

const TOOL_META = {
  execute_command: { icon: '⌨️', label: '执行命令' },
  run_command: { icon: '⌨️', label: '执行命令' },
  read_file: { icon: '📖', label: '读取文件' },
  write_file: { icon: '✏️', label: '写入文件' },
  edit_file: { icon: '🖊', label: '编辑文件' },
  apply_patch: { icon: '🩹', label: '应用补丁' },
  search_file: { icon: '🔍', label: '搜索' },
  grep: { icon: '🔍', label: '搜索' },
  glob: { icon: '📂', label: '文件匹配' },
  list_dir: { icon: '📂', label: '列出目录' },
  ssh_exec: { icon: '🖥', label: '远程执行' },
  ssh_terminal: { icon: '🖥', label: '远程终端' },
};

function toolLabel(toolName) {
  const meta = TOOL_META[String(toolName || '').toLowerCase()];
  return meta ? meta.label : (toolName || '工具');
}

function toolSummaryText(tool) {
  const args = tool.args || '';
  try {
    const o = typeof args === 'string' ? JSON.parse(args) : args;
    if (o && typeof o === 'object') {
      const v = o.command || o.path || o.file_path || o.pattern || o.query || o.dir || '';
      if (v) return firstTextLine(v).slice(0, 120);
    }
  } catch (e) { /* 非 JSON */ }
  return firstTextLine(String(args)).slice(0, 120);
}

function firstTextLine(text) {
  const v = String(text || '');
  const i = v.indexOf('\n');
  return (i === -1 ? v : v.slice(0, i)).trim();
}

function toolStateClass(tool) {
  return tool.status === 'ok' || tool.status === 'success' || tool.status === 'completed' ? 'ok'
    : tool.status === 'fail' || tool.status === 'error' || tool.status === 'failed' ? 'fail' : 'running';
}

// ── 工具组：连续工具调用压缩为一个可展开块（收起时仅一行摘要）──
function buildToolGroup() {
  const g = document.createElement('div');
  g.className = 'tool-group';
  const head = document.createElement('div');
  head.className = 'tool-group-head';
  head.setAttribute('role', 'button');
  head.onclick = () => g.classList.toggle('open');
  head.innerHTML = `<span class="tg-icon">⚙️</span>
    <span class="tg-title">工具调用</span>
    <span class="tool-dot" aria-hidden="true"></span>
    <span class="tg-detail"></span>
    <span class="tg-chevron">▸</span>`;
  const body = document.createElement('div');
  body.className = 'tool-group-body';
  g.appendChild(head);
  g.appendChild(body);
  return g;
}

// 依据组内卡片刷新组头摘要（次数 / 工具分布 / 状态点）
function updateToolGroupHead(group) {
  if (!group) return;
  const cards = [...group.querySelectorAll('.tool-card')];
  if (!cards.length) return;
  const counts = {};
  for (const c of cards) {
    const name = c.querySelector('.tool-name')?.textContent || '工具';
    counts[name] = (counts[name] || 0) + 1;
  }
  group.querySelector('.tg-title').textContent = `工具调用 · ${cards.length} 次`;
  group.querySelector('.tg-detail').textContent =
    Object.entries(counts).map(([k, v]) => v > 1 ? `${k} ×${v}` : k).join(' · ');
  const running = cards.some(c => c.dataset.state === 'running');
  const failed = cards.some(c => c.dataset.state === 'fail');
  group.dataset.state = running ? 'running' : failed ? 'fail' : 'ok';
  // 有工具在跑时自动展开（流式可见），结束后由 done 收起
  if (running) group.classList.add('open');
}

// 收起气泡内所有无运行中工具的组（done 时调用）
function collapseToolGroups(bubble) {
  if (!bubble) return;
  bubble.querySelectorAll('.tool-group').forEach(g => {
    const running = [...g.querySelectorAll('.tool-card')].some(c => c.dataset.state === 'running');
    if (!running) g.classList.remove('open');
  });
}

function buildToolCard(tool) {
  const card = document.createElement('div');
  card.className = 'tool-card';
  card._toolId = tool.id;
  const header = document.createElement('div');
  header.className = 'tool-card-header';
  header.setAttribute('role', 'button');
  header.onclick = () => card.classList.toggle('expanded');
  card.appendChild(header);
  const body = document.createElement('div');
  body.className = 'tool-card-body';
  card.appendChild(body);
  fillToolCard(card, tool);
  return card;
}

function fillToolCard(card, tool) {
  const meta = TOOL_META[(tool.toolName || '').toLowerCase()] || { icon: '🔧', label: tool.toolName || '工具' };
  const cls = toolStateClass(tool);
  card.dataset.state = cls;
  if (tool.expanded) card.classList.add('expanded');

  const header = card.querySelector('.tool-card-header');
  const summary = cls === 'fail'
    ? firstTextLine(tool.result || tool.output) || toolSummaryText(tool)  // 失败时摘要显示错误首行
    : toolSummaryText(tool);
  header.innerHTML = `<span class="tool-icon">${meta.icon}</span>
    <span class="tool-name">${escapeHtml(meta.label)}</span>
    <span class="tool-dot" aria-hidden="true"></span>
    <span class="tool-summary"></span>
    <span class="tool-chevron">▸</span>`;
  header.querySelector('.tool-summary').textContent = summary;

  const body = card.querySelector('.tool-card-body');
  body.innerHTML = '';
  if (tool.args) {
    body.appendChild(buildToolIo('IN', prettyJson(tool.args), false));
  }
  const outText = tool.result && tool.result !== tool.output
    ? (tool.output ? tool.output + '\n' : '') + prettyJson(tool.result)
    : (tool.output || tool.result || '');
  if (outText) {
    body.appendChild(buildToolIo('OUT', outText, true));
  }
  // 涉及的本地文件：渲染为可点击 chip，在右侧工程面板中查看
  const paths = extractToolFilePaths(tool);
  if (paths.length) {
    const box = document.createElement('div');
    box.className = 'tool-files';
    for (const p of paths) {
      const chip = document.createElement('button');
      chip.className = 'tool-file-chip';
      chip.title = '在右侧工程面板中查看：' + p;
      chip.innerHTML = `<span>${fileIcon(p)}</span><span>${escapeHtml(p.split('/').pop() || p)}</span>`;
      chip.onclick = (e) => {
        e.stopPropagation();
        openWorkspace();
        openWsFile(p);
      };
      box.appendChild(chip);
    }
    body.appendChild(box);
  }
  if (tool.duration) {
    const d = document.createElement('div');
    d.className = 'tool-duration';
    d.textContent = '耗时 ' + tool.duration;
    body.appendChild(d);
  }
}

// 从工具调用参数/结果中提取本地文件路径（readLocalFile/writeLocalFile/createLocalFile/readFile/writeFile 等）
function extractToolFilePaths(tool) {
  const paths = [];
  const push = (v) => {
    if (typeof v !== 'string' || !v || v.length > 500) return;
    if (!v.startsWith('/') && !/^\\\\?[A-Za-z]:\\/.test(v) && !/^[A-Za-z]:\\/.test(v)) return;
    if (!paths.includes(v)) paths.push(v);
  };
  const scan = (obj) => {
    if (!obj) return;
    if (typeof obj === 'string') {
      try { scan(JSON.parse(obj)); } catch (e) { /* 非 JSON 忽略 */ }
      return;
    }
    if (typeof obj !== 'object') return;
    for (const [k, v] of Object.entries(obj)) {
      if (['filePath', 'path', 'dirPath', 'targetPath', 'fileName'].includes(k) && typeof v === 'string') push(v);
      else if (v && typeof v === 'object') scan(v);
    }
  };
  scan(tool.args);
  scan(tool.result);
  return paths.slice(0, 6);
}

function buildToolIo(label, text, isOut) {
  const io = document.createElement('div');
  io.className = 'tool-io' + (isOut ? ' out' : '');
  const lab = document.createElement('div');
  lab.className = 'tool-io-label';
  lab.textContent = label;
  const pre = document.createElement('pre');
  pre.textContent = text;
  io.appendChild(lab);
  io.appendChild(pre);
  return io;
}

function prettyJson(s) {
  if (typeof s !== 'string') return JSON.stringify(s, null, 2);
  try { return JSON.stringify(JSON.parse(s), null, 2); } catch (e) { return s; }
}

function buildMessageEl(item) {
  if (item.type === 'status') return buildStatusEl(item);

  const wrap = document.createElement('div');
  wrap.className = 'msg ' + (item.type === 'user' ? 'user' : 'assistant');

  const avatar = document.createElement('div');
  avatar.className = 'avatar';
  avatar.textContent = item.type === 'user' ? '你' : 'W';

  const bubble = document.createElement('div');
  bubble.className = 'bubble';

  // 消息操作（复制 / 编辑 / 重新生成）
  const content = item.type === 'user' ? item.text : (item.full || '');
  if (content) {
    const actions = document.createElement('div');
    actions.className = 'msg-actions';
    if (item.type === 'user') {
      const editBtn = document.createElement('button');
      editBtn.textContent = '编辑';
      editBtn.title = '将内容填入输入框重新编辑';
      editBtn.onclick = () => {
        const input = $('#input');
        input.value = item.text;
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 240) + 'px';
        input.focus();
      };
      actions.appendChild(editBtn);
    } else if (item.done && !item.streaming) {
      const regenBtn = document.createElement('button');
      regenBtn.textContent = '重新生成';
      regenBtn.title = '用同一条提问重新生成回答';
      regenBtn.onclick = () => regenerateFrom(item);
      actions.appendChild(regenBtn);
    }
    const copyBtn = document.createElement('button');
    copyBtn.textContent = '复制';
    copyBtn.onclick = () => copyText(content, copyBtn);
    actions.appendChild(copyBtn);
    bubble.appendChild(actions);
  }

  if (item.type === 'user') {
    // 随消息发送的图片（仅当轮内存可见，刷新/重载后不显示）
    for (const img of (item.images || [])) {
      const el = document.createElement('img');
      el.className = 'msg-image';
      el.src = img.dataUrl;
      el.alt = img.name || '图片';
      el.onclick = () => window.open(img.dataUrl, '_blank');
      el.onload = () => scrollBottom(); // 图片异步加载撑高后补滚
      bubble.appendChild(el);
    }
    // 随消息发送的文本文件（仅当轮内存可见，刷新/重载后不显示内容）
    for (const f of (item.files || [])) {
      const chip = document.createElement('span');
      chip.className = 'msg-file';
      chip.title = '文件内容已随消息发送';
      chip.textContent = '📄 ' + f.name;
      bubble.appendChild(chip);
    }
    // @ 选择的工程上下文（点击打开右侧工程面板）
    for (const m of (item.mentions || [])) {
      const chip = document.createElement('span');
      chip.className = 'msg-file';
      chip.title = m.dir;
      chip.textContent = '📁 @' + m.name;
      chip.style.cursor = 'pointer';
      chip.onclick = () => openWorkspace(m.projectId);
      bubble.appendChild(chip);
    }
    if (item.text) bubble.appendChild(document.createTextNode(item.text));
    if (item.time) {
      const meta = document.createElement('div');
      meta.className = 'msg-meta user-meta';
      meta.innerHTML = `<span>${escapeHtml(fmtTime(item.time))}</span>`;
      bubble.appendChild(meta);
    }
  } else {
    // 归一化：无 segs 时合成（旧格式持久化数据 / 刚创建的流式项）
    if (!item.segs) {
      item.segs = [];
      if (item.full) item.segs.push({ kind: 'text', text: item.full });
      for (const t of item.tools || []) item.segs.push({ kind: 'tool', toolId: t.id });
    }
    if (!item.segs.length) item.segs.push({ kind: 'text', text: '' });

    let lastMd = null;
    let curGroup = null; // 连续工具收进同一组
    const lastTextSegIdx = item.segs.map(s => s.kind).lastIndexOf('text');
    item.segs.forEach((seg, i) => {
      if (seg.kind === 'text') {
        curGroup = null; // 文本打断工具组
        const md = document.createElement('div');
        md.className = 'md';
        md._seg = seg;
        renderMdInto(md, seg.text || '', item.streaming && i === lastTextSegIdx);
        bubble.appendChild(md);
        lastMd = md;
      } else {
        const t = (item.tools || []).find(x => x.id === seg.toolId);
        if (!t) return;
        if (!curGroup) { curGroup = buildToolGroup(); bubble.appendChild(curGroup); }
        curGroup.querySelector('.tool-group-body').appendChild(buildToolCard(t));
        updateToolGroupHead(curGroup);
      }
    });

    if (item.error) {
      bubble.appendChild(buildErrorCard(item.error));
    }

    if (item.summary) {
      const s = document.createElement('div');
      s.innerHTML = changeSummaryHtml(item.summary);
      bubble.appendChild(s.firstChild);
    }

    if (!item.streaming && (item.time || item.stats || item.stopped)) {
      bubble.appendChild(buildMetaEl(item));
    }
    wrap._mdEl = lastMd;
  }

  wrap.appendChild(avatar);
  wrap.appendChild(bubble);
  wrap._bubble = bubble;
  return wrap;
}

// 助手消息底部 meta：时间 · 步数 · 工具调用 · 耗时
function buildMetaEl(item) {
  const meta = document.createElement('div');
  meta.className = 'msg-meta';
  const parts = [];
  if (item.stopped === 'user') parts.push('⏹ 已停止');
  else if (item.stopped === 'max_steps') parts.push('⚠ 已达步数上限');
  else parts.push('✓ 完成');
  if (item.stats) {
    if (item.stats.steps) parts.push(item.stats.steps + ' 步');
    if (item.stats.toolCalls) parts.push('工具 ' + item.stats.toolCalls + ' 次');
  }
  if (item.elapsed != null) parts.push('用时 ' + fmtElapsed(item.elapsed));
  if (item.time) parts.push(fmtTime(item.time));
  meta.textContent = parts.join(' · ');
  return meta;
}

function buildErrorCard(msg) {
  const card = document.createElement('div');
  card.className = 'error-card';
  card.innerHTML = '<span class="error-card-icon">❌</span><span class="error-card-text"></span>';
  card.querySelector('.error-card-text').textContent = msg;
  return card;
}

function renderMdInto(el, text, streaming) {
  const src = streaming ? streamingSafeMarkdown(text || '') : (text || '');
  el.innerHTML = renderMarkdown(src) + (streaming ? '<span class="stream-cursor"></span>' : '');
  renderMermaids(el, streaming);
}

// ── mermaid 渲染：把消息里的 ```mermaid 代码块转成图 ──
let mermaidReady = false;
let mermaidSeq = 0;
function initMermaid() {
  if (mermaidReady || typeof mermaid === 'undefined') return mermaidReady;
  try {
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      theme: (document.documentElement.getAttribute('data-theme') === 'dark') ? 'dark' : 'default',
      fontFamily: 'inherit',
    });
    mermaidReady = true;
  } catch (e) { /* 初始化失败按无 mermaid 处理 */ }
  return mermaidReady;
}

// 模型常见 mermaid 笔误的自动修复：只做安全、明确的变换
function normalizeMermaidSrc(src) {
  let out = src.replace(/\r\n/g, '\n');
  // 1) 一行多条语句拆行：连线目标（非空白）后跟 2+ 空格，再跟下一个语句起点（节点ID + 连线）
  //    如 "D -- 否 --> C    D -- 是 --> E[提交]" → 拆成两行
  out = out.replace(/(\S)[ \t]{2,}(?=[A-Za-z0-9(（\[]+[ \t]*(?:--|-->|-\.-|==|-))/g, '$1\n  ');
  return out;
}

// 扫描容器内未处理的 mermaid 块并渲染；streaming 中只渲染已闭合块
async function renderMermaids(container, streaming) {
  if (!container || !initMermaid()) return;
  const blocks = container.querySelectorAll('.mermaid-block:not([data-mermaid-done])');
  for (const block of blocks) {
    const rawSrc = block.getAttribute('data-mermaid-src') || '';
    // 流式中未闭合围栏会产生空/半截代码，跳过等下一次
    if (!rawSrc.trim()) continue;
    block.setAttribute('data-mermaid-done', '1');
    let src = rawSrc;
    let repaired = false;
    try {
      await mermaid.parse(src);
    } catch (e1) {
      // 预检失败 → 尝试自动修复常见笔误，修复成功则用修复版渲染（仍失败走降级）
      const fixed = normalizeMermaidSrc(rawSrc);
      if (fixed !== rawSrc) {
        try { await mermaid.parse(fixed); src = fixed; repaired = true; } catch (e2) { /* 保持原样，走下方正常路径拿原始报错 */ }
      }
    }
    const id = 'mmd-' + (++mermaidSeq);
    try {
      const { svg } = await mermaid.render(id, src);
      const box = document.createElement('div');
      box.className = 'mermaid-box';
      box.innerHTML = svg;
      // 保留「查看源码」切换
      const toggle = document.createElement('button');
      toggle.className = 'mermaid-toggle';
      toggle.textContent = '源码';
      toggle.title = '查看 / 隐藏 Mermaid 源码';
      toggle.onclick = () => {
        const showing = box.style.display !== 'none';
        box.style.display = showing ? 'none' : '';
        toggle.textContent = showing ? '图形' : '源码';
      };
      block.innerHTML = '';
      const head = document.createElement('div');
      head.className = 'mermaid-head';
      const label = document.createElement('span');
      label.className = 'code-lang-label';
      label.textContent = 'mermaid';
      head.appendChild(label);
      if (repaired) {
        const rep = document.createElement('span');
        rep.className = 'code-lang-label';
        rep.style.color = 'var(--warn)';
        rep.textContent = '已自动修复语法';
        head.appendChild(rep);
      }
      head.appendChild(toggle);
      block.appendChild(head);
      block.appendChild(box);
      block.classList.add('rendered');
    } catch (e) {
      // 语法错误等：保留源码展示 + 错误提示，不吞内容
      block.setAttribute('data-mermaid-error', String(e && e.message || e).slice(0, 200));
      block.classList.add('mermaid-error');
    }
  }
}

function buildStatusEl(item) {
  const el = document.createElement('div');
  el.className = 'status-line' + (item.kind === 'warning' ? ' warn' : item.kind === 'error' ? ' err' : '');
  el.textContent = item.text;
  return el;
}

function scrollBottom(force) {
  const box = $('#messages');
  // stick 由 scroll 事件实时维护；用户向上翻阅时不跟随（除非 force）
  const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
  if (force || nearBottom || state.stick) {
    box.scrollTop = box.scrollHeight;
    $('#btn-scroll-bottom').hidden = true;
  } else {
    $('#btn-scroll-bottom').hidden = false;
  }
}

function appendItem(item) {
  hideWelcome();
  const box = $('#messages');
  // 贴底判断必须在追加内容之前：追加后 scrollHeight 已变大，会误判为"用户已上翻"
  const wasNear = !box.clientHeight || box.scrollHeight - box.scrollTop - box.clientHeight < 80;
  state.messages.push(item);
  const el = buildMessageEl(item);
  el._item = item;
  box.appendChild(el);
  scrollBottom(wasNear);
  updateSessionInfo();
  return el;
}

function rerenderAll() {
  const box = $('#messages');
  box.innerHTML = '';
  for (const item of state.messages) {
    const el = buildMessageEl(item);
    el._item = item;
    box.appendChild(el);
  }
  if (!state.messages.length) showWelcome();
  scrollBottom(true);
}

// ───────────────────────── 等待指示器 ─────────────────────────

function startWaitIndicator(aiItem, aiEl) {
  state.waitStart = Date.now();
  const md = aiEl._mdEl;
  md.innerHTML = `<div class="waiting-indicator">
    <span class="waiting-dots">
      <span class="waiting-dot"></span><span class="waiting-dot" style="animation-delay:0.16s"></span><span class="waiting-dot" style="animation-delay:0.32s"></span>
    </span>
    <span class="waiting-text">正在思考… <span class="wait-elapsed">0.0s</span></span>
  </div>`;
  state.waitTimer = setInterval(() => {
    const t = md.querySelector('.wait-elapsed');
    if (t) t.textContent = fmtElapsed((Date.now() - state.waitStart) / 1000);
  }, 100);
}

function stopWaitIndicator() {
  if (state.waitTimer) { clearInterval(state.waitTimer); state.waitTimer = null; }
  state.waitStart = null;
}

// ───────────────────────── 流式事件处理 ─────────────────────────

function upsertToolCard(item, el, tool) {
  // 确保工具在 segs 中有位置（工具卡按到达顺序插入文本段之间）
  item.segs = item.segs || [];
  if (!item.segs.some(s => s.kind === 'tool' && s.toolId === tool.id)) {
    item.segs.push({ kind: 'tool', toolId: tool.id });
  }
  // 定位工具所属组：segs 中该工具前最近的文本段之后如果没有新文本，则沿用上一个组
  let group = el._bubble.querySelector('.tool-group:last-of-type');
  const lastTextSegIdx = item.segs.map(s => s.kind).lastIndexOf('text');
  const thisIdx = item.segs.findIndex(s => s.kind === 'tool' && s.toolId === tool.id);
  if (thisIdx > lastTextSegIdx) {
    // 工具在最后一段文本之后 → 可复用末尾组
    if (!group) { group = buildToolGroup(); el._bubble.appendChild(group); }
  } else {
    // 工具在新文本之后 → 新开组
    group = buildToolGroup();
    el._bubble.appendChild(group);
  }
  const existing = [...group.querySelectorAll('.tool-card')].find(c => c._toolId === tool.id);
  const fresh = buildToolCard(tool);
  if (existing) {
    fresh.classList.toggle('expanded', existing.classList.contains('expanded'));
    existing.replaceWith(fresh);
  } else {
    group.querySelector('.tool-group-body').appendChild(fresh);
  }
  updateToolGroupHead(group);
  scrollBottom();
}

// 移除气泡内所有残留的流式光标（多段文本交错时，仅重渲染最后一段清不掉前面的）
function clearStreamCursors(bubble) {
  if (!bubble) return;
  bubble.querySelectorAll('.stream-cursor').forEach(c => c.remove());
}

// 确保有可写入的文本段：无段或最后一段是工具时新开一段，返回 {seg, md}
function ensureTextSeg(item, el) {
  item.segs = item.segs || [];
  let seg = item.segs[item.segs.length - 1];
  if (!seg || seg.kind !== 'text') {
    seg = { kind: 'text', text: '' };
    item.segs.push(seg);
  }
  // 找到该 seg 对应的 md DOM（按顺序数 segs 中的 text 段）
  let md = el._mdEl;
  if (!md || md._seg !== seg) {
    clearStreamCursors(el._bubble); // 结束上一段：清掉残留光标
    md = document.createElement('div');
    md.className = 'md';
    md._seg = seg;
    el._bubble.appendChild(md);
    el._mdEl = md;
  }
  return { seg, md };
}

function setStage(text) {
  const box = $('#stage-info');
  if (!box) return;
  if (!text) { box.hidden = true; return; }
  box.hidden = false;
  $('#stage-text').textContent = text;
}

function handleStreamEvent(evt, aiItem, aiEl) {
  switch (evt.event) {
    case 'text': {
      // 后端每轮携带 fullText（累计），工具边界后重置 → 每段独立累计
      const full = evt.fullText != null ? evt.fullText : (aiItem.full + (evt.content || ''));
      if (!String(full).trim()) break;
      aiItem.full = full;
      aiItem.streaming = true;
      stopWaitIndicator();
      setStage('生成回复');
      const { seg, md } = ensureTextSeg(aiItem, aiEl);
      seg.text = full;
      renderMdInto(md, seg.text, true);
      scrollBottom();
      break;
    }
    case 'tool_call': {
      stopWaitIndicator();
      const id = evt.toolCallId || ('t_' + uid());
      aiItem.tools = aiItem.tools || [];
      let tool = aiItem.tools.find(t => t.id === id);
      if (!tool) {
        tool = { id, toolName: evt.toolName || 'tool', args: '', result: '', output: '', status: 'running' };
        aiItem.tools.push(tool);
      }
      if (evt.toolName) tool.toolName = evt.toolName;
      if (evt.args != null && evt.args !== '') tool.args = typeof evt.args === 'string' ? evt.args : JSON.stringify(evt.args);
      tool.startTs = Date.now();
      const label = toolLabel(tool.toolName);
      setStage('执行工具 · ' + label);
      upsertToolCard(aiItem, aiEl, tool);
      break;
    }
    case 'tool_progress': {
      // 工具执行中进度：按 toolName 匹配最近的运行中卡片
      const tn = evt.toolName || '';
      aiItem.tools = aiItem.tools || [];
      let tool = aiItem.tools.filter(t => t.toolName === tn && t.status === 'running').pop();
      if (evt.status === 'executing') {
        if (!tool) {
          tool = { id: 'p_' + uid(), toolName: tn, args: evt.args || '', result: '', output: '', status: 'running' };
          aiItem.tools.push(tool);
          if (evt.args) tool.args = typeof evt.args === 'string' ? evt.args : JSON.stringify(evt.args);
        }
        tool.startTs = tool.startTs || Date.now();
        setStage('执行工具 · ' + toolLabel(tn));
      } else if (tool) {
        tool.status = evt.status === 'success' ? 'ok' : 'fail';
        if (evt.summary) tool.result = evt.summary;
        if (tool.startTs) tool.duration = fmtElapsed((Date.now() - tool.startTs) / 1000);
        setStage((tool.status === 'fail' ? '工具失败 · ' : '工具完成 · ') + toolLabel(tn));
      }
      if (tool) upsertToolCard(aiItem, aiEl, tool);
      break;
    }
    case 'tool_result': {
      const id = evt.toolCallId || '';
      aiItem.tools = aiItem.tools || [];
      let tool = aiItem.tools.find(t => t.id === id);
      if (!tool) {
        tool = { id: id || ('t_' + uid()), toolName: '', args: '', result: '', output: '', status: 'ok' };
        aiItem.tools.push(tool);
      }
      if (evt.content != null && evt.content !== '') tool.result = typeof evt.content === 'string' ? evt.content : JSON.stringify(evt.content);
      tool.status = evt.status === 'error' ? 'fail' : 'ok';
      if (tool.startTs) tool.duration = fmtElapsed((Date.now() - tool.startTs) / 1000);
      setStage((tool.status === 'fail' ? '工具失败 · ' : '工具完成 · ') + toolLabel(tool.toolName));
      upsertToolCard(aiItem, aiEl, tool);
      break;
    }
    case 'tool_output': {
      const id = evt.toolCallId || '';
      const tool = (aiItem.tools || []).find(t => t.id === id);
      if (tool && evt.outputChunk) {
        tool.output += evt.outputChunk;
        const existing = [...aiEl._bubble.querySelectorAll('.tool-card')].find(c => c._toolId === id);
        if (existing) fillToolCard(existing, tool);
        scrollBottom();
      }
      break;
    }
    case 'round_start': {
      const n = parseInt(evt.content, 10);
      if (n) appendItem({ type: 'status', kind: 'round', text: `── 第 ${n} 轮 ──` });
      break;
    }
    case 'round_end': {
      const info = evt.stepInfo;
      if (info) {
        appendItem({ type: 'status', kind: 'round', text: `步骤 ${info.currentStep}/${info.maxSteps} · 工具调用 ${info.totalToolCalls} 次` });
      }
      break;
    }
    case 'status': {
      if (evt.statusMessage) appendItem({ type: 'status', kind: 'status', text: 'ⓘ ' + evt.statusMessage });
      break;
    }
    case 'warning': {
      if (evt.content) appendItem({ type: 'status', kind: 'warning', text: '⚠ ' + evt.content });
      break;
    }
    case 'execute_local_command': {
      // Web 端无法执行本地命令（桌面端能力），给出提示
      appendItem({ type: 'status', kind: 'warning', text: '⚠ 服务端请求执行本地命令，Web 控制台暂不支持本地命令执行（请使用桌面版）' });
      break;
    }
    case 'done': {
      aiItem.done = true;
      stopWaitIndicator();
      setStage(null);
      collapseToolGroups(aiEl._bubble);
      let result = null;
      if (evt.content) {
        try { result = JSON.parse(evt.content); } catch (e) { result = null; }
      }
      if (result && result.content) {
        // done.content 是最后一段文本 → 写回最后一个文本段
        const { seg, md } = ensureTextSeg(aiItem, aiEl);
        seg.text = result.content;
        aiItem.full = result.content;
        renderMdInto(md, seg.text, false);
      }
      if (result) {
        aiItem.stats = {
          steps: result.totalSteps || 0,
          toolCalls: result.totalToolCalls || 0,
        };
        if (result.userStopped) aiItem.stopped = 'user';
        else if (result.maxStepsReached) aiItem.stopped = 'max_steps';
      }
      if (evt.changeSummary) aiItem.summary = evt.changeSummary;
      if (aiItem.summary) {
        const old = aiEl._bubble.querySelector('.change-summary');
        if (old) old.remove();
        const s = document.createElement('div');
        s.innerHTML = changeSummaryHtml(aiItem.summary);
        aiEl._bubble.appendChild(s.firstChild);
      }
      if (aiItem.stopped === 'max_steps') {
        appendItem({ type: 'status', kind: 'warning', text: '⚠ 已达最大执行步数，任务可能未全部完成，可发送「继续」让 Agent 接着处理' });
      }
      break;
    }
    case 'error': {
      aiItem.done = true;
      stopWaitIndicator();
      setStage(null);
      collapseToolGroups(aiEl._bubble);
      const msg = translateError(evt.content || '执行出错');
      aiItem.error = msg;
      aiEl._bubble.appendChild(buildErrorCard(msg));
      scrollBottom();
      break;
    }
    default:
      break; // heartbeat 等
  }
}

// ───────────────────────── 发送 / 流式请求 ─────────────────────────

function setStreamUi(on) {
  state.streaming = on;
  $('#btn-send').hidden = on;
  $('#btn-stop').hidden = !on;
  $('#input').disabled = on;
  if (!on) {
    $('#queue-hint').hidden = true;
    $('#input').focus();
  }
}

async function sendMessage(text, fromQueue, images, files, mentions) {
  if (state.streaming) {
    if (images && images.length) toast('已排队文本消息（图片不参与排队）', 'info');
    if (files && files.length) toast('已排队文本消息（文件不参与排队）', 'info');
    if (mentions && mentions.length) toast('已排队文本消息（工程上下文不参与排队）', 'info');
    state.queue.push(text);
    const hint = $('#queue-hint');
    hint.hidden = false;
    hint.textContent = `已排队 ${state.queue.length} 条`;
    return;
  }
  try {
    await ensureSession();
  } catch (e) {
    toast('初始化会话失败：' + translateError(e.message), 'err');
    setConnStatus(false, '会话创建失败');
    return;
  }

  appendItem({ type: 'user', text, images: images || undefined, files: files || undefined, mentions: mentions || undefined, time: Date.now() });
  scrollBottom(true); // 用户主动发消息：即使之前在上翻也回到最新
  touchSession();
  pushInputHistory(text);

  // 会话标题：取第一条用户消息
  const sess = currentSession();
  if (sess && (sess.title === '新对话' || !sess.title)) {
    sess.title = (text || (images && images.length ? '[图片]' : '[文件]')).slice(0, 24) + ((text || '').length > 24 ? '…' : '');
    saveSessions();
    renderSessionList();
  }

  streamReply(text, images, files, mentions);
}

// 用最近一次用户提问重新生成（移除该条回答后重放）
function regenerateFrom(aiItem) {
  if (state.streaming) { toast('正在生成中，请先停止', 'info'); return; }
  const idx = state.messages.indexOf(aiItem);
  if (idx === -1) return;
  let userText = null;
  for (let i = idx - 1; i >= 0; i--) {
    if (state.messages[i].type === 'user') { userText = state.messages[i].text; break; }
  }
  if (!userText) { toast('找不到可重放的用户提问', 'err'); return; }
  state.messages.splice(idx, 1);
  saveMessages();
  rerenderAll();
  streamReply(userText);
}

// ───────────────────────── 项目空间 ─────────────────────────

const PJ_KEY = 'wc_projects';
const PJ_CUR_KEY = 'wc_current_project';

function loadProjects() {
  state.projects = [];
  state.currentProjectId = null;
  try {
    state.projects = JSON.parse(localStorage.getItem(PJ_KEY) || '[]') || [];
    state.currentProjectId = localStorage.getItem(PJ_CUR_KEY) || null;
  } catch (e) { /* ignore */ }
  if (!state.projects.some(p => p.id === state.currentProjectId)) state.currentProjectId = null;
}

function saveProjects() {
  try { localStorage.setItem(PJ_KEY, JSON.stringify(state.projects)); } catch (e) { /* ignore */ }
  try {
    if (state.currentProjectId) localStorage.setItem(PJ_CUR_KEY, state.currentProjectId);
    else localStorage.removeItem(PJ_CUR_KEY);
  } catch (e) { /* ignore */ }
}

function currentProject() {
  return state.projects.find(p => p.id === state.currentProjectId) || null;
}

function renderProjectList() { renderSidebarList(); }

function switchProject(id) {
  if (state.currentProjectId === id) { renderProjectList(); renderSessionList(); return; }
  state.currentProjectId = id;
  saveProjects();
  renderProjectList();
  renderSessionList();
  // 若右侧面板开着且无指定项目，跟随切换
  if (!$('#workspace-panel').hidden && !state.wsProjectId) loadWsTree();
  toast(id ? '已切换项目' : '已回到默认空间', 'ok', 1500);
}

function deleteProject(id) {
  const p = state.projects.find(x => x.id === id);
  if (!p) return;
  if (!confirm(`确定删除项目「${p.name}」？会话保留并归入「默认」分组。`)) return;
  state.projects = state.projects.filter(x => x.id !== id);
  let moved = 0;
  for (const s of state.sessions) {
    if (String(s.projectId || '') === String(id)) { s.projectId = null; moved++; }
  }
  if (state.currentProjectId === id) state.currentProjectId = null;
  saveProjects();
  saveSessions();
  renderSidebarList();
  toast(moved ? `项目已删除，${moved} 个会话归入默认分组` : '项目已删除', 'ok', 1800);
}

function openProjectModal(id) {
  state.editingProjectId = id || null;
  const p = id ? state.projects.find(x => x.id === id) : null;
  $('#pm-title').textContent = p ? '重命名项目' : '新建项目';
  $('#pm-name').value = p ? p.name : '';
  $('#project-modal-mask').hidden = false;
  setTimeout(() => $('#pm-name').focus(), 50);
}

function closeProjectModal() {
  $('#project-modal-mask').hidden = true;
  state.editingProjectId = null;
}

function saveProjectModal() {
  const name = ($('#pm-name').value || '').trim();
  if (!name) { toast('请填写项目名称', 'info'); return; }
  if (state.editingProjectId) {
    const p = state.projects.find(x => x.id === state.editingProjectId);
    if (p) p.name = name;
  } else {
    const p = { id: uid(), name, dirs: [] };
    state.projects.push(p);
    state.currentProjectId = p.id;
  }
  saveProjects();
  closeProjectModal();
  renderProjectList();
  renderSessionList();
  toast(state.editingProjectId ? '项目已保存' : '项目已创建，可通过 ⋯ 关联工程', 'ok', 2200);
}

// ── 项目 ⋯ 菜单 ──
let projectMenuEl = null;

function closeProjectMenu() {
  if (!projectMenuEl) return;
  projectMenuEl.remove();
  projectMenuEl = null;
  document.removeEventListener('click', onDocClickCloseMenu);
  document.removeEventListener('contextmenu', onDocClickCloseMenu);
}

function onDocClickCloseMenu(e) {
  if (projectMenuEl && !projectMenuEl.contains(e.target)) closeProjectMenu();
}

function toggleProjectMenu(p, anchor, projId) {
  projId = projId !== undefined ? projId : (p ? p.id : null);
  if (projectMenuEl && projectMenuEl.dataset.pid === String(projId || '')) { closeProjectMenu(); return; }
  closeProjectMenu();
  const menu = document.createElement('div');
  menu.className = 'project-menu';
  menu.dataset.pid = String(projId || '');
  const divider = () => {
    const d = document.createElement('div');
    d.className = 'pm-divider';
    menu.appendChild(d);
  };
  const mi = (icon, label, fn, cls) => {
    const b = document.createElement('button');
    b.className = 'pm-item' + (cls ? ' ' + cls : '');
    b.innerHTML = `<span class="pm-ico">${icon}</span><span class="pm-label">${label}</span>`;
    b.onclick = (e) => { e.stopPropagation(); closeProjectMenu(); fn(); };
    return b;
  };
  // 新建对话（对齐 deepseek-harness：项目菜单首位）
  menu.appendChild(mi('💬', '新建对话', () => newSessionInProject(projId)));
  if (p) {
    menu.appendChild(mi('✎', '重命名', () => openProjectModal(p.id)));
    const dirs = p.dirs || [];
    menu.appendChild(mi('🗂', '打开工程文件', () => {
      if (state.currentProjectId !== projId) { state.currentProjectId = projId; saveProjects(); renderSidebarList(); }
      if (dirs.length) openWorkspace(p.id);
      else openDirModal(p.id);
    }));
    const sec = document.createElement('div');
    sec.className = 'pm-section';
    sec.textContent = `关联的工程（${dirs.length}）`;
    menu.appendChild(sec);
    if (dirs.length) {
      for (const d of dirs) {
        const row = document.createElement('button');
        row.className = 'pm-dir';
        row.title = `${d}\n点击取消关联`;
        const sp = document.createElement('span');
        sp.className = 'pm-dir-path';
        sp.textContent = d;
        const x = document.createElement('span');
        x.className = 'pm-dir-x';
        x.textContent = '✕';
        row.appendChild(sp);
        row.appendChild(x);
        row.onclick = (e) => { e.stopPropagation(); unbindProjectDir(p.id, d); };
        menu.appendChild(row);
      }
    } else {
      const none = document.createElement('div');
      none.className = 'pm-empty';
      none.textContent = '尚未关联工程目录';
      menu.appendChild(none);
    }
    menu.appendChild(mi('➕', '关联本地文件夹…', () => openDirModal(p.id), 'accent'));
    divider();
    menu.appendChild(mi('🧹', '清空非今日对话', () => clearProjectSessions(projId, false)));
    menu.appendChild(mi('🗑', '清空全部对话', () => {
      if (confirm(`确定清空项目「${p.name}」下全部对话？不可恢复。`)) clearProjectSessions(projId, true);
    }, 'danger'));
    menu.appendChild(mi('🗑', '删除项目', () => deleteProject(p.id), 'danger'));
  } else {
    divider();
    menu.appendChild(mi('🧹', '清空非今日对话', () => clearProjectSessions(null, false)));
    menu.appendChild(mi('🗑', '清空全部对话', () => {
      if (confirm('确定清空默认分组下全部对话？不可恢复。')) clearProjectSessions(null, true);
    }, 'danger'));
  }
  document.body.appendChild(menu);
  const r = anchor.getBoundingClientRect();
  menu.style.visibility = 'hidden';
  requestAnimationFrame(() => {
    const mw = menu.offsetWidth, mh = menu.offsetHeight;
    let x = r.right + 6, y = r.top;
    if (x + mw > window.innerWidth - 8) x = Math.max(8, r.left - mw - 6);
    if (y + mh > window.innerHeight - 8) y = Math.max(8, window.innerHeight - mh - 8);
    menu.style.left = x + 'px';
    menu.style.top = y + 'px';
    menu.style.visibility = '';
  });
  projectMenuEl = menu;
  setTimeout(() => {
    document.addEventListener('click', onDocClickCloseMenu);
    document.addEventListener('contextmenu', onDocClickCloseMenu);
  }, 0);
}

function unbindProjectDir(id, dir) {
  const p = state.projects.find(x => x.id === id);
  if (!p) return;
  p.dirs = (p.dirs || []).filter(d => d !== dir);
  saveProjects();
  closeProjectMenu();
  renderProjectList();
  if (!$('#workspace-panel').hidden && state.wsProjectId === id) { renderWsProjectSelect(); loadWsTree(); }
  toast('已取消关联：' + dir.split('/').filter(Boolean).pop(), 'ok', 1800);
}

// ── 关联工程弹窗 ──
let dmDirBackup = [];

function openDirModal(id) {
  const p = state.projects.find(x => x.id === id);
  if (!p) return;
  state.editingProjectId = id;
  dmDirBackup = (p.dirs || []).slice();
  $('#dm-name').textContent = p.name;
  renderDmDirList();
  $('#dir-modal-mask').hidden = false;
}

function closeDirModal() {
  $('#dir-modal-mask').hidden = true;
  state.editingProjectId = null;
}

function renderDmDirList() {
  const box = $('#dm-list');
  box.innerHTML = '';
  if (!dmDirBackup.length) {
    const none = document.createElement('div');
    none.className = 'dm-empty';
    none.textContent = '尚未关联工程目录，点击下方按钮选择';
    box.appendChild(none);
    return;
  }
  dmDirBackup.forEach((d, i) => {
    const row = document.createElement('div');
    row.className = 'dm-row';
    const sp = document.createElement('span');
    sp.className = 'dm-path';
    sp.textContent = d;
    sp.title = d;
    const x = document.createElement('button');
    x.className = 'icon-btn dm-x';
    x.textContent = '✕';
    x.title = '取消关联';
    x.onclick = () => { dmDirBackup.splice(i, 1); renderDmDirList(); };
    row.appendChild(sp);
    row.appendChild(x);
    box.appendChild(row);
  });
}

// ── 本地文件夹选择弹窗（后端逐级浏览，参考 deepseek-harness showDirectoryPicker）──
let dirPickerMask = null;

function closeDirPicker() {
  if (dirPickerMask) { dirPickerMask.remove(); dirPickerMask = null; }
}

function showDirPicker(onPick) {
  closeDirPicker();
  const mask = document.createElement('div');
  mask.className = 'dirp-mask';
  const modal = document.createElement('div');
  modal.className = 'dirp-modal';

  const header = document.createElement('div');
  header.className = 'dirp-header';
  const h2 = document.createElement('h2');
  h2.textContent = '选择本地文件夹';
  const closeBtn = document.createElement('button');
  closeBtn.className = 'icon-btn';
  closeBtn.textContent = '✕';
  closeBtn.title = '关闭 (Esc)';
  closeBtn.onclick = closeDirPicker;
  header.appendChild(h2);
  header.appendChild(closeBtn);

  let current = localStorage.getItem('wc_last_pick_dir') || '';

  const pathBar = document.createElement('div');
  pathBar.className = 'dirp-pathbar';
  const upBtn = document.createElement('button');
  upBtn.className = 'icon-btn dirp-up';
  upBtn.textContent = '↑';
  upBtn.title = '上一级';
  const pathTxt = document.createElement('span');
  pathTxt.className = 'dirp-path';
  pathBar.appendChild(upBtn);
  pathBar.appendChild(pathTxt);

  const list = document.createElement('div');
  list.className = 'dirp-list';

  const footer = document.createElement('div');
  footer.className = 'dirp-footer';
  const cancelBtn = document.createElement('button');
  cancelBtn.className = 'btn btn-ghost';
  cancelBtn.textContent = '取消';
  cancelBtn.onclick = closeDirPicker;
  const pickBtn = document.createElement('button');
  pickBtn.className = 'btn btn-primary';
  pickBtn.textContent = '选择当前目录';
  footer.appendChild(cancelBtn);
  footer.appendChild(pickBtn);

  modal.appendChild(header);
  modal.appendChild(pathBar);
  modal.appendChild(list);
  modal.appendChild(footer);
  mask.appendChild(modal);
  document.body.appendChild(mask);
  dirPickerMask = mask;
  mask.addEventListener('click', (e) => { if (e.target === mask) closeDirPicker(); });

  async function load(path) {
    list.innerHTML = '';
    const tip = document.createElement('div');
    tip.className = 'dirp-tip';
    tip.textContent = '加载中…';
    list.appendChild(tip);
    pathTxt.textContent = current || '（主目录）';
    pathTxt.title = current;
    try {
      const resp = await api('fs/list?path=' + encodeURIComponent(path));
      if (resp.code !== '0000') throw new Error(resp.info || '读取失败');
      list.innerHTML = '';
      const dirs = (resp.data || []).filter(d => d.dir);
      if (!dirs.length) {
        const empty = document.createElement('div');
        empty.className = 'dirp-tip';
        empty.textContent = '无子目录';
        list.appendChild(empty);
      }
      for (const d of dirs) {
        const item = document.createElement('button');
        item.className = 'dirp-item';
        const ico = document.createElement('span');
        ico.className = 'dirp-ico';
        ico.textContent = '📁';
        const nm = document.createElement('span');
        nm.className = 'dirp-name';
        nm.textContent = d.name;
        item.appendChild(ico);
        item.appendChild(nm);
        item.title = d.path;
        item.onclick = () => { current = d.path; load(current); };
        list.appendChild(item);
      }
    } catch (e) {
      list.innerHTML = '';
      const err = document.createElement('div');
      err.className = 'dirp-tip';
      err.textContent = '读取失败：' + e.message;
      list.appendChild(err);
    }
  }

  upBtn.onclick = () => {
    if (!current) { toast('已在最上层', 'info', 1200); return; }
    const trimmed = current.replace(/\/+$/, '');
    const parent = trimmed.split('/').slice(0, -1).join('/');
    current = parent || '/';
    load(current);
  };

  pickBtn.onclick = () => {
    if (!current) { toast('请先进入一个目录再选择', 'info'); return; }
    localStorage.setItem('wc_last_pick_dir', current);
    closeDirPicker();
    onPick(current);
  };

  load(current);
}

function saveDirModal() {
  const p = state.projects.find(x => x.id === state.editingProjectId);
  if (!p) { closeDirModal(); return; }
  p.dirs = dmDirBackup.slice();
  saveProjects();
  closeDirModal();
  renderProjectList();
  if (!$('#workspace-panel').hidden && state.wsProjectId === p.id) { renderWsProjectSelect(); loadWsTree(); }
  toast('关联已更新', 'ok', 1600);
}

// ───────────────────────── 右侧工程文件面板 ─────────────────────────

function openWorkspace(projectId) {
  const panel = $('#workspace-panel');
  panel.hidden = false;
  state.wsProjectId = projectId !== undefined ? projectId : (state.wsProjectId || state.currentProjectId);
  renderWsProjectSelect();
  loadWsTree();
}

function closeWorkspace() {
  $('#workspace-panel').hidden = true;
  showWsTree();
}

function renderWsProjectSelect() {
  const sel = $('#ws-project-select');
  sel.innerHTML = '';
  const opts = state.projects.filter(p => (p.dirs || []).length);
  if (!opts.length) {
    const o = document.createElement('option');
    o.value = '';
    o.textContent = '暂无项目（先在左侧新建）';
    sel.appendChild(o);
    sel.disabled = true;
    return;
  }
  sel.disabled = false;
  for (const p of opts) {
    const o = document.createElement('option');
    o.value = p.id;
    o.textContent = p.name;
    sel.appendChild(o);
  }
  if (!opts.some(p => p.id === state.wsProjectId)) state.wsProjectId = opts[0].id;
  sel.value = state.wsProjectId;
}

async function loadWsTree() {
  const box = $('#ws-tree');
  const proj = state.projects.find(p => p.id === state.wsProjectId);
  box.innerHTML = '';
  if (!proj || !(proj.dirs || []).length) {
    const empty = document.createElement('div');
    empty.className = 'ws-tree-empty';
    empty.textContent = '该项目未配置工程目录';
    box.appendChild(empty);
    return;
  }
  showWsTree();
  for (const dir of proj.dirs) {
    const node = await fetchWsNode(dir, 2);
    if (!node) {
      const err = document.createElement('div');
      err.className = 'ws-tree-empty';
      err.textContent = `无法读取：${dir}`;
      box.appendChild(err);
      continue;
    }
    box.appendChild(buildWsNode(node, true));
  }
}

async function fetchWsNode(path, depth) {
  try {
    const resp = await api(`fs/tree?path=${encodeURIComponent(path)}&depth=${depth}`);
    if (resp.code !== '0000') { toast(translateError(resp.info || '目录读取失败'), 'err'); return null; }
    state.wsNodes[resp.data.path] = resp.data;
    return resp.data;
  } catch (e) {
    toast(translateError(e.message), 'err');
    return null;
  }
}

function buildWsNode(node, expanded) {
  const wrap = document.createElement('div');
  wrap.className = 'ws-node';
  const row = document.createElement('div');
  row.className = 'ws-node-row';
  const arrow = document.createElement('span');
  arrow.className = 'ws-node-arrow';
  const icon = document.createElement('span');
  icon.className = 'ws-node-icon';
  const name = document.createElement('span');
  name.className = 'ws-node-name';
  row.appendChild(arrow);
  row.appendChild(icon);
  row.appendChild(name);

  if (node.dir) {
    arrow.textContent = expanded ? '▾' : '▸';
    icon.textContent = '📁';
    name.textContent = node.name;
    row.onclick = () => toggleWsDir(node, wrap);
  } else {
    arrow.textContent = '';
    icon.textContent = fileIcon(node.name);
    name.textContent = node.name;
    name.title = node.path;
    row.onclick = () => openWsFile(node.path);
    row.dataset.filePath = node.path;
  }
  wrap.appendChild(row);
  const childrenBox = document.createElement('div');
  childrenBox.className = 'ws-node-children';
  childrenBox.hidden = !expanded;
  wrap.appendChild(childrenBox);
  if (node.dir && expanded) fillWsChildren(node, wrap, childrenBox);
  return wrap;
}

function fillWsChildren(node, wrap, childrenBox) {
  childrenBox.innerHTML = '';
  const kids = node.children || [];
  const javaPkgMode = wsUnderJavaPath(node.path);
  if (!kids.length) {
    const e = document.createElement('div');
    e.className = 'ws-tree-empty';
    e.textContent = '（空目录）';
    childrenBox.appendChild(e);
    return;
  }
  for (const kid of kids) {
    if (kid.dir && kid.lazy && !javaPkgMode) {
      // 深度截断：懒加载
      const row = document.createElement('div');
      row.className = 'ws-node-row ws-lazy-row';
      row.innerHTML = '<span class="ws-node-arrow"></span><span class="ws-node-icon">📁</span>';
      const label = document.createElement('span');
      label.textContent = kid.name;
      row.appendChild(label);
      const loadLazy = async () => {
        row.onclick = null;
        label.textContent = kid.name + '（加载中…）';
        const full = await fetchWsNode(kid.path, 2);
        if (full) {
          full.name = kid.name; // 后端根节点 name 为完整路径，还原为目录名
          const sub = buildWsNode(full, true);
          // 懒加载行直接挂在 childrenBox 下，替换行自身即可
          childrenBox.replaceChild(sub, row);
        } else {
          // 失败恢复：还原文案并允许重试
          label.textContent = kid.name;
          row.onclick = loadLazy;
        }
      };
      row.onclick = loadLazy;
      childrenBox.appendChild(row);
    } else if (kid.dir && javaPkgMode) {
      // Java 包目录：单链子包合并为一行（cn.bugstack.ai）
      const el = buildWsNode(kid, false);
      childrenBox.appendChild(el);
      resolvePkgChain(kid).then(({ label, deep }) => {
        if (!deep || label === kid.name) return; // 无可合并链
        if (!el.parentNode) return; // 树已被刷新
        const pseudo = {
          name: label, path: deep.path, dir: true,
          children: deep.children || [], lazy: deep.lazy,
        };
        const merged = buildWsNode(pseudo, false);
        merged.classList.add('ws-pkg-node');
        childrenBox.replaceChild(merged, el);
      }).catch(() => {});
    } else {
      childrenBox.appendChild(buildWsNode(kid, false));
    }
  }
}

// 是否处于 Java 源码包路径下（src/main/java 或 src/test/java 之内）
function wsUnderJavaPath(path) {
  return /\/src\/(main|test)\/java(\/|$)/.test(path || '');
}

// 沿单子目录链向下合并：返回 { label: 'cn.bugstack.ai', deep: 最深层节点 }
async function resolvePkgChain(kid) {
  let label = kid.name;
  let cur = kid;
  while (cur && !cur.lazy) {
    const kids = cur.children || [];
    const dirs = kids.filter(c => c.dir);
    const files = kids.filter(c => !c.dir);
    if (dirs.length === 1 && files.length === 0) {
      const only = dirs[0];
      label += '.' + only.name;
      // 链继续向下：取下一层数据（缓存命中则不请求）
      let next = state.wsNodes[only.path];
      if (!next && only.children && only.children.length) next = only;
      if (!next) {
        next = await fetchWsNode(only.path, 2);
        if (!next) return { label, deep: cur };
      }
      if (next.name === next.path) next.name = only.name;
      cur = next;
    } else {
      return { label, deep: cur };
    }
  }
  return { label, deep: cur };
}

async function toggleWsDir(node, wrap) {
  const childrenBox = wrap.querySelector('.ws-node-children');
  const row = wrap.querySelector('.ws-node-row');
  const arrow = row.querySelector('.ws-node-arrow');
  if (childrenBox.hidden) {
    if (node.lazy && !(node.children || []).length) {
      const full = await fetchWsNode(node.path, 2);
      if (!full) return;
      node.children = full.children;
      node.lazy = false;
    }
    fillWsChildren(node, wrap, childrenBox);
    childrenBox.hidden = false;
    arrow.textContent = '▾';
  } else {
    childrenBox.hidden = true;
    arrow.textContent = '▸';
  }
}

// ── 工程面板拖拽：视图/目录分栏宽度 + 面板整体宽度（均持久化）──
function initWsDrag() {
  const panel = $('#workspace-panel');
  const splitter = $('#ws-splitter');
  const resizer = $('#ws-resizer');
  const tree = $('#ws-tree');
  // 恢复历史宽度
  const savedPanelW = parseInt(localStorage.getItem('wc_ws_panel_w') || '0', 10);
  if (savedPanelW >= 360) panel.style.width = savedPanelW + 'px';
  const savedTreeW = parseInt(localStorage.getItem('wc_ws_tree_w') || '0', 10);
  if (savedTreeW >= 140) tree.style.width = savedTreeW + 'px';

  const drag = (el, onMove, onEnd) => {
    el.addEventListener('mousedown', (e) => {
      e.preventDefault();
      el.classList.add('dragging');
      document.body.style.userSelect = 'none';
      document.body.style.cursor = 'col-resize';
      const move = (ev) => onMove(ev);
      const up = () => {
        el.classList.remove('dragging');
        document.body.style.userSelect = '';
        document.body.style.cursor = '';
        document.removeEventListener('mousemove', move);
        document.removeEventListener('mouseup', up);
        if (onEnd) onEnd();
      };
      document.addEventListener('mousemove', move);
      document.addEventListener('mouseup', up);
    });
  };

  // 文件视图 ↔ 工程树 分栏（拖 splitter 调树宽）
  drag(splitter, (ev) => {
    const bodyRect = splitter.parentElement.getBoundingClientRect();
    let w = bodyRect.right - ev.clientX;
    w = Math.max(140, Math.min(w, bodyRect.width - 240));
    tree.style.width = w + 'px';
  }, () => {
    localStorage.setItem('wc_ws_tree_w', String(parseInt(tree.style.width, 10) || 215));
  });

  // 面板整体宽度（拖左缘）
  drag(resizer, (ev) => {
    let w = window.innerWidth - ev.clientX;
    w = Math.max(360, Math.min(w, Math.round(window.innerWidth * 0.85)));
    panel.style.width = w + 'px';
  }, () => {
    localStorage.setItem('wc_ws_panel_w', String(parseInt(panel.style.width, 10) || 520));
  });
}

function showWsTree() {
  $('#ws-tree').hidden = false;
  $('#ws-viewer').hidden = true;
  const sp = $('#ws-splitter');
  if (sp) sp.hidden = true;
  state.wsActivePath = null;
  document.querySelectorAll('.ws-node-row.active').forEach(r => r.classList.remove('active'));
}

const WS_TEXT_EXTS = new Set(('txt md markdown rst js ts jsx tsx mjs cjs java py go rs c h cpp hpp cc cs php rb sh bash zsh json xml yml yaml toml ini conf properties csv tsv log html htm css scss less sql kt scala swift vue svelte gradle bat ps1 proto graphql tf lock gitignore').split(' '));

function fileIcon(name) {
  const ext = (name || '').split('.').pop().toLowerCase();
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'ico'].includes(ext)) return '🖼';
  if (['js', 'ts', 'jsx', 'tsx', 'mjs', 'cjs'].includes(ext)) return '📜';
  if (['java', 'kt', 'scala'].includes(ext)) return '☕';
  if (['py'].includes(ext)) return '🐍';
  if (['go'].includes(ext)) return '🐹';
  if (['rs'].includes(ext)) return '🦀';
  if (['md', 'markdown', 'rst', 'txt', 'log'].includes(ext)) return '📄';
  if (['json', 'yml', 'yaml', 'toml', 'ini', 'conf', 'properties'].includes(ext)) return '⚙️';
  if (['html', 'htm', 'css', 'scss', 'less'].includes(ext)) return '🌐';
  if (['zip', 'tar', 'gz', 'jar', 'class', 'exe', 'bin', 'pdf'].includes(ext)) return '📦';
  return '📄';
}

function hljsLangOf(name) {
  const ext = (name || '').split('.').pop().toLowerCase();
  const map = { js: 'javascript', ts: 'typescript', jsx: 'javascript', tsx: 'typescript', mjs: 'javascript', cjs: 'javascript',
    py: 'python', java: 'java', kt: 'kotlin', go: 'go', rs: 'rust', c: 'c', h: 'c', cpp: 'cpp', hpp: 'cpp', cc: 'cpp',
    cs: 'csharp', rb: 'ruby', php: 'php', sh: 'bash', bash: 'bash', zsh: 'bash', json: 'json', xml: 'xml',
    yml: 'yaml', yaml: 'yaml', html: 'xml', htm: 'xml', css: 'css', scss: 'scss', less: 'less', sql: 'sql',
    md: 'markdown', csv: 'plaintext', toml: 'ini', ini: 'ini', properties: 'properties', swift: 'swift',
    vue: 'xml', svelte: 'xml', log: 'plaintext', txt: 'plaintext' };
  return map[ext] || 'plaintext';
}

async function openWsFile(path) {
  const resp = await api('fs/file?path=' + encodeURIComponent(path));
  if (resp.code !== '0000') { toast(translateError(resp.info || '文件读取失败'), 'err'); return; }
  const f = resp.data;
  state.wsActivePath = path;
  $('#ws-tree').hidden = false;
  $('#ws-viewer').hidden = false;
  const sp = $('#ws-splitter');
  if (sp) sp.hidden = false;
  $('#ws-file-name').textContent = path;
  $('#ws-file-name').title = path;
  $('#ws-file-download').href = API + 'fs/raw?path=' + encodeURIComponent(path) + '&download=true';
  document.querySelectorAll('.ws-node-row.active').forEach(r => r.classList.remove('active'));
  document.querySelectorAll(`.ws-node-row[data-file-path]`).forEach(r => {
    if (r.dataset.filePath === path) r.classList.add('active');
  });
  const box = $('#ws-file-content');
  box.innerHTML = '';
  const ext0 = (f.name || '').split('.').pop().toLowerCase();
  // PDF 走浏览器内嵌预览（即使是二进制也可直接渲染）
  if (ext0 === 'pdf') {
    const frame = document.createElement('iframe');
    frame.className = 'ws-pdf';
    frame.src = API + 'fs/raw?path=' + encodeURIComponent(path);
    box.appendChild(frame);
    return;
  }
  if (f.binary) {
    const ext = ext0;
    if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'ico'].includes(ext)) {
      const img = document.createElement('img');
      img.className = 'ws-image';
      img.src = API + 'fs/raw?path=' + encodeURIComponent(path);
      box.appendChild(img);
    } else {
      const tip = document.createElement('div');
      tip.className = 'ws-binary-tip';
      tip.innerHTML = `📦 二进制文件（${fmtSize(f.size)}）<br><br><a href="${API}fs/raw?path=${encodeURIComponent(path)}&download=true">⤓ 下载查看</a>`;
      box.appendChild(tip);
    }
    return;
  }
  const ext = ext0;
  if (ext === 'md' || ext === 'markdown') {
    const md = document.createElement('div');
    md.className = 'ws-md md';
    md.innerHTML = renderMarkdown(f.content || '');
    box.appendChild(md);
    return;
  }
  // CSV / TSV 渲染为表格
  if (ext === 'csv' || ext === 'tsv') {
    box.appendChild(buildWsTable(f.content || '', ext === 'tsv' ? '\t' : ','));
    return;
  }
  // 代码 / 文本：highlight.js 高亮 + 行号槽
  const wrap = document.createElement('div');
  wrap.className = 'ws-code-wrap';
  const gutter = document.createElement('div');
  gutter.className = 'ws-gutter';
  const lineCount = (f.content || '').split('\n').length;
  let nums = '';
  for (let i = 1; i <= lineCount; i++) nums += i + '\n';
  gutter.textContent = nums;
  const pre = document.createElement('pre');
  pre.className = 'code-block';
  const code = document.createElement('code');
  const lang = hljsLangOf(f.name);
  if (typeof hljs !== 'undefined') {
    try {
      const r = hljs.highlight(f.content || '', { language: lang, ignoreIllegals: true });
      code.innerHTML = r.value;
    } catch (e) { code.textContent = f.content || ''; }
  } else {
    code.textContent = f.content || '';
  }
  pre.appendChild(code);
  wrap.appendChild(gutter);
  wrap.appendChild(pre);
  box.appendChild(wrap);
  if (f.truncated) {
    const tip = document.createElement('div');
    tip.className = 'ws-binary-tip';
    tip.textContent = `文件较大，仅显示前 ${Math.round(MAX_WS_TEXT / 1024)}KB（可下载查看全部）`;
    box.appendChild(tip);
  }
}

// CSV/TSV → 表格（支持引号包裹与转义）
function buildWsTable(text, delim) {
  const wrap = document.createElement('div');
  wrap.className = 'ws-table-wrap';
  const rows = [];
  let row = [], cell = '', inQ = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inQ) {
      if (c === '"') {
        if (text[i + 1] === '"') { cell += '"'; i++; }
        else inQ = false;
      } else cell += c;
    } else if (c === '"') inQ = true;
    else if (c === delim) { row.push(cell); cell = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(cell); cell = '';
      if (row.length > 1 || row[0] !== '') rows.push(row);
      row = [];
      if (rows.length >= 500) break; // 最多渲染 500 行
    } else cell += c;
  }
  if (cell !== '' || row.length) { row.push(cell); rows.push(row); }
  const table = document.createElement('table');
  table.className = 'ws-table';
  rows.forEach((r, ri) => {
    const tr = document.createElement('tr');
    for (const c of r) {
      const td = document.createElement(ri === 0 ? 'th' : 'td');
      td.textContent = c;
      td.title = c;
      tr.appendChild(td);
    }
    table.appendChild(tr);
  });
  wrap.appendChild(table);
  if (rows.length >= 500) {
    const tip = document.createElement('div');
    tip.className = 'ws-binary-tip';
    tip.textContent = '表格较大，仅显示前 500 行（可下载查看全部）';
    wrap.appendChild(tip);
  }
  return wrap;
}

const MAX_WS_TEXT = 300 * 1024;
function fmtSize(n) {
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1024 / 1024).toFixed(1) + ' MB';
}

// ───────────────────────── @ 工程选择 ─────────────────────────

function renderMentionPop(filter) {
  const pop = $('#mention-pop');
  pop.innerHTML = '';
  const kw = (filter || '').toLowerCase();
  const cur = currentProject();
  const groups = [
    { label: cur ? `当前项目 · ${cur.name}` : '当前项目（默认空间，未选择项目）', items: [] },
    { label: '其他项目', items: [] },
  ];
  for (const p of state.projects) {
    for (const dir of (p.dirs || [])) {
      const item = { name: p.name, dir, projectId: p.id };
      const hit = !kw || p.name.toLowerCase().includes(kw) || dir.toLowerCase().includes(kw);
      if (!hit) continue;
      if (cur && p.id === cur.id) groups[0].items.push(item);
      else groups[1].items.push(item);
    }
  }
  if (!cur) { // 无当前项目时全部归入"其他项目"之上展示为可选工程
    groups[0].label = '全部工程';
    groups[1].items = [];
  }
  let any = false;
  for (const g of groups) {
    if (!g.items.length) continue;
    any = true;
    const gh = document.createElement('div');
    gh.className = 'mention-group';
    gh.textContent = g.label;
    pop.appendChild(gh);
    for (const it of g.items) {
      const row = document.createElement('div');
      row.className = 'mention-item';
      const picked = state.mentionDirs.some(m => m.dir === it.dir);
      const icon = document.createElement('span');
      icon.textContent = '📁';
      const name = document.createElement('span');
      name.className = 'm-name';
      name.textContent = it.name;
      const dir = document.createElement('span');
      dir.className = 'm-dir';
      dir.textContent = it.dir;
      const check = document.createElement('span');
      check.className = 'm-check';
      check.textContent = picked ? '✓' : '';
      row.appendChild(icon);
      row.appendChild(name);
      row.appendChild(dir);
      row.appendChild(check);
      row.onclick = () => pickMention(it);
      pop.appendChild(row);
    }
  }
  if (!any) {
    const e = document.createElement('div');
    e.className = 'mention-empty';
    e.innerHTML = '没有匹配的工程<br>可在左侧「项目空间 ＋」新建并挂载目录';
    pop.appendChild(e);
  }
  pop.hidden = false;
}

function pickMention(item) {
  if (!state.mentionDirs.some(m => m.dir === item.dir)) {
    state.mentionDirs.push(item);
    renderAttachStrip();
  }
  // 移除输入框里已输入的 @词
  const input = $('#input');
  const caret = input.selectionStart;
  const before = input.value.slice(0, caret);
  const m = before.match(/@([^\s@]*)$/);
  if (m) {
    input.value = input.value.slice(0, caret - m[0].length) + input.value.slice(caret);
    input.selectionStart = input.selectionEnd = caret - m[0].length;
  }
  $('#mention-pop').hidden = true;
  input.focus();
}

function hideMentionPop() {
  const pop = $('#mention-pop');
  if (pop && !pop.hidden) pop.hidden = true;
}

// ── 输入历史（↑/↓ 翻页，上限 50 条）──
const IH_KEY = 'wc_input_history';

function getInputHistory() {
  try { return JSON.parse(localStorage.getItem(IH_KEY) || '[]') || []; } catch (e) { return []; }
}

function pushInputHistory(text) {
  try {
    const hist = getInputHistory();
    if (hist[hist.length - 1] !== text) {
      hist.push(text);
      localStorage.setItem(IH_KEY, JSON.stringify(hist.slice(-50)));
    }
  } catch (e) { /* ignore */ }
  state.ihIndex = null;
  state.ihStash = '';
}

async function streamReply(text, images, files, mentions) {
  const aiItem = { type: 'ai', full: '', tools: [], segs: [], done: false, streaming: true, time: Date.now() };
  const aiEl = appendItem(aiItem); // buildMessageEl 会归一化出首个空文本段
  startWaitIndicator(aiItem, aiEl);
  setStage('思考中');

  setStreamUi(true);
  // 文本文件以内容块内联进消息（仅当轮请求生效）
  let message = text || '';
  for (const f of (files || [])) {
    message += (message ? '\n\n' : '') + `📎 附件「${f.name}」内容如下：\n\`\`\`\n${f.content}\n\`\`\``;
  }
  // @ 选择的工程作为工作上下文注入，模型据此定位要操作的目录
  if (mentions && mentions.length) {
    message += (message ? '\n\n' : '') + '【工作工程目录】';
    for (const m of mentions) message += `\n- ${m.name}: ${m.dir}`;
    message += '\n（如需读写文件请基于以上目录操作）';
    // 打开对应项目的工程面板，便于查看涉及文件
    if (!$('#workspace-panel').hidden || mentions.length === 1) openWorkspace(mentions[0].projectId);
  }
  const body = {
    agentId: String(state.agentId),
    userId: 'webui',
    sessionId: state.sessionId,
    message,
  };
  if (state.modelId) body.modelId = state.modelId;
  // 内联图片（多模态输入）：仅当轮生效
  if (images && images.length) {
    body.inlineDatas = images.map(i => ({ data: i.b64, mimeType: i.mime }));
  }

  state.abortCtrl = new AbortController();
  const headers = { 'Content-Type': 'application/json' };
  if (state.token) headers['Authorization'] = 'Bearer ' + state.token;

  try {
    const res = await fetch(API + 'chat_stream', {
      method: 'POST', headers,
      body: JSON.stringify(body),
      signal: state.abortCtrl.signal,
    });
    if (!res.ok || !res.body) throw new Error('HTTP ' + res.status);

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, idx).trim();
        buf = buf.slice(idx + 1);
        if (!line) continue;
        try {
          handleStreamEvent(JSON.parse(line), aiItem, aiEl);
          scrollBottom(); // 流式内容增长时保持跟随（用户上翻则 stick=false 不打扰）
        } catch (e) { console.warn('非法事件行:', line.slice(0, 120)); }
      }
    }
  } catch (e) {
    stopWaitIndicator();
    if (e.name === 'AbortError') {
      if (!aiItem.stopped) aiItem.stopped = 'user';
    } else {
      const msg = '请求失败：' + translateError(e.message);
      aiItem.error = aiItem.error || msg;
      aiEl._bubble.appendChild(buildErrorCard(aiItem.error));
      setConnStatus(false, '连接断开');
    }
  } finally {
    aiItem.done = true;
    aiItem.streaming = false;
    aiItem.time = aiItem.time || Date.now();
    aiItem.elapsed = (Date.now() - aiItem.time) / 1000;
    stopWaitIndicator();
    setStage(null);
    clearStreamCursors(aiEl._bubble);
    if (aiEl._mdEl && aiEl._mdEl._seg) renderMdInto(aiEl._mdEl, aiEl._mdEl._seg.text, false);
    // 刷新底部 meta（完成状态 · 步数 · 工具 · 耗时）
    const oldMeta = aiEl._bubble.querySelector('.msg-meta');
    if (oldMeta) oldMeta.remove();
    aiEl._bubble.appendChild(buildMetaEl(aiItem));
    setStreamUi(false);
    saveMessages();
    updateSessionInfo();
    state.abortCtrl = null;
    // 发送排队中的下一条
    if (state.queue.length) {
      const next = state.queue.shift();
      setTimeout(() => sendMessage(next, true), 300);
    }
  }
}

// ───────────────────────── 斜杠命令 ─────────────────────────

function handleCommand(text) {
  const cmd = text.split(/\s+/)[0].toLowerCase();
  switch (cmd) {
    case '/help':
      appendItem({ type: 'status', kind: 'status', text: '命令：/clear 清空 · /new 新会话 · /export 导出 · /help 帮助 ｜ 快捷键：Ctrl/⌘+N 新会话 · Ctrl/⌘+B 侧栏 · Esc 停止生成 · ↑ 翻输入历史' });
      return true;
    case '/clear':
      clearMessages();
      return true;
    case '/new':
      newSession();
      return true;
    case '/export':
      exportSession();
      return true;
    default:
      return false;
  }
}

// ───────────────────────── 导出 / 清空 ─────────────────────────

function exportSession() {
  if (!state.messages.length) { toast('当前会话没有消息可导出', 'info'); return; }
  const lines = [`# DaMaoCode 会话导出`, '', `- 智能体：${$('#chat-title').textContent}`, `- 会话：${state.sessionId || '-'}`, `- 时间：${new Date().toLocaleString('zh-CN')}`, ''];
  for (const m of state.messages) {
    if (m.type === 'user') lines.push(`## 🧑 用户${m.time ? '（' + fmtTime(m.time) + '）' : ''}${m.images?.length ? '（附图片 ' + m.images.length + ' 张）' : ''}${m.files?.length ? '（附文件 ' + m.files.map(f => f.name).join('、') + '）' : ''}`, '', m.text, '');
    else if (m.type === 'ai') {
      const stat = m.stats ? `（${m.stats.steps} 步 · 工具 ${m.stats.toolCalls} 次${m.elapsed != null ? ' · 用时 ' + fmtElapsed(m.elapsed) : ''}）` : '';
      lines.push(`## 🤖 助手${stat}`, '');
      // 按分段顺序导出：文本段与工具卡交错
      const segs = (m.segs && m.segs.length) ? m.segs
        : [{ kind: 'text', text: m.full || '' }, ...(m.tools || []).map(t => ({ kind: 'tool', toolId: t.id }))];
      for (const seg of segs) {
        if (seg.kind === 'text') {
          if (seg.text) lines.push(seg.text, '');
        } else {
          const t = (m.tools || []).find(x => x.id === seg.toolId);
          if (!t) continue;
          lines.push(`> 🔧 **${t.toolName || 'tool'}**（${t.status || '-'}${t.duration ? ' · ' + t.duration : ''}）`, '',
            '```', prettyJson(t.args || ''), '```',
            t.result ? '```' + prettyJson(t.result) + '```' : '', '');
        }
      }
      if (m.error) lines.push(`> ❌ ${m.error}`, '');
      if (m.summary) {
        lines.push(`> 📋 改动：${m.summary.description || ''}`,
          ...(m.summary.created || []).map(f => `> + ${f}`),
          ...(m.summary.modified || []).map(f => `> ~ ${f}`),
          ...(m.summary.deleted || []).map(f => `> - ${f}`), '');
      }
    } else if (m.type === 'status') {
      lines.push(`*${m.text}*`, '');
    }
  }
  const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `DaMaoCode-session-${(state.sessionId || 'na').slice(0, 8)}.md`;
  a.click();
  URL.revokeObjectURL(a.href);
  toast('已导出 Markdown', 'ok', 2000);
}

function clearMessages() {
  state.messages = [];
  saveMessages();
  rerenderAll();
  updateSessionInfo();
  toast('已清空当前会话消息', 'ok', 1800);
}

// ───────────────────────── 智能体 / 模型加载 ─────────────────────────

async function loadAgents() {
  const resp = await api('query_ai_agent_config_list');
  if (resp.code !== '0000') throw new Error(resp.info || '查询智能体失败');
  state.agents = resp.data || [];
  const sel = $('#agent-select');
  sel.innerHTML = '';
  for (const a of state.agents) {
    const opt = document.createElement('option');
    opt.value = a.agentId;
    opt.textContent = `${a.agentName}（${a.agentId}）`;
    sel.appendChild(opt);
  }
  const saved = localStorage.getItem('wc_agent_id');
  if (saved && state.agents.some(a => String(a.agentId) === saved)) sel.value = saved;
  state.agentId = sel.value || null;
}

async function switchAgent(agentId) {
  if (state.streaming) {
    toast('正在生成中，请先停止或等待完成', 'info');
    $('#agent-select').value = String(state.agentId);
    return;
  }
  state.agentId = agentId;
  localStorage.setItem('wc_agent_id', agentId);
  state.sessionId = null;
  state.queue = [];
  state.messages = [];
  const agent = state.agents.find(a => String(a.agentId) === String(agentId));
  $('#chat-title').textContent = agent ? agent.agentName : 'DaMaoCode Agent';
  await ensureSession().catch(e => toast(e.message, 'err'));
}

async function loadModels() {
  const sel = $('#model-select');
  try {
    const resp = await api('model/list');
    state.models = resp.code === '0000' ? (resp.data || []) : [];
  } catch (e) {
    state.models = [];
  }
  const saved = localStorage.getItem('wc_model_id');
  sel.innerHTML = '<option value="">默认模型（数据库配置）</option>';
  for (const m of state.models) {
    const opt = document.createElement('option');
    opt.value = m.id;
    opt.textContent = `${m.name} · ${m.modelName}`;
    sel.appendChild(opt);
  }
  if (saved && state.models.some(m => String(m.id) === saved)) sel.value = saved;
  state.modelId = sel.value || null;
}

// ───────────────────────── 模型设置弹窗 ─────────────────────────

function openSettings() {
  $('#modal-mask').hidden = false;
  renderModelTable();
}

function closeSettings() {
  $('#modal-mask').hidden = true;
}

function renderModelTable() {
  const tbody = $('#model-tbody');
  tbody.innerHTML = '';
  if (!state.models.length) {
    tbody.innerHTML = '<tr><td colspan="5" id="model-empty">暂无模型配置，使用智能体 yml 兜底配置</td></tr>';
    return;
  }
  for (const m of state.models) {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td>${escapeHtml(m.name)}</td>
      <td class="mono">${escapeHtml(m.modelName)}</td>
      <td class="mono">${escapeHtml(m.baseUrl)}</td>
      <td class="mono">${escapeHtml(m.apiKeyMasked || '****')}</td>
      <td><div class="cell-actions">
        <button class="btn btn-ghost btn-sm" data-act="edit">编辑</button>
        <button class="btn btn-ghost btn-sm" data-act="test">测试</button>
        <button class="btn btn-danger btn-sm" data-act="del">删除</button>
      </div></td>`;
    tr.querySelector('[data-act="edit"]').onclick = () => { fillForm(m); };
    tr.querySelector('[data-act="test"]').onclick = async (e) => {
      const btn = e.target;
      btn.disabled = true;
      btn.textContent = '测试中';
      try {
        const resp = await api('model/test', { method: 'POST', body: JSON.stringify({ id: m.id, name: m.name, baseUrl: m.baseUrl, apiKey: '', modelName: m.modelName, completionsPath: m.completionsPath }) });
        toast(resp.code === '0000' ? `「${m.name}」连接成功 ✔` : `「${m.name}」连接失败：${resp.info || ''}`, resp.code === '0000' ? 'ok' : 'err', 5000);
      } catch (err) {
        toast('测试失败：' + translateError(err.message), 'err');
      } finally {
        btn.disabled = false;
        btn.textContent = '测试';
      }
    };
    tr.querySelector('[data-act="del"]').onclick = () => deleteModel(m);
    tbody.appendChild(tr);
  }
}

function fillForm(m) {
  $('#f-id').value = m.id ?? '';
  $('#edit-id-field').hidden = false;
  $('#f-name').value = m.name || '';
  $('#f-model').value = m.modelName || '';
  $('#f-baseurl').value = m.baseUrl || '';
  $('#f-apikey').value = '';
  $('#f-apikey').placeholder = m.apiKeyMasked || '不修改请留空';
  $('#f-path').value = m.completionsPath || '';
}

function resetForm() {
  $('#f-id').value = '';
  $('#edit-id-field').hidden = true;
  $('#f-name').value = '';
  $('#f-model').value = '';
  $('#f-baseurl').value = '';
  $('#f-apikey').value = '';
  $('#f-apikey').placeholder = 'sk-...';
  $('#f-path').value = '';
}

function formEntity() {
  return {
    id: $('#f-id').value ? Number($('#f-id').value) : null,
    name: $('#f-name').value.trim(),
    baseUrl: $('#f-baseurl').value.trim(),
    apiKey: $('#f-apikey').value.trim(),
    modelName: $('#f-model').value.trim(),
    completionsPath: $('#f-path').value.trim(),
  };
}

async function saveModel() {
  const entity = formEntity();
  if (!entity.name || !entity.baseUrl || !entity.modelName) {
    toast('名称 / Base URL / 模型名为必填项', 'err');
    return;
  }
  if (!entity.id && !entity.apiKey) {
    toast('新增模型必须填写 API Key', 'err');
    return;
  }
  const resp = await api('model/save', { method: 'POST', body: JSON.stringify(entity) });
  if (resp.code !== '0000') { toast(resp.info || '保存失败', 'err'); return; }
  toast('模型已保存');
  resetForm();
  await loadModels();
  renderModelTable();
}

async function testModel() {
  const entity = formEntity();
  if (!entity.baseUrl || !entity.modelName) {
    toast('Base URL 与模型名为必填项', 'err');
    return;
  }
  const btn = $('#btn-model-test');
  btn.disabled = true;
  btn.textContent = '测试中…';
  try {
    const resp = await api('model/test', { method: 'POST', body: JSON.stringify(entity) });
    toast(resp.code === '0000' ? '连接成功 ✔ ' + (resp.data || '') : (resp.info || '连接失败'), resp.code === '0000' ? 'ok' : 'err', 5000);
  } catch (e) {
    toast('测试失败：' + translateError(e.message), 'err');
  } finally {
    btn.disabled = false;
    btn.textContent = '测试连接';
  }
}

async function deleteModel(m) {
  if (!confirm(`确定删除模型「${m.name}」？`)) return;
  const resp = await api(`model/delete?id=${m.id}`, { method: 'POST' });
  if (resp.code !== '0000') { toast(resp.info || '删除失败', 'err'); return; }
  toast('已删除');
  if (String(state.modelId) === String(m.id)) {
    state.modelId = null;
    localStorage.removeItem('wc_model_id');
  }
  await loadModels();
  renderModelTable();
}

// ───────────────────────── 连接状态 / 主题 ─────────────────────────

function setConnStatus(ok, text) {
  const dot = $('#status-dot');
  dot.className = 'status-dot ' + (ok ? 'ok' : 'bad');
  $('#status-text').textContent = text;
}

// 心跳：每 30s 轻量探测服务可用性（页面不可见时跳过），断线实时反馈到顶栏状态点
function startHeartbeat() {
  setInterval(async () => {
    if (document.hidden) return;
    try {
      const resp = await api('query_ai_agent_config_list');
      setConnStatus(resp.code === '0000', resp.code === '0000' ? '已连接' : '连接异常');
    } catch (e) {
      setConnStatus(false, '连接断开');
    }
  }, 30000);
}

function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  localStorage.setItem('wc_theme', theme);
  // mermaid 主题跟随：重置已渲染图形按新主题重绘
  if (typeof mermaid !== 'undefined' && mermaidReady) {
    try {
      mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: theme === 'dark' ? 'dark' : 'default', fontFamily: 'inherit' });
      mermaidReady = true;
      // 已渲染的块重新按当前源码渲染
      document.querySelectorAll('.mermaid-block[data-mermaid-done]').forEach(b => {
        b.removeAttribute('data-mermaid-done');
        b.classList.remove('rendered', 'mermaid-error');
      });
      document.querySelectorAll('#messages .md').forEach(md => renderMermaids(md, false));
    } catch (e) { /* ignore */ }
  }
}

// ───────────────────────── 事件绑定与启动 ─────────────────────────

// ── 附件（图片多模态输入 + 文本文件内联）──
const IMG_MAX_COUNT = 4;        // 单条消息最多图片数
const IMG_MAX_EDGE = 1280;      // 长边超过此值则缩放
const IMG_KEEP_BYTES = 512 * 1024; // 小于该值且未超尺寸时保留原图
const FILE_MAX_COUNT = 5;         // 单条消息最多文件数
const FILE_MAX_BYTES = 128 * 1024; // 单文件文本内容上限（超出截断）
const TEXT_FILE_EXTS = new Set(('txt md markdown rst js ts jsx tsx mjs cjs java py go rs c h cpp hpp cc cs php rb sh bash zsh json xml yml yaml toml ini conf properties csv tsv log html htm css scss less sql kt scala swift vue svelte gradle dockerfile makefile env gitignore bat ps1 proto graphql tf').split(' '));

function isTextFile(file) {
  if (file.type && (file.type.startsWith('text/') || ['application/json', 'application/xml', 'application/javascript', 'application/x-yaml', 'application/toml'].includes(file.type))) return true;
  const ext = (file.name || '').split('.').pop().toLowerCase();
  return TEXT_FILE_EXTS.has(ext);
}

function readTextFile(file) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result || ''));
    r.onerror = reject;
    r.readAsText(file);
  });
}

// 大图降质：超尺寸或超大文件时用 canvas 缩到 IMG_MAX_EDGE 并转 JPEG(0.85)
function compressImage(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      const scale = Math.min(1, IMG_MAX_EDGE / Math.max(img.width, img.height));
      const keep = scale === 1 && file.size <= IMG_KEEP_BYTES;
      if (keep) { // 原图足够小：直接保留
        URL.revokeObjectURL(url);
        const r = new FileReader();
        r.onload = () => resolve({ dataUrl: r.result, mime: file.type, name: file.name });
        r.onerror = reject;
        r.readAsDataURL(file);
        return;
      }
      const canvas = document.createElement('canvas');
      canvas.width = Math.round(img.width * scale);
      canvas.height = Math.round(img.height * scale);
      canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
      URL.revokeObjectURL(url);
      const mime = file.type === 'image/png' || file.type === 'image/webp' ? file.type : 'image/jpeg';
      resolve({ dataUrl: canvas.toDataURL(mime, 0.85), mime, name: file.name });
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('图片解析失败')); };
    img.src = url;
  });
}

function addPendingImages(files) {
  const room = IMG_MAX_COUNT - state.pendingImages.length;
  const list = [...files].filter(f => f.type.startsWith('image/'));
  if (!list.length) return;
  if (list.length > room) toast(`一次最多 ${IMG_MAX_COUNT} 张图片，超出部分已忽略`, 'info');
  list.slice(0, room).forEach(file => {
    compressImage(file).then(({ dataUrl, mime, name }) => {
      state.pendingImages.push({ dataUrl, mime, name, b64: dataUrl.split(',')[1] || '' });
      renderAttachStrip();
    }).catch(() => toast(`图片解析失败：${file.name}`, 'err'));
  });
}

// 统一附件入口：图片走多模态，文本类文件读取内容，其余提示不支持
async function handlePickedFiles(fileList) {
  const files = [...fileList];
  if (!files.length) return;
  const imgs = files.filter(f => f.type.startsWith('image/'));
  const texts = files.filter(f => !f.type.startsWith('image/') && isTextFile(f));
  const rejected = files.filter(f => !f.type.startsWith('image/') && !isTextFile(f));
  if (rejected.length) toast(`暂不支持发送「${rejected[0].name}」这类文件（仅图片 / 文本类文件）`, 'info');
  if (imgs.length) addPendingImages(imgs);
  const room = FILE_MAX_COUNT - state.pendingFiles.length;
  if (texts.length && room <= 0) { toast(`一次最多 ${FILE_MAX_COUNT} 个文件，超出部分已忽略`, 'info'); return; }
  for (const file of texts.slice(0, room)) {
    if (file.size > FILE_MAX_BYTES * 4) { toast(`文件过大已跳过：${file.name}`, 'info'); continue; }
    try {
      let content = await readTextFile(file);
      if (content.length > FILE_MAX_BYTES) {
        content = content.slice(0, FILE_MAX_BYTES) + '\n…（内容过长已截断）';
        toast(`文件内容较长已截断：${file.name}`, 'info');
      }
      state.pendingFiles.push({ name: file.name, content });
    } catch (e) { toast(`文件读取失败：${file.name}`, 'err'); }
  }
  renderAttachStrip();
}

function renderAttachStrip() {
  const strip = $('#attach-strip');
  strip.innerHTML = '';
  strip.hidden = state.pendingImages.length === 0 && state.pendingFiles.length === 0 && state.mentionDirs.length === 0;
  // @ 已选工程 chip
  state.mentionDirs.forEach((m, i) => {
    const cell = document.createElement('div');
    cell.className = 'attach-file';
    cell.title = `${m.name}\n${m.dir}\n将作为工作工程上下文随消息发送`;
    const icon = document.createElement('span');
    icon.className = 'af-icon';
    icon.textContent = '📁';
    const name = document.createElement('span');
    name.className = 'af-name';
    name.textContent = '@' + m.name;
    const rm = document.createElement('button');
    rm.className = 'attach-remove';
    rm.textContent = '×';
    rm.title = '移除';
    rm.onclick = () => { state.mentionDirs.splice(i, 1); renderAttachStrip(); };
    cell.appendChild(icon);
    cell.appendChild(name);
    cell.appendChild(rm);
    strip.appendChild(cell);
  });
  state.pendingImages.forEach((img, i) => {
    const cell = document.createElement('div');
    cell.className = 'attach-thumb';
    const im = document.createElement('img');
    im.src = img.dataUrl;
    im.title = img.name || '图片';
    const rm = document.createElement('button');
    rm.className = 'attach-remove';
    rm.textContent = '×';
    rm.title = '移除';
    rm.onclick = () => { state.pendingImages.splice(i, 1); renderAttachStrip(); };
    cell.appendChild(im);
    cell.appendChild(rm);
    strip.appendChild(cell);
  });
  state.pendingFiles.forEach((f, i) => {
    const cell = document.createElement('div');
    cell.className = 'attach-file';
    cell.title = `${f.name}\n${f.content.length} 字符，将随消息内容发送`;
    const icon = document.createElement('span');
    icon.className = 'af-icon';
    icon.textContent = '📄';
    const name = document.createElement('span');
    name.className = 'af-name';
    name.textContent = f.name;
    const rm = document.createElement('button');
    rm.className = 'attach-remove';
    rm.textContent = '×';
    rm.title = '移除';
    rm.onclick = () => { state.pendingFiles.splice(i, 1); renderAttachStrip(); };
    cell.appendChild(icon);
    cell.appendChild(name);
    cell.appendChild(rm);
    strip.appendChild(cell);
  });
}

function bindEvents() {
  const input = $('#input');
  const send = () => {
    const text = input.value.trim();
    const images = state.pendingImages.slice();
    const files = state.pendingFiles.slice();
    const mentions = state.mentionDirs.slice();
    if (!text && !images.length && !files.length) return;
    input.value = '';
    input.style.height = 'auto';
    state.pendingImages = [];
    state.pendingFiles = [];
    state.mentionDirs = [];
    renderAttachStrip();
    hideMentionPop();
    if (text.startsWith('/') && !images.length && !files.length) {
      if (handleCommand(text)) return;
    }
    sendMessage(text || (files.length ? '[文件]' : '[图片]'), false, images, files, mentions);
  };

  // @ 工程选择：输入 @ 触发下拉（当前项目 / 其他项目分组）
  input.addEventListener('input', () => {
    const caret = input.selectionStart;
    const before = input.value.slice(0, caret);
    const m = before.match(/(?:^|\s)@([^\s@]*)$/);
    if (m) renderMentionPop(m[1]);
    else hideMentionPop();
  });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { hideMentionPop(); return; }
  });

  // 附件上传：点击 + 选择 / 粘贴 / 拖拽到输入框
  $('#btn-attach').onclick = () => $('#file-attach').click();
  $('#file-attach').onchange = (e) => { handlePickedFiles(e.target.files); e.target.value = ''; };

  // 项目空间
  $('#btn-new-project').onclick = () => openProjectModal(null);
  $('#btn-pm-close').onclick = closeProjectModal;
  $('#btn-pm-cancel').onclick = closeProjectModal;
  $('#btn-pm-save').onclick = saveProjectModal;
  $('#pm-name').addEventListener('keydown', (e) => { if (e.key === 'Enter') saveProjectModal(); });
  $('#project-modal-mask').addEventListener('click', (e) => {
    if (e.target === $('#project-modal-mask')) closeProjectModal();
  });
  // 关联工程弹窗
  $('#btn-dm-close').onclick = closeDirModal;
  $('#btn-dm-cancel').onclick = closeDirModal;
  $('#btn-dm-done').onclick = saveDirModal;
  $('#btn-dm-pick').onclick = () => showDirPicker((path) => {
    if (dmDirBackup.includes(path)) { toast('该目录已关联', 'info'); return; }
    dmDirBackup.push(path);
    renderDmDirList();
  });
  $('#dir-modal-mask').addEventListener('click', (e) => {
    if (e.target === $('#dir-modal-mask')) closeDirModal();
  });

  // 右侧工程面板
  $('#btn-workspace').onclick = () => {
    if ($('#workspace-panel').hidden) openWorkspace(state.wsProjectId !== undefined ? state.wsProjectId : state.currentProjectId);
    else closeWorkspace();
  };
  $('#btn-ws-close').onclick = closeWorkspace;
  $('#btn-ws-refresh').onclick = () => { state.wsNodes = {}; loadWsTree(); };
  $('#btn-ws-back').onclick = showWsTree;
  $('#ws-project-select').onchange = (e) => {
    state.wsProjectId = e.target.value || null;
    state.wsNodes = {};
    loadWsTree();
  };
  initWsDrag();

  // 点击面板外区域收起 @ 下拉
  document.addEventListener('click', (e) => {
    const pop = $('#mention-pop');
    if (pop && !pop.hidden && !pop.contains(e.target) && e.target !== input) hideMentionPop();
  });
  input.addEventListener('paste', (e) => {
    const files = [...(e.clipboardData?.files || [])];
    if (files.length) { e.preventDefault(); handlePickedFiles(files); }
  });
  ['dragover', 'drop'].forEach(evt => input.addEventListener(evt, (e) => {
    const files = [...(e.dataTransfer?.files || [])];
    if (!files.length) return;
    e.preventDefault();
    if (evt === 'drop') handlePickedFiles(files);
  }));

  $('#btn-send').onclick = send;
  $('#btn-stop').onclick = () => state.abortCtrl && state.abortCtrl.abort();
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); send(); return; }
    // ↑/↓ 翻输入历史（↑ 仅在光标位于开头或未开始翻页时触发）
    if (e.key === 'ArrowUp' && !e.shiftKey) {
      const hist = getInputHistory();
      if (!hist.length) return;
      if (state.ihIndex == null && (input.selectionStart > 0 || input.value.includes('\n'))) return;
      e.preventDefault();
      if (state.ihIndex == null) { state.ihStash = input.value; state.ihIndex = hist.length - 1; }
      else if (state.ihIndex > 0) state.ihIndex--;
      input.value = hist[state.ihIndex];
      input.style.height = 'auto';
      input.style.height = Math.min(input.scrollHeight, 240) + 'px';
    } else if (e.key === 'ArrowDown' && state.ihIndex != null) {
      e.preventDefault();
      const hist = getInputHistory();
      if (state.ihIndex < hist.length - 1) { state.ihIndex++; input.value = hist[state.ihIndex]; }
      else { state.ihIndex = null; input.value = state.ihStash || ''; }
    } else if (state.ihIndex != null && e.key.length === 1) {
      state.ihIndex = null; // 手动输入即退出历史翻页
    }
  });
  input.addEventListener('input', () => {
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 240) + 'px';
  });

  if ($('#btn-new-session')) $('#btn-new-session').onclick = newSession;
  $('#btn-export').onclick = exportSession;
  $('#btn-clear').onclick = clearMessages;

  $('#session-search').addEventListener('input', renderSessionList);

  $('#agent-select').onchange = (e) => switchAgent(e.target.value);

  $('#model-select').onchange = (e) => {
    state.modelId = e.target.value || null;
    if (state.modelId) localStorage.setItem('wc_model_id', state.modelId);
    else localStorage.removeItem('wc_model_id');
    updateSessionInfo();
  };

  $('#btn-settings').onclick = openSettings;
  $('#btn-close-settings').onclick = closeSettings;
  $('#modal-mask').onclick = (e) => { if (e.target.id === 'modal-mask') closeSettings(); };
  $('#btn-model-save').onclick = () => saveModel().catch(e => toast(translateError(e.message), 'err'));
  $('#btn-model-test').onclick = () => testModel().catch(e => toast(translateError(e.message), 'err'));
  $('#btn-model-reset').onclick = resetForm;

  $('#btn-theme').onclick = () => {
    const cur = document.documentElement.getAttribute('data-theme');
    applyTheme(cur === 'dark' ? 'light' : 'dark');
  };

  $('#btn-collapse').onclick = () => toggleSidebar(true);
  $('#btn-expand').onclick = () => toggleSidebar(false);

  $('#btn-scroll-bottom').onclick = () => scrollBottom(true);

  // 消息区滚动：维护贴底跟随标志，控制回底按钮显隐
  $('#messages').addEventListener('scroll', () => {
    const box = $('#messages');
    const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
    state.stick = nearBottom;
    $('#btn-scroll-bottom').hidden = nearBottom;
  });

  // 快捷键
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      if (projectMenuEl) { closeProjectMenu(); return; }
      if (dirPickerMask) { closeDirPicker(); return; }
      if (!$('#modal-mask').hidden) { closeSettings(); return; }
      if (!$('#dir-modal-mask') || !$('#dir-modal-mask').hidden) { closeDirModal(); return; }
      if (!$('#project-modal-mask').hidden) { closeProjectModal(); return; }
      if (!$('#mention-pop').hidden) { hideMentionPop(); return; }
      if (!$('#ws-viewer').hidden) { showWsTree(); return; }
      if (!$('#workspace-panel').hidden) { closeWorkspace(); return; }
      if (state.streaming && state.abortCtrl) state.abortCtrl.abort();
    }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'n') {
      e.preventDefault();
      newSession();
    }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'b') {
      e.preventDefault();
      toggleSidebar(!$('#sidebar').classList.contains('collapsed'));
    }
  });
}

function toggleSidebar(collapse) {
  $('#sidebar').classList.toggle('collapsed', collapse);
  $('#btn-expand').hidden = !collapse;
  localStorage.setItem('wc_sidebar', collapse ? 'collapsed' : 'open');
}

async function boot() {
  applyTheme(localStorage.getItem('wc_theme') || 'light');
  if (localStorage.getItem('wc_sidebar') === 'collapsed') {
    $('#sidebar').classList.add('collapsed');
    $('#btn-expand').hidden = false;
  }
  bindEvents();

  // 项目空间（localStorage，早于会话加载以过滤会话列表）
  loadProjects();
  renderProjectList();

  // 1. 引导鉴权（local profile 下获取运行时 token；dev profile 返回空）
  try {
    const res = await fetch('/webui/bootstrap');
    if (res.ok) {
      const data = await res.json();
      state.token = data.token || '';
    }
  } catch (e) { /* 忽略，后续 API 报错时提示 */ }

  // 2. 智能体 + 模型 + 会话
  try {
    await loadAgents();
    const agent = state.agents.find(a => String(a.agentId) === String(state.agentId));
    $('#chat-title').textContent = agent ? agent.agentName : 'DaMaoCode Agent';
    setConnStatus(true, '已连接');
  } catch (e) {
    setConnStatus(false, '连接失败');
    toast('初始化失败：' + translateError(e.message), 'err', 6000);
    return;
  }

  await loadModels();
  await ensureSession().catch(e => toast(translateError(e.message), 'err'));
  updateSessionInfo();
  startHeartbeat();
  $('#input').focus();
}

boot();
