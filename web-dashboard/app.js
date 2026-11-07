const API_BASE_KEY = 'sn-api-base';
const API_KEY_KEY = 'sn-api-key';

function defaultApiBaseUrl() {
  if (!window.location.protocol.startsWith('http')) return '';
  const url = new URL(window.location.href);
  if ((url.hostname === 'localhost' || url.hostname === '127.0.0.1') && url.port === '8000') {
    url.port = '8001';
  }
  return url.origin;
}

let apiBaseUrl = sessionStorage.getItem(API_BASE_KEY) || defaultApiBaseUrl();
let apiKey = sessionStorage.getItem(API_KEY_KEY) || '';
let matches = [];
let requests = [];
let selectedVehicles = new Set();
let activeSearch = null;

const $ = (id) => document.getElementById(id);
const escapeHtml = (value) => String(value == null ? '' : value).replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
const apiRoot = () => apiBaseUrl.trim().replace(/\/+$/, '');

function applyTheme() {
  const theme = localStorage.getItem('sn-theme') || 'light';
  document.documentElement.dataset.theme = theme;
  document.querySelectorAll('#themeToggle, #themeToggleDashboard').forEach((button) => {
    button.title = theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme';
    button.setAttribute('aria-label', button.title);
    const label = button.querySelector('span');
    button.innerHTML = '<i data-lucide="' + (theme === 'dark' ? 'sun' : 'moon') + '"></i>' + (label ? '<span>Theme</span>' : '');
  });
  if (window.lucide) lucide.createIcons();
}

function showToast(message) {
  const toast = $('toast');
  toast.textContent = message;
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), 3300);
}

function setApiStatus(message, connected) {
  const status = $('apiStatus');
  status.innerHTML = '<span></span> ' + escapeHtml(message);
  status.classList.toggle('connected', Boolean(connected));
}

function errorMessage(data) {
  const detail = data.detail || data.error;
  if (Array.isArray(detail)) return detail.map((item) => item.msg || String(item)).join('; ');
  if (detail && typeof detail === 'object') return [detail.message].concat(detail.vehicles || []).filter(Boolean).join(': ');
  return detail;
}

async function api(path, options) {
  options = options || {};
  if (!apiKey || !apiRoot()) throw new Error('Open Backend connection and enter the API URL and key.');
  const headers = Object.assign({ 'X-Demo-API-Key': apiKey }, options.headers || {});
  if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
  const response = await fetch(apiRoot() + path, Object.assign({}, options, { headers: headers, cache: 'no-store' }));
  const text = await response.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch (_) { data = { detail: text }; }
  if (!response.ok) throw new Error(errorMessage(data) || ('Request failed (' + response.status + ')'));
  return data;
}

function openConnectionDialog() {
  $('apiBaseUrl').value = apiBaseUrl;
  $('apiKey').value = apiKey;
  $('connectionMessage').textContent = '';
  $('connectionDialog').showModal();
}

function localDateTimeValue(date) {
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

function parseCoordinates(value) {
  const parts = value.trim().split(/[\s,]+/).filter(Boolean).map(Number);
  if (parts.length !== 2 || !parts.every(Number.isFinite)) return null;
  const latitude = parts[0];
  const longitude = parts[1];
  if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null;
  return { latitude: latitude, longitude: longitude };
}

function distanceMeters(lat1, lon1, lat2, lon2) {
  const radians = (degrees) => degrees * Math.PI / 180;
  const dLat = radians(lat2 - lat1);
  const dLon = radians(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(radians(lat1)) * Math.cos(radians(lat2)) * Math.sin(dLon / 2) ** 2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function formatDistance(meters) {
  return meters < 1000 ? Math.round(meters) + ' m' : (meters / 1000).toFixed(1) + ' km';
}

function formatDateTime(value) {
  if (!value) return 'Unknown time';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Unknown time' : date.toLocaleString('en-IN', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false });
}

function statusInfo(request) {
  const state = String(request.status || 'approval_pending').toLowerCase();
  if (state === 'approved') return { key: 'approved', label: 'Approved' };
  if (state === 'declined') return { key: 'overdue', label: 'Declined' };
  if (state === 'expired') return { key: 'expired', label: 'Expired' };
  const created = new Date(request.created_at || Date.now()).getTime();
  const ageDays = Math.max(0, (Date.now() - created) / 86400000);
  if (ageDays >= 14) return { key: 'overdue', label: 'Approval pending' };
  if (ageDays >= 7) return { key: 'acknowledged', label: 'Approval pending' };
  return { key: 'pending', label: 'Approval pending' };
}

function updateSelection() {
  $('selectionText').textContent = selectedVehicles.size + ' selected';
  $('sendRequests').disabled = selectedVehicles.size === 0 || !activeSearch;
}

function renderMatches() {
  $('matchCount').textContent = matches.length;
  if (!matches.length) {
    $('matchList').innerHTML = '<p class="vehicle-meta">No vehicles match these coordinates and time range.</p>';
    $('mapMarkers').innerHTML = '';
    updateSelection();
    return;
  }
  const center = activeSearch;
  $('matchList').innerHTML = matches.map((vehicle) => {
    const id = vehicle.vehicle_reg_number;
    const meters = vehicle.distance_meters == null ? distanceMeters(center.latitude, center.longitude, vehicle.latitude, vehicle.longitude) : vehicle.distance_meters;
    return '<label class="match-row"><input type="checkbox" data-vehicle="' + escapeHtml(id) + '" ' + (selectedVehicles.has(id) ? 'checked' : '') + '><span><span class="vehicle-name">' + escapeHtml(id) + '</span><span class="vehicle-meta">' + escapeHtml(vehicle.owner_name || 'Vehicle owner') + ' · GPS ' + escapeHtml(formatDateTime(vehicle.last_matching_ping)) + '</span></span><span class="distance"><strong>' + escapeHtml(formatDistance(meters)) + '</strong>from incident</span></label>';
  }).join('');

  $('mapMarkers').innerHTML = matches.map((vehicle) => {
    const id = vehicle.vehicle_reg_number;
    const latScale = Math.max(center.radiusMeters / 111320, 0.00001);
    const lonScale = Math.max(center.radiusMeters / (111320 * Math.cos(center.latitude * Math.PI / 180)), 0.00001);
    const left = Math.min(94, Math.max(6, 50 + ((vehicle.longitude - center.longitude) / lonScale) * 42));
    const top = Math.min(91, Math.max(6, 47 - ((vehicle.latitude - center.latitude) / latScale) * 42));
    return '<button class="map-point vehicle-marker ' + (selectedVehicles.has(id) ? 'selected' : '') + '" style="left:calc(' + left + '% - 15px);top:calc(' + top + '% - 15px)" title="' + escapeHtml(id) + '" aria-label="' + escapeHtml(id) + '" data-map-vehicle="' + escapeHtml(id) + '"><i data-lucide="car-front"></i></button>';
  }).join('');

  document.querySelectorAll('[data-vehicle]').forEach((input) => input.addEventListener('change', (event) => {
    const id = event.target.dataset.vehicle;
    event.target.checked ? selectedVehicles.add(id) : selectedVehicles.delete(id);
    renderMatches();
  }));
  document.querySelectorAll('[data-map-vehicle]').forEach((marker) => marker.addEventListener('click', () => {
    const id = marker.dataset.mapVehicle;
    selectedVehicles.has(id) ? selectedVehicles.delete(id) : selectedVehicles.add(id);
    renderMatches();
  }));
  if (window.lucide) lucide.createIcons();
  updateSelection();
}

function hashCell(request, state) {
  const alert = request.rejected_upload_count
    ? '<small class="hash-alert">' + escapeHtml(request.rejected_upload_count) + ' upload(s) rejected: hash did not match</small>'
    : '';
  if (!request.sha256) {
    return '<span class="evidence-pending">' + (state.label === 'Approval pending' ? 'Waiting for phone to hash clip' : 'Not recorded') + '</span>' + alert;
  }
  return '<code class="hash-value" title="' + escapeHtml(request.sha256) + '">' + escapeHtml(request.sha256.slice(0, 12) + '…' + request.sha256.slice(-8)) + '</code>'
    + '<button class="hash-copy" type="button" data-copy-hash="' + escapeHtml(request.sha256) + '">Copy</button>'
    + '<small class="subline">Recorded ' + escapeHtml(formatDateTime(request.preserved_at)) + '</small>' + alert;
}

async function sha256Hex(blob) {
  if (!window.crypto || !crypto.subtle) throw new Error('Open the dashboard on localhost or HTTPS to verify hashes.');
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

function showHashCheck(recorded, received, error) {
  const check = $('hashCheck');
  const matches = Boolean(recorded && received && recorded.toLowerCase() === received.toLowerCase());
  let verdict = 'Checking the received clip…';
  let tone = '';
  if (error) { verdict = 'Could not hash the received clip: ' + error; tone = 'warn'; }
  else if (received && !recorded) { verdict = 'No hash was recorded before approval, so this clip cannot be compared.'; tone = 'warn'; }
  else if (received && matches) { verdict = 'Hashes match. This clip is unchanged since the phone hashed it.'; tone = 'ok'; }
  else if (received) { verdict = 'Hashes do not match. This clip may have been altered, so do not rely on it.'; tone = 'bad'; }
  check.className = 'hash-check ' + tone;
  check.innerHTML = '<strong>' + escapeHtml(verdict) + '</strong>'
    + '<span>Recorded before approval</span><code>' + escapeHtml(recorded || 'Not recorded') + '</code>'
    + '<span>Received clip, hashed in this browser</span><code>' + escapeHtml(received || (error ? 'Unavailable' : '…')) + '</code>';
  check.hidden = false;
}

function renderRequests() {
  $('requestTable').innerHTML = requests.length ? requests.map((request) => {
    const state = statusInfo(request);
    const vehicle = request.vehicle_reg_number || '';
    const start = request.time_start;
    const end = request.time_end;
    const endTime = end ? new Date(end).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: false }) : '';
    const windowText = formatDateTime(start) + (end ? ' – ' + endTime : '');
    const evidence = state.key === 'approved' && request.evidence_url
      ? '<button class="evidence-ready" type="button" data-evidence-url="' + escapeHtml(request.evidence_url) + '">View clip</button>'
      : '<span class="evidence-pending">' + (state.label === 'Approval pending' ? 'Waiting for consent' : 'No clip') + '</span>';
    const delivery = request.notification_status === 'sent_fcm' ? 'Push sent' : (state.label === 'Approval pending' ? 'Waiting for phone poll' : '');
    return '<tr><td class="vehicle-cell">' + escapeHtml(vehicle) + '</td><td>' + escapeHtml(windowText) + '</td><td>' + escapeHtml(formatDateTime(request.created_at)) + '</td><td><span class="status ' + state.key + '">' + escapeHtml(state.label) + '</span><br><small>' + delivery + '</small></td><td class="hash-cell">' + hashCell(request, state) + '</td><td>' + evidence + '</td><td><button class="row-menu" type="button" aria-label="Request ID ' + escapeHtml(request.request_id) + '" title="Request ID ' + escapeHtml(request.request_id) + '"><i data-lucide="more-horizontal"></i></button></td></tr>';
  }).join('') : '<tr><td colspan="7" class="vehicle-meta">No requests are stored on the backend yet.</td></tr>';
  document.querySelectorAll('[data-evidence-url]').forEach((button) => button.addEventListener('click', () => openEvidence(button.dataset.evidenceUrl)));
  document.querySelectorAll('[data-copy-hash]').forEach((button) => button.addEventListener('click', () => {
    navigator.clipboard.writeText(button.dataset.copyHash).then(() => showToast('Hash copied.'), () => showToast('Could not copy the hash.'));
  }));
  if (window.lucide) lucide.createIcons();
}

async function openEvidence(path) {
  const dialog = $('evidenceDialog');
  const player = $('evidencePlayer');
  const message = $('evidenceMessage');
  if (window.evidenceObjectUrl) {
    URL.revokeObjectURL(window.evidenceObjectUrl);
    window.evidenceObjectUrl = null;
  }
  player.pause();
  player.removeAttribute('src');
  player.load();
  message.textContent = 'Loading approved clip…';
  message.hidden = false;
  $('hashCheck').hidden = true;
  dialog.showModal();
  const request = requests.find((item) => item.evidence_url === path);
  const recorded = request ? request.sha256 : null;
  try {
    const response = await fetch(apiRoot() + path, { headers: { 'X-Demo-API-Key': apiKey } });
    if (!response.ok) {
      let detail = '';
      try { const body = await response.json(); detail = body.detail || body.error || ''; } catch (_) {}
      throw new Error(detail || ('Clip request failed (' + response.status + ').'));
    }
    const blob = await response.blob();
    if (!blob.size) throw new Error('The backend returned an empty video file.');
    window.evidenceObjectUrl = URL.createObjectURL(blob);
    player.src = window.evidenceObjectUrl;
    message.hidden = true;
    player.focus();
    showHashCheck(recorded, null);
    try {
      showHashCheck(recorded, await sha256Hex(blob));
    } catch (error) {
      showHashCheck(recorded, null, error.message);
    }
  } catch (error) {
    message.textContent = 'Could not load approved clip: ' + error.message;
    message.hidden = false;
  }
}

function closeEvidence() {
  const player = $('evidencePlayer');
  player.pause();
  player.removeAttribute('src');
  player.load();
  $('evidenceDialog').close();
  if (window.evidenceObjectUrl) {
    URL.revokeObjectURL(window.evidenceObjectUrl);
    window.evidenceObjectUrl = null;
  }
}

function renderNotifications() {
  const recent = requests.slice(0, 20);
  ['notificationCount', 'topNotificationCount'].forEach((id) => {
    const element = $(id);
    element.textContent = recent.length;
    element.style.display = recent.length ? 'grid' : 'none';
  });
  $('notificationList').innerHTML = recent.length ? recent.map((request) => '<article class="notification"><span class="notification-icon"><i data-lucide="send"></i></span><div><strong>Request for ' + escapeHtml(request.vehicle_reg_number) + '</strong><p>Status: ' + escapeHtml(statusInfo(request).label) + '</p><time>' + escapeHtml(formatDateTime(request.created_at)) + '</time></div></article>').join('') : '<p class="vehicle-meta">No backend requests yet.</p>';
  if (window.lucide) lucide.createIcons();
}

async function loadRequests() {
  const data = await api('/api/v1/footage-requests');
  requests = data.requests || [];
  renderRequests();
  renderNotifications();
}

async function searchMatches(event) {
  event.preventDefault();
  const coordinates = parseCoordinates($('location').value);
  if (!coordinates) {
    showToast('Enter coordinates as latitude, longitude.');
    return;
  }
  const timeStart = new Date($('fromTime').value);
  const timeEnd = new Date($('toTime').value);
  if (Number.isNaN(timeStart.getTime()) || Number.isNaN(timeEnd.getTime()) || timeEnd <= timeStart) {
    showToast('Choose a valid time range.');
    return;
  }
  const radiusMeters = Math.round(Number($('radius').value) * 1000);
  try {
    setApiStatus('Searching backend…');
    const result = await api('/api/v1/matches/search', {
      method: 'POST',
      body: JSON.stringify({ latitude: coordinates.latitude, longitude: coordinates.longitude, radius_meters: radiusMeters, time_start: timeStart.toISOString(), time_end: timeEnd.toISOString() })
    });
    activeSearch = { latitude: coordinates.latitude, longitude: coordinates.longitude, radiusMeters: radiusMeters, timeStart: timeStart, timeEnd: timeEnd };
    matches = result.matches || [];
    selectedVehicles.clear();
    $('mapLocation').textContent = coordinates.latitude.toFixed(5) + ', ' + coordinates.longitude.toFixed(5);
    renderMatches();
    setApiStatus('Backend connected · live GPS matches', true);
    if (!matches.length) showToast('No vehicles matched this location and time range.');
  } catch (error) {
    setApiStatus('Backend connection problem');
    showToast(error.message);
  }
}

async function sendRequests() {
  if (!activeSearch || selectedVehicles.size === 0) return;
  try {
    const result = await api('/api/v1/footage-requests', {
      method: 'POST',
      body: JSON.stringify({
        latitude: activeSearch.latitude,
        longitude: activeSearch.longitude,
        radius_meters: activeSearch.radiusMeters,
        time_start: activeSearch.timeStart.toISOString(),
        time_end: activeSearch.timeEnd.toISOString(),
        vehicle_reg_numbers: Array.from(selectedVehicles),
        requester_name: 'Authorized Viewer',
        requester_organization: 'SurakshaNethra Demo'
      })
    });
    const count = (result.requests || []).length;
    selectedVehicles.clear();
    await loadRequests();
    renderMatches();
    showToast((count + ' footage request' + (count === 1 ? '' : 's') + ' saved. ') + (result.notification_note || 'Phones will receive requests through app polling.'));
  } catch (error) {
    showToast(error.message);
  }
}

async function connectBackend() {
  apiBaseUrl = $('apiBaseUrl').value.trim().replace(/\/+$/, '');
  apiKey = $('apiKey').value.trim();
  sessionStorage.setItem(API_BASE_KEY, apiBaseUrl);
  sessionStorage.setItem(API_KEY_KEY, apiKey);
  $('connectionMessage').textContent = 'Checking backend and API key…';
  try {
    await loadRequests();
    $('connectionDialog').close();
    setApiStatus('Backend connected', true);
    showToast('Backend connected. GPS matches are loaded from MongoDB.');
  } catch (error) {
    $('connectionMessage').textContent = error.message;
    setApiStatus('Backend connection problem');
  }
}

$('connectionSettings').addEventListener('click', openConnectionDialog);
$('closeEvidence').addEventListener('click', closeEvidence);
$('evidenceDialog').addEventListener('click', (event) => {
  if (event.target === $('evidenceDialog')) closeEvidence();
});
$('evidenceDialog').addEventListener('close', () => {
  const player = $('evidencePlayer');
  player.pause();
  player.removeAttribute('src');
  player.load();
  if (window.evidenceObjectUrl) {
    URL.revokeObjectURL(window.evidenceObjectUrl);
    window.evidenceObjectUrl = null;
  }
});
document.querySelectorAll('#themeToggle, #themeToggleDashboard').forEach((button) => button.addEventListener('click', () => {
  localStorage.setItem('sn-theme', (localStorage.getItem('sn-theme') || 'light') === 'dark' ? 'light' : 'dark');
  applyTheme();
}));
$('cancelConnection').addEventListener('click', () => $('connectionDialog').close());
$('saveConnection').addEventListener('click', connectBackend);
$('connectionForm').addEventListener('submit', (event) => event.preventDefault());
$('searchForm').addEventListener('submit', searchMatches);
$('sendRequests').addEventListener('click', sendRequests);
$('notificationButton').addEventListener('click', () => $('notificationDrawer').classList.add('open'));
$('notificationsLink').addEventListener('click', (event) => {
  event.preventDefault();
  $('notificationDrawer').classList.add('open');
});
$('closeNotifications').addEventListener('click', () => $('notificationDrawer').classList.remove('open'));
$('viewAll').addEventListener('click', () => $('evidence').scrollIntoView({ behavior: 'smooth', block: 'start' }));

const now = new Date();
now.setSeconds(0, 0);
const start = new Date(now.getTime() - 15 * 60000);
$('fromTime').value = localDateTimeValue(start);
$('toTime').value = localDateTimeValue(now);
$('mapLocation').textContent = 'Incident coordinates';
renderMatches();
renderRequests();
renderNotifications();
if (apiKey && apiBaseUrl) {
  loadRequests().then(() => setApiStatus('Backend connected', true)).catch(() => setApiStatus('Backend connection problem'));
} else {
  setApiStatus('Backend not configured');
}
// Phones report hashes and decisions in the background, so keep the tracker current.
setInterval(() => {
  if (apiKey && apiRoot()) loadRequests().catch(() => {});
}, 15000);
if (window.lucide) lucide.createIcons();
applyTheme();
