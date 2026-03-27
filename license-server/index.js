// ClawLauncher License Server — Firebase Firestore Edition
const express = require('express');
const crypto = require('crypto');
const admin = require('firebase-admin');

const cors = require('cors');
const app = express();
app.use(cors());
app.use(express.json());

// Initialize Firebase
// Option 1: Service account key file
// admin.initializeApp({ credential: admin.credential.cert(require('./serviceAccountKey.json')) });
// Option 2: Application Default Credentials (for Cloud Run/Functions)
admin.initializeApp({
    credential: admin.credential.cert(require('./serviceAccountKey.json'))
});

const db = admin.firestore();
const licensesCol = db.collection('licenses');

const ADMIN_KEY = process.env.ADMIN_KEY || 'claw-roox-admin-2026';

// Admin auth middleware
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
app.post('/api/activate', async (req, res) => {
    try {
        const { key, deviceId, deviceName, appVersion } = req.body;
        if (!key || !deviceId) return res.status(400).json({ error: 'Missing key or deviceId' });

        const doc = await licensesCol.doc(key).get();
        if (!doc.exists) return res.json({ success: false, error: 'Invalid license key' });

        const license = doc.data();
        if (license.revoked) return res.json({ success: false, error: 'License has been revoked' });
        if (license.expiresAt < Date.now()) return res.json({ success: false, error: 'License expired' });

        // Device binding (1 device per key)
        if (license.deviceId && license.deviceId !== deviceId) {
            return res.json({ success: false, error: 'License already activated on another device' });
        }

        // Bind to device
        await licensesCol.doc(key).update({
            deviceId,
            deviceName: deviceName || 'Unknown',
            lastSeen: Date.now(),
            appVersion: appVersion || null,
            activatedAt: license.activatedAt || Date.now()
        });

        const daysLeft = Math.ceil((license.expiresAt - Date.now()) / (24 * 60 * 60 * 1000));
        res.json({ success: true, expiresAt: license.expiresAt, plan: license.plan, daysLeft });
    } catch (e) {
        console.error('Activate error:', e);
        res.status(500).json({ error: 'Server error' });
    }
});

// Verify license
app.post('/api/verify', async (req, res) => {
    try {
        const { key, deviceId } = req.body;
        if (!key || !deviceId) return res.status(400).json({ error: 'Missing params' });

        const doc = await licensesCol.doc(key).get();
        if (!doc.exists) return res.json({ valid: false, error: 'Invalid key' });

        const license = doc.data();
        if (license.revoked) return res.json({ valid: false, error: 'License revoked' });
        if (license.expiresAt < Date.now()) return res.json({ valid: false, error: 'License expired' });
        if (license.deviceId && license.deviceId !== deviceId) {
            return res.json({ valid: false, error: 'Device mismatch' });
        }

        await licensesCol.doc(key).update({ lastSeen: Date.now() });

        const daysLeft = Math.ceil((license.expiresAt - Date.now()) / (24 * 60 * 60 * 1000));
        res.json({ valid: true, expiresAt: license.expiresAt, plan: license.plan, daysLeft });
    } catch (e) {
        console.error('Verify error:', e);
        res.status(500).json({ error: 'Server error' });
    }
});

// === ADMIN ENDPOINTS ===

// Generate new key
app.post('/api/admin/generate', adminAuth, async (req, res) => {
    try {
        const { duration = 30, plan = 'monthly', note = '' } = req.body;
        const key = generateKey();
        const expiresAt = Date.now() + (duration * 24 * 60 * 60 * 1000);

        await licensesCol.doc(key).set({
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
    } catch (e) {
        console.error('Generate error:', e);
        res.status(500).json({ error: 'Server error' });
    }
});

// Revoke key
app.post('/api/admin/revoke', adminAuth, async (req, res) => {
    try {
        const { key } = req.body;
        const doc = await licensesCol.doc(key).get();
        if (!doc.exists) return res.status(404).json({ error: 'Key not found' });
        await licensesCol.doc(key).update({ revoked: true });
        res.json({ success: true, key });
    } catch (e) {
        res.status(500).json({ error: 'Server error' });
    }
});

// Unrevoke key
app.post('/api/admin/unrevoke', adminAuth, async (req, res) => {
    try {
        const { key } = req.body;
        const doc = await licensesCol.doc(key).get();
        if (!doc.exists) return res.status(404).json({ error: 'Key not found' });
        await licensesCol.doc(key).update({ revoked: false });
        res.json({ success: true, key });
    } catch (e) {
        res.status(500).json({ error: 'Server error' });
    }
});

// Extend key
app.post('/api/admin/extend', adminAuth, async (req, res) => {
    try {
        const { key, days = 30 } = req.body;
        const doc = await licensesCol.doc(key).get();
        if (!doc.exists) return res.status(404).json({ error: 'Key not found' });
        const newExpiry = doc.data().expiresAt + (days * 24 * 60 * 60 * 1000);
        await licensesCol.doc(key).update({ expiresAt: newExpiry });
        res.json({ success: true, key, newExpiry: new Date(newExpiry).toISOString() });
    } catch (e) {
        res.status(500).json({ error: 'Server error' });
    }
});

// Unbind device
app.post('/api/admin/unbind', adminAuth, async (req, res) => {
    try {
        const { key } = req.body;
        const doc = await licensesCol.doc(key).get();
        if (!doc.exists) return res.status(404).json({ error: 'Key not found' });
        await licensesCol.doc(key).update({ deviceId: null, deviceName: null });
        res.json({ success: true, key });
    } catch (e) {
        res.status(500).json({ error: 'Server error' });
    }
});

// List all keys
app.get('/api/admin/list', adminAuth, async (req, res) => {
    try {
        const snapshot = await licensesCol.orderBy('createdAt', 'desc').get();
        const list = snapshot.docs.map(doc => {
            const l = doc.data();
            return {
                key: l.key,
                plan: l.plan,
                note: l.note,
                status: l.revoked ? 'REVOKED' : (l.expiresAt < Date.now() ? 'EXPIRED' : 'ACTIVE'),
                device: l.deviceName || 'Not activated',
                daysLeft: Math.ceil((l.expiresAt - Date.now()) / (24 * 60 * 60 * 1000)),
                lastSeen: l.lastSeen ? new Date(l.lastSeen).toISOString() : null,
                createdAt: new Date(l.createdAt).toISOString()
            };
        });
        res.json({ count: list.length, licenses: list });
    } catch (e) {
        console.error('List error:', e);
        res.status(500).json({ error: 'Server error' });
    }
});

// Health check
app.get('/api/health', async (req, res) => {
    try {
        const snapshot = await licensesCol.count().get();
        res.json({ ok: true, keys: snapshot.data().count, storage: 'firestore' });
    } catch (e) {
        res.json({ ok: false, error: e.message });
    }
});

const PORT = process.env.PORT || 3001;
app.listen(PORT, () => console.log(`License server on port ${PORT} (Firestore)`));
