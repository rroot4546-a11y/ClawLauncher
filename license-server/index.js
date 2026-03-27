// ClawLauncher License Server
// Deploy as Firebase Cloud Functions or standalone Node.js server

const express = require('express');
const crypto = require('crypto');
const app = express();
app.use(express.json());

// In-memory store (replace with Firestore for production)
// For Firebase: const admin = require('firebase-admin'); admin.initializeApp();
// const db = admin.firestore();

const ADMIN_KEY = process.env.ADMIN_KEY || 'claw-admin-2026-secret';
const licenses = new Map(); // key -> license data

// Middleware: Admin auth
function adminAuth(req, res, next) {
    const auth = req.headers.authorization?.replace('Bearer ', '');
    if (auth !== ADMIN_KEY) return res.status(401).json({ error: 'Unauthorized' });
    next();
}

// Generate license key
function generateKey() {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
    const part = () => Array.from({ length: 4 }, () => chars[crypto.randomInt(chars.length)]).join('');
    return `CLAW-${part()}-${part()}-${part()}`;
}

// === PUBLIC ENDPOINTS ===

// Activate license
app.post('/api/activate', (req, res) => {
    const { key, deviceId, deviceName, appVersion } = req.body;
    if (!key || !deviceId) return res.status(400).json({ error: 'Missing key or deviceId' });

    const license = licenses.get(key);
    if (!license) return res.json({ success: false, error: 'Invalid license key' });
    if (license.revoked) return res.json({ success: false, error: 'License has been revoked' });
    if (license.expiresAt < Date.now()) return res.json({ success: false, error: 'License expired' });

    // Check device binding (max 1 device per key)
    if (license.deviceId && license.deviceId !== deviceId) {
        return res.json({ success: false, error: 'License already activated on another device' });
    }

    // Bind to device
    license.deviceId = deviceId;
    license.deviceName = deviceName || 'Unknown';
    license.lastSeen = Date.now();
    license.appVersion = appVersion;
    license.activatedAt = license.activatedAt || Date.now();

    res.json({
        success: true,
        expiresAt: license.expiresAt,
        plan: license.plan,
        daysLeft: Math.ceil((license.expiresAt - Date.now()) / (24 * 60 * 60 * 1000))
    });
});

// Verify license
app.post('/api/verify', (req, res) => {
    const { key, deviceId } = req.body;
    if (!key || !deviceId) return res.status(400).json({ error: 'Missing params' });

    const license = licenses.get(key);
    if (!license) return res.json({ valid: false, error: 'Invalid key' });
    if (license.revoked) return res.json({ valid: false, error: 'License revoked' });
    if (license.expiresAt < Date.now()) return res.json({ valid: false, error: 'License expired' });
    if (license.deviceId && license.deviceId !== deviceId) {
        return res.json({ valid: false, error: 'Device mismatch' });
    }

    license.lastSeen = Date.now();

    res.json({
        valid: true,
        expiresAt: license.expiresAt,
        plan: license.plan,
        daysLeft: Math.ceil((license.expiresAt - Date.now()) / (24 * 60 * 60 * 1000))
    });
});

// === ADMIN ENDPOINTS ===

// Generate new key
app.post('/api/admin/generate', adminAuth, (req, res) => {
    const { duration = 30, plan = 'monthly', note = '' } = req.body;
    const key = generateKey();
    const expiresAt = Date.now() + (duration * 24 * 60 * 60 * 1000);

    licenses.set(key, {
        key,
        plan,
        note,
        expiresAt,
        createdAt: Date.now(),
        deviceId: null,
        deviceName: null,
        activatedAt: null,
        lastSeen: null,
        revoked: false,
        appVersion: null
    });

    res.json({ key, expiresAt, plan, duration });
});

// Revoke key
app.post('/api/admin/revoke', adminAuth, (req, res) => {
    const { key } = req.body;
    const license = licenses.get(key);
    if (!license) return res.status(404).json({ error: 'Key not found' });
    license.revoked = true;
    res.json({ success: true, key });
});

// Unrevoke key
app.post('/api/admin/unrevoke', adminAuth, (req, res) => {
    const { key } = req.body;
    const license = licenses.get(key);
    if (!license) return res.status(404).json({ error: 'Key not found' });
    license.revoked = false;
    res.json({ success: true, key });
});

// Extend key
app.post('/api/admin/extend', adminAuth, (req, res) => {
    const { key, days = 30 } = req.body;
    const license = licenses.get(key);
    if (!license) return res.status(404).json({ error: 'Key not found' });
    license.expiresAt += days * 24 * 60 * 60 * 1000;
    res.json({ success: true, key, newExpiry: new Date(license.expiresAt).toISOString() });
});

// Unbind device (allow re-activation on different device)
app.post('/api/admin/unbind', adminAuth, (req, res) => {
    const { key } = req.body;
    const license = licenses.get(key);
    if (!license) return res.status(404).json({ error: 'Key not found' });
    license.deviceId = null;
    license.deviceName = null;
    res.json({ success: true, key });
});

// List all keys
app.get('/api/admin/list', adminAuth, (req, res) => {
    const list = Array.from(licenses.values()).map(l => ({
        key: l.key,
        plan: l.plan,
        note: l.note,
        status: l.revoked ? 'REVOKED' : (l.expiresAt < Date.now() ? 'EXPIRED' : 'ACTIVE'),
        device: l.deviceName || 'Not activated',
        daysLeft: Math.ceil((l.expiresAt - Date.now()) / (24 * 60 * 60 * 1000)),
        lastSeen: l.lastSeen ? new Date(l.lastSeen).toISOString() : null,
        createdAt: new Date(l.createdAt).toISOString()
    }));
    res.json({ count: list.length, licenses: list });
});

// Health check
app.get('/api/health', (req, res) => res.json({ ok: true, keys: licenses.size }));

const PORT = process.env.PORT || 3001;
app.listen(PORT, () => console.log(`License server on port ${PORT}`));

// For Firebase Cloud Functions:
// exports.api = require('firebase-functions').https.onRequest(app);
