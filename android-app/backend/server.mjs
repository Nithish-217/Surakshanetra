import 'dotenv/config';
import { createServer } from 'node:http';
import { MongoClient } from 'mongodb';

const PORT = Number(process.env.PORT || 3000);
const MAX_BODY_BYTES = 1_000_000;
const MAX_BATCH_SIZE = 500;

function normalizeVehicle(value) {
  return String(value ?? '').replace(/[^a-z0-9]/gi, '').toUpperCase();
}

function loadDeviceTokens() {
  let parsed;
  try {
    parsed = JSON.parse(process.env.DEVICE_TOKENS_JSON || '{}');
  } catch {
    throw new Error('DEVICE_TOKENS_JSON must be a JSON object mapping access tokens to vehicle numbers.');
  }

  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
    throw new Error('DEVICE_TOKENS_JSON must be a JSON object.');
  }

  return new Map(
    Object.entries(parsed)
      .filter(([token, vehicle]) => token.length >= 24 && normalizeVehicle(vehicle).length >= 4)
      .map(([token, vehicle]) => [token, normalizeVehicle(vehicle)])
  );
}

function send(response, status, body) {
  const bytes = Buffer.from(JSON.stringify(body));
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': bytes.length,
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff'
  });
  response.end(bytes);
}

async function readJsonBody(request) {
  const chunks = [];
  let totalBytes = 0;
  for await (const chunk of request) {
    totalBytes += chunk.length;
    if (totalBytes > MAX_BODY_BYTES) {
      const error = new Error('Request body is too large.');
      error.status = 413;
      throw error;
    }
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    const error = new Error('Request body must be valid JSON.');
    error.status = 400;
    throw error;
  }
}

function validatePing(ping, authorizedVehicle) {
  if (!ping || typeof ping !== 'object' || Array.isArray(ping)) return 'Each ping must be an object.';
  if (typeof ping.pingId !== 'string' || !/^[a-z0-9-]{16,80}$/i.test(ping.pingId)) return 'Invalid pingId.';
  if (normalizeVehicle(ping.vehicleNumber) !== authorizedVehicle) return 'This access token is not authorized for that vehicle.';

  const recordedAt = new Date(ping.recordedAt);
  if (typeof ping.recordedAt !== 'string' || Number.isNaN(recordedAt.getTime())) return 'Invalid recordedAt timestamp.';

  const coordinates = ping.location?.coordinates;
  if (ping.location?.type !== 'Point' || !Array.isArray(coordinates) || coordinates.length !== 2) return 'location must be a GeoJSON Point.';
  const [longitude, latitude] = coordinates.map(Number);
  if (!Number.isFinite(longitude) || longitude < -180 || longitude > 180) return 'Invalid longitude.';
  if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90) return 'Invalid latitude.';

  const accuracyMeters = Number(ping.accuracyMeters);
  if (!Number.isFinite(accuracyMeters) || accuracyMeters < 0) return 'Invalid accuracyMeters.';

  const deviceId = ping.deviceId == null ? null : String(ping.deviceId);
  if (deviceId != null && !/^[a-z0-9-]{16,80}$/i.test(deviceId)) return 'Invalid deviceId.';
  const recordingId = ping.recordingId == null ? null : String(ping.recordingId);
  if (recordingId != null && !/^[a-z0-9-]{16,80}$/i.test(recordingId)) return 'Invalid recordingId.';

  return {
    pingId: ping.pingId,
    deviceId,
    vehicleNumber: authorizedVehicle,
    recordingId,
    recordedAt,
    location: { type: 'Point', coordinates: [longitude, latitude] },
    accuracyMeters,
    receivedAt: new Date()
  };
}

const mongoUri = process.env.MONGODB_URI;
const databaseName = process.env.MONGODB_DATABASE || 'surakshanetra';
if (!mongoUri) throw new Error('MONGODB_URI is required. Set it in the backend environment, never in the Android app.');
const deviceTokens = loadDeviceTokens();
if (deviceTokens.size === 0) throw new Error('Configure at least one long random token in DEVICE_TOKENS_JSON.');

const mongo = new MongoClient(mongoUri, { appName: 'surakshanetra-location-api' });
await mongo.connect();
const locations = mongo.db(databaseName).collection('vehicle_locations');
await Promise.all([
  locations.createIndex({ pingId: 1 }, { unique: true }),
  locations.createIndex({ vehicleNumber: 1, recordedAt: 1 }),
  locations.createIndex({ deviceId: 1, recordingId: 1, recordedAt: 1 }),
  locations.createIndex({ location: '2dsphere' })
]);

const server = createServer(async (request, response) => {
  const path = new URL(request.url || '/', 'http://localhost').pathname;
  if (request.method === 'GET' && path === '/health') {
    return send(response, 200, { status: 'ok' });
  }
  if (request.method !== 'POST' || path !== '/v1/location-pings/batch') {
    return send(response, 404, { error: 'Not found.' });
  }

  const bearer = /^Bearer\s+(.+)$/i.exec(request.headers.authorization || '');
  const authorizedVehicle = bearer ? deviceTokens.get(bearer[1]) : null;
  if (!authorizedVehicle) return send(response, 401, { error: 'Invalid access token.' });

  try {
    const body = await readJsonBody(request);
    if (!Array.isArray(body?.pings) || body.pings.length === 0 || body.pings.length > MAX_BATCH_SIZE) {
      return send(response, 400, { error: `pings must contain between 1 and ${MAX_BATCH_SIZE} records.` });
    }

    const records = [];
    for (const ping of body.pings) {
      const result = validatePing(ping, authorizedVehicle);
      if (typeof result === 'string') return send(response, 400, { error: result });
      records.push(result);
    }

    try {
      await locations.bulkWrite(
        records.map((record) => ({
          updateOne: {
            filter: { pingId: record.pingId },
            update: { $setOnInsert: record },
            upsert: true
          }
        })),
        { ordered: false }
      );
    } catch (error) {
      // A retry after a lost response may race with an earlier insert. Acknowledge only if
      // every ping ID already exists and belongs to the same authenticated vehicle.
      if (error?.code !== 11000 && !error?.writeErrors?.some((item) => item.code === 11000)) throw error;
      const existing = await locations.find(
        { pingId: { $in: records.map((record) => record.pingId) } },
        { projection: { pingId: 1, vehicleNumber: 1 } }
      ).toArray();
      if (existing.length !== records.length || existing.some((record) => record.vehicleNumber !== authorizedVehicle)) throw error;
    }

    return send(response, 200, { acceptedPingIds: records.map((record) => record.pingId) });
  } catch (error) {
    if (error?.status) return send(response, error.status, { error: error.message });
    console.error('Location batch write failed:', error?.name || 'unknown error');
    return send(response, 503, { error: 'Location service temporarily unavailable.' });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Surakshanetra location API listening on port ${PORT}`);
});

async function shutdown() {
  server.close();
  await mongo.close();
  process.exit(0);
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
