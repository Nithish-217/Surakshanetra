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

const DAY_MS = 86400000;

function plural(count, word) {
  return count + ' ' + word + (count === 1 ? '' : 's');
}

function timeAgo(value) {
  const time = Date.parse(value);
  if (!Number.isFinite(time)) return 'Never';
  const seconds = Math.max(0, Math.round((Date.now() - time) / 1000));
  if (seconds < 60) return seconds + ' s ago';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return minutes + ' min ago';
  const hours = Math.round(minutes / 60);
  if (hours < 48) return hours + ' h ago';
  return plural(Math.round(hours / 24), 'day') + ' ago';
}

// Request-age colours from the project brief: yellow when sent, green after one
// week, red after two weeks, expired once the 30-day backend deadline passes.
function statusInfo(request) {
  const state = String(request.status || 'approval_pending').toLowerCase();
  if (state === 'approved') return { key: 'approved', label: 'Approved', pending: false, detail: '' };
  if (state === 'declined') return { key: 'declined', label: 'Declined', pending: false, detail: '' };
  const deadline = Date.parse(request.deadline_at);
  if (state === 'expired' || (Number.isFinite(deadline) && deadline <= Date.now())) {
    return { key: 'expired', label: 'Expired', pending: false, detail: 'No reply within 30 days' };
  }
  const created = Date.parse(request.created_at);
  const ageDays = Number.isFinite(created) ? Math.max(0, (Date.now() - created) / DAY_MS) : 0;
  const ageText = ageDays < 1 ? 'sent today' : plural(Math.floor(ageDays), 'day');
  const detail = Number.isFinite(deadline) ? 'Expires in ' + plural(Math.max(1, Math.ceil((deadline - Date.now()) / DAY_MS)), 'day') : '';
  const label = 'Pending · ' + ageText;
  if (ageDays >= 14) return { key: 'overdue', label: label, pending: true, detail: detail };
  if (ageDays >= 7) return { key: 'acknowledged', label: label, pending: true, detail: detail };
  return { key: 'pending', label: label, pending: true, detail: detail };
}

function copyText(text, successMessage) {
  const fallback = () => {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    let copied = false;
    try { copied = document.execCommand('copy'); } catch (_) {}
    area.remove();
    showToast(copied ? successMessage : 'Could not copy. Select and copy it manually: ' + text);
  };
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).then(() => showToast(successMessage), fallback);
  } else {
    fallback();
  }
}

function updateSelection() {
  $('selectionText').textContent = selectedVehicles.size + ' selected';
  $('sendRequests').disabled = selectedVehicles.size === 0 || !activeSearch;
}

// ---- Live map (Leaflet + OpenStreetMap). Falls back to the static sketch if Leaflet cannot load.
const DEFAULT_MAP_CENTER = [12.9716, 77.5946];
let map = null;
let mapLayers = null;
let mapNeedsFit = false;
let phonesFitted = false;

function initMap() {
  if (map) return true;
  if (!window.L) return false;
  const element = $('leafletMap');
  element.hidden = false;
  $('mapPanel').classList.add('has-leaflet');
  map = L.map(element, { scrollWheelZoom: true }).setView(DEFAULT_MAP_CENTER, 12);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
  }).addTo(map);
  mapLayers = { phones: L.layerGroup().addTo(map), search: L.layerGroup().addTo(map) };
  setTimeout(() => map.invalidateSize(), 60);
  window.addEventListener('resize', () => map.invalidateSize());
  return true;
}

function pinIcon(kind, iconName, selected) {
  return L.divIcon({
    className: 'lf-pin-wrap',
    html: '<span class="lf-pin ' + kind + (selected ? ' selected' : '') + '"><i data-lucide="' + iconName + '"></i></span>',
    iconSize: [31, 31],
    iconAnchor: [15, 15]
  });
}

function toggleVehicle(id) {
  selectedVehicles.has(id) ? selectedVehicles.delete(id) : selectedVehicles.add(id);
  renderMatches();
}

function renderSearchOnMap() {
  if (!initMap()) return false;
  mapLayers.search.clearLayers();
  if (activeSearch) {
    const center = [activeSearch.latitude, activeSearch.longitude];
    const circle = L.circle(center, { radius: activeSearch.radiusMeters, color: '#b03737', weight: 1.5, dashArray: '6 6', fillColor: '#b03737', fillOpacity: 0.06 }).addTo(mapLayers.search);
    // Keep the incident under vehicle pins: vehicles at the scene must stay clickable.
    L.marker(center, { icon: pinIcon('incident', 'alert-triangle'), zIndexOffset: -1000, keyboard: false })
      .bindTooltip('Incident · ' + formatDistance(activeSearch.radiusMeters) + ' radius', { direction: 'top', offset: [0, -14] })
      .addTo(mapLayers.search);
    matches.forEach((vehicle) => {
      if (!Number.isFinite(vehicle.latitude) || !Number.isFinite(vehicle.longitude)) return;
      const id = vehicle.vehicle_reg_number;
      const selected = selectedVehicles.has(id);
      L.marker([vehicle.latitude, vehicle.longitude], { icon: pinIcon('vehicle', 'car-front', selected), title: id, zIndexOffset: selected ? 500 : 0 })
        .bindTooltip(id + (selected ? ' · selected' : ' · click to select'), { direction: 'top', offset: [0, -14] })
        .on('click', () => toggleVehicle(id))
        .addTo(mapLayers.search);
    });
    if (mapNeedsFit) {
      map.fitBounds(circle.getBounds(), { padding: [28, 28] });
      mapNeedsFit = false;
    }
  }
  if (window.lucide) lucide.createIcons();
  return true;
}

function renderPhonesOnMap() {
  if (!initMap()) return;
  mapLayers.phones.clearLayers();
  const points = [];
  devices.forEach((device) => {
    if (!Number.isFinite(device.latitude) || !Number.isFinite(device.longitude)) return;
    const state = phoneState(device);
    const point = [device.latitude, device.longitude];
    points.push(point);
    L.circleMarker(point, { radius: 6, weight: 2, color: '#ffffff', fillColor: state.key === 'online' ? '#35a36e' : (state.key === 'idle' ? '#c48a12' : '#7d8a85'), fillOpacity: 0.95 })
      .bindTooltip(device.vehicle_reg_number + ' · ' + state.label + ' · GPS ' + timeAgo(device.last_ping_at), { direction: 'top' })
      .addTo(mapLayers.phones);
  });
  // Before any search, open the map where the phones actually are.
  if (!phonesFitted && !activeSearch && points.length) {
    map.fitBounds(L.latLngBounds(points).pad(0.3), { maxZoom: 15 });
    phonesFitted = true;
  }
}

function focusMap(latitude, longitude) {
  if (!initMap()) return;
  map.setView([latitude, longitude], Math.max(map.getZoom(), 16));
  $('mapPanel').scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function renderMatches() {
  $('matchCount').textContent = matches.length;
  if (!matches.length) {
    $('matchList').innerHTML = '<p class="vehicle-meta">' + (activeSearch ? 'No vehicles match these coordinates and time range.' : 'Enter incident details and search to see matching vehicles.') + '</p>';
    if (!renderSearchOnMap()) $('mapMarkers').innerHTML = '';
    updateSelection();
    return;
  }
  const center = activeSearch;
  $('matchList').innerHTML = matches.map((vehicle) => {
    const id = vehicle.vehicle_reg_number;
    const meters = vehicle.distance_meters == null ? distanceMeters(center.latitude, center.longitude, vehicle.latitude, vehicle.longitude) : vehicle.distance_meters;
    return '<label class="match-row"><input type="checkbox" data-vehicle="' + escapeHtml(id) + '" ' + (selectedVehicles.has(id) ? 'checked' : '') + '><span><span class="vehicle-name">' + escapeHtml(id) + '</span><span class="vehicle-meta">' + escapeHtml(vehicle.owner_name || 'Vehicle owner') + ' · GPS ' + escapeHtml(formatDateTime(vehicle.last_matching_ping)) + '</span></span><span class="distance"><strong>' + escapeHtml(formatDistance(meters)) + '</strong>from incident</span></label>';
  }).join('');

  if (!renderSearchOnMap()) $('mapMarkers').innerHTML = matches.map((vehicle) => {
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
  document.querySelectorAll('[data-map-vehicle]').forEach((marker) => marker.addEventListener('click', () => toggleVehicle(marker.dataset.mapVehicle)));
  if (window.lucide) lucide.createIcons();
  updateSelection();
}

function hashCell(request, state) {
  const alert = request.rejected_upload_count
    ? '<small class="hash-alert">' + escapeHtml(request.rejected_upload_count) + ' upload(s) rejected: hash did not match</small>'
    : '';
  if (!request.sha256) {
    return '<span class="evidence-pending">' + (state.pending ? 'Waiting for phone to hash clip' : 'Not recorded') + '</span>' + alert;
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
      : '<span class="evidence-pending">' + (state.pending ? 'Waiting for consent' : 'No clip') + '</span>';
    const delivery = request.notification_status === 'sent_fcm' ? 'Push sent' : (state.pending ? 'Waiting for phone poll' : '');
    const subline = [state.detail, delivery].filter(Boolean).join(' · ');
    return '<tr><td class="vehicle-cell">' + escapeHtml(vehicle) + '</td><td>' + escapeHtml(windowText) + '</td><td>' + escapeHtml(formatDateTime(request.created_at)) + '</td><td><span class="status ' + state.key + '">' + escapeHtml(state.label) + '</span>' + (subline ? '<small class="subline">' + escapeHtml(subline) + '</small>' : '') + '</td><td class="hash-cell">' + hashCell(request, state) + '</td><td>' + evidence + '</td><td><button class="row-menu" type="button" data-row-menu="' + escapeHtml(request.request_id) + '" aria-haspopup="menu" aria-expanded="false" aria-label="More actions for ' + escapeHtml(vehicle) + '" title="More actions"><i data-lucide="more-horizontal"></i></button></td></tr>';
  }).join('') : '<tr><td colspan="7" class="vehicle-meta">No requests are stored on the backend yet.</td></tr>';
  document.querySelectorAll('[data-evidence-url]').forEach((button) => button.addEventListener('click', () => openEvidence(button.dataset.evidenceUrl)));
  document.querySelectorAll('[data-copy-hash]').forEach((button) => button.addEventListener('click', () => copyText(button.dataset.copyHash, 'Hash copied.')));
  if (window.lucide) lucide.createIcons();
}

// ---- Row actions menu ("⋯" in the request tracker)
function closeRowMenu(returnFocus) {
  const menu = $('rowMenu');
  if (menu.hidden) return;
  const id = menu.dataset.requestId;
  menu.hidden = true;
  const trigger = document.querySelector('[data-row-menu="' + CSS.escape(id || '') + '"]');
  if (trigger) {
    trigger.setAttribute('aria-expanded', 'false');
    if (returnFocus) trigger.focus();
  }
}

function openRowMenu(trigger) {
  const request = requests.find((item) => item.request_id === trigger.dataset.rowMenu);
  if (!request) return;
  closeRowMenu();
  const menu = $('rowMenu');
  const items = [
    ['copy-id', 'copy', 'Copy request ID', true],
    ['copy-hash', 'hash', 'Copy recorded hash', Boolean(request.sha256)],
    ['view-clip', 'film', 'View clip', statusInfo(request).key === 'approved' && Boolean(request.evidence_url)],
    ['library', 'clapperboard', 'Open in Evidence Library', true]
  ];
  menu.innerHTML = '<p class="row-menu-title">' + escapeHtml(request.vehicle_reg_number) + '<small>' + escapeHtml(request.request_id) + '</small></p>'
    + items.map((item) => '<button type="button" role="menuitem" data-menu-action="' + item[0] + '"' + (item[3] ? '' : ' disabled') + '><i data-lucide="' + item[1] + '"></i>' + escapeHtml(item[2]) + '</button>').join('');
  menu.dataset.requestId = request.request_id;
  menu.hidden = false;
  if (window.lucide) lucide.createIcons();
  const rect = trigger.getBoundingClientRect();
  const left = Math.max(8, Math.min(rect.right - menu.offsetWidth, window.innerWidth - menu.offsetWidth - 8));
  let top = rect.bottom + 6;
  if (top + menu.offsetHeight > window.innerHeight - 8) top = rect.top - menu.offsetHeight - 6;
  menu.style.left = left + 'px';
  menu.style.top = Math.max(8, top) + 'px';
  trigger.setAttribute('aria-expanded', 'true');
  const first = menu.querySelector('button:not([disabled])');
  if (first) first.focus({ preventScroll: true });
}

function runRowAction(action, requestId) {
  const request = requests.find((item) => item.request_id === requestId);
  if (!request) return;
  if (action === 'copy-id') copyText(request.request_id, 'Request ID copied.');
  if (action === 'copy-hash' && request.sha256) copyText(request.sha256, 'Recorded hash copied.');
  if (action === 'view-clip' && request.evidence_url) openEvidence(request.evidence_url);
  if (action === 'library') window.location.href = 'evidence-library.html#' + encodeURIComponent(request.request_id);
}

// ---- Phone status
let devices = [];
let devicesAvailable = true;

function phoneState(device) {
  const contact = Date.parse(device.last_contact_at || device.last_ping_at);
  if (!Number.isFinite(contact)) return { key: 'offline', label: 'Never seen' };
  const seconds = (Date.now() - contact) / 1000;
  if (seconds < 120) return { key: 'online', label: 'Online' };
  if (seconds < 1800) return { key: 'idle', label: 'Idle' };
  return { key: 'offline', label: 'Offline' };
}

function renderDevices() {
  if (!devicesAvailable) {
    $('phoneSummary').textContent = 'Restart the backend to enable phone status';
    $('phoneTable').innerHTML = '<tr><td colspan="6" class="vehicle-meta">This backend version has no phone status endpoint. Stop run-local.ps1 with Ctrl+C and start it again.</td></tr>';
    return;
  }
  const counts = { online: 0, idle: 0, offline: 0 };
  $('phoneTable').innerHTML = devices.length ? devices.map((device) => {
    const state = phoneState(device);
    counts[state.key] += 1;
    const hasLocation = Number.isFinite(device.latitude) && Number.isFinite(device.longitude);
    const location = hasLocation
      ? '<button class="text-button locate-button" type="button" data-locate="' + device.latitude + ',' + device.longitude + '"><i data-lucide="map-pin"></i>' + device.latitude.toFixed(5) + ', ' + device.longitude.toFixed(5) + '</button>'
        + (Number.isFinite(device.accuracy_meters) ? '<small class="subline">± ' + Math.round(device.accuracy_meters) + ' m accuracy</small>' : '')
      : '<span class="evidence-pending">No GPS yet</span>';
    return '<tr><td class="vehicle-cell">' + escapeHtml(device.vehicle_reg_number) + '<small class="subline">' + escapeHtml(device.owner_name) + '</small></td>'
      + '<td><span class="phone-status ' + state.key + '">' + escapeHtml(state.label) + '</span></td>'
      + '<td>' + escapeHtml(timeAgo(device.last_ping_at)) + (device.last_ping_at ? '<small class="subline">' + escapeHtml(formatDateTime(device.last_ping_at)) + '</small>' : '') + '</td>'
      + '<td>' + escapeHtml(timeAgo(device.last_poll_at)) + '</td>'
      + '<td>' + location + '</td>'
      + '<td>' + escapeHtml(device.ping_count || 0) + '</td></tr>';
  }).join('') : '<tr><td colspan="6" class="vehicle-meta">No phones have registered or sent GPS yet.</td></tr>';
  $('phoneSummary').textContent = devices.length
    ? counts.online + ' online · ' + counts.idle + ' idle · ' + counts.offline + ' offline · ' + plural(devices.length, 'phone')
    : 'No phones yet';
  document.querySelectorAll('[data-locate]').forEach((button) => button.addEventListener('click', () => {
    const parts = button.dataset.locate.split(',').map(Number);
    focusMap(parts[0], parts[1]);
  }));
  renderPhonesOnMap();
  if (window.lucide) lucide.createIcons();
}

async function loadDevices() {
  try {
    const data = await api('/api/v1/devices');
    devices = Array.isArray(data.devices) ? data.devices : [];
    devicesAvailable = true;
  } catch (error) {
    if (!/not found/i.test(error.message)) throw error;
    devicesAvailable = false;
  }
  renderDevices();
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
    mapNeedsFit = true;
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
    loadDevices().catch(() => {});
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
document.addEventListener('click', (event) => {
  const menu = $('rowMenu');
  const trigger = event.target.closest('[data-row-menu]');
  if (trigger) {
    const isOpenForRow = !menu.hidden && menu.dataset.requestId === trigger.dataset.rowMenu;
    isOpenForRow ? closeRowMenu() : openRowMenu(trigger);
    return;
  }
  const item = event.target.closest('[data-menu-action]');
  if (item && !item.disabled) {
    const id = menu.dataset.requestId;
    closeRowMenu();
    runRowAction(item.dataset.menuAction, id);
    return;
  }
  if (!menu.contains(event.target)) closeRowMenu();
});
document.addEventListener('keydown', (event) => {
  const menu = $('rowMenu');
  if (menu.hidden) return;
  if (event.key === 'Escape') { event.preventDefault(); closeRowMenu(true); return; }
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    const buttons = Array.from(menu.querySelectorAll('button:not([disabled])'));
    if (!buttons.length) return;
    event.preventDefault();
    const index = buttons.indexOf(document.activeElement);
    const next = event.key === 'ArrowDown' ? (index + 1) % buttons.length : (index - 1 + buttons.length) % buttons.length;
    buttons[next].focus();
  }
});
window.addEventListener('resize', () => closeRowMenu());
window.addEventListener('scroll', () => closeRowMenu(), true);

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
  loadDevices().catch(() => {});
} else {
  setApiStatus('Backend not configured');
}
// Phones report GPS, hashes and decisions in the background, so keep the tracker and phone list current.
setInterval(() => {
  if (!apiKey || !apiRoot()) return;
  loadRequests().catch(() => {});
  loadDevices().catch(() => {});
}, 15000);
if (window.lucide) lucide.createIcons();
applyTheme();
