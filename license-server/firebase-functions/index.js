// Firebase Cloud Functions version — no server needed!
const functions = require('firebase-functions');
const admin = require('firebase-admin');
const crypto = require('crypto');

admin.initializeApp();
const db = admin.firestore();
const licensesCol = db.collection('licenses');

const ADMIN_KEY = functions.config().license?.admin_key || 'claw-roox-admin-2026';

// CORS helper
function cors(res) {
    res.set('Access-Control-Allow-Origin', '*');
    res.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
}

function adminAuth(req) {
    const auth = req.headers.authorization?.replace('Bearer ', '');
    return auth === ADMIN_KEY;
}

function generateKey() {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
    const part = () => Array.from({ length: 4 }, () => chars[crypto.randomInt(chars.length)]).join('');
    return `CLAW-${part()}-${part()}-${part()}`;
}

exports.api = functions.https.onRequest(async (req, res) => {
    cors(res);
    if (req.method === 'OPTIONS') return res.status(204).end();

    const path = req.path.replace(/^\//, '');

    try {
        // === PUBLIC ===
        if (path === 'activate' && req.method === 'POST') {
            const { key, deviceId, deviceName, appVersion } = req.body;
            if (!key || !deviceId) return res.status(400).json({ error: 'Missing key or deviceId' });

            const doc = await licensesCol.doc(key).get();
            if (!doc.exists) return res.json({ success: false, error: 'Invalid license key' });

            const license = doc.data();
            if (license.revoked) return res.json({ success: false, error: 'License has been revoked' });
            if (license.expiresAt < Date.now()) return res.json({ success: false, error: 'License expired' });
            if (license.deviceId && license.deviceId !== deviceId) {
                return res.json({ success: false, error: 'License already activated on another device' });
            }

            await licensesCol.doc(key).update({
                deviceId,
                deviceName: deviceName || 'Unknown',
                lastSeen: Date.now(),
                appVersion: appVersion || null,
                activatedAt: license.activatedAt || Date.now()
            });

            const daysLeft = Math.ceil((license.expiresAt - Date.now()) / (24 * 60 * 60 * 1000));
            return res.json({ success: true, expiresAt: license.expiresAt, plan: license.plan, daysLeft });
        }

        if (path === 'verify' && req.method === 'POST') {
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
            return res.json({ valid: true, expiresAt: license.expiresAt, plan: license.plan, daysLeft });
        }

        if (path === 'health') {
            const snapshot = await licensesCol.count().get();
            return res.json({ ok: true, keys: snapshot.data().count, storage: 'firestore' });
        }

        // === ADMIN ===
        if (path.startsWith('admin/')) {
            if (!adminAuth(req)) return res.status(401).json({ error: 'Unauthorized' });

            if (path === 'admin/generate' && req.method === 'POST') {
                const { duration = 30, plan = 'monthly', note = '' } = req.body;
                const key = generateKey();
                const expiresAt = Date.now() + (duration * 24 * 60 * 60 * 1000);
                await licensesCol.doc(key).set({
                    key, plan, note, expiresAt,
                    createdAt: Date.now(),
                    deviceId: null, deviceName: null,
                    activatedAt: null, lastSeen: null,
                    revoked: false, appVersion: null
                });
                return res.json({ key, expiresAt, plan, duration });
            }

            if (path === 'admin/revoke' && req.method === 'POST') {
                const { key } = req.body;
                const doc = await licensesCol.doc(key).get();
                if (!doc.exists) return res.status(404).json({ error: 'Key not found' });
                await licensesCol.doc(key).update({ revoked: true });
                return res.json({ success: true, key });
            }

            if (path === 'admin/unrevoke' && req.method === 'POST') {
                const { key } = req.body;
                const doc = await licensesCol.doc(key).get();
                if (!doc.exists) return res.status(404).json({ error: 'Key not found' });
                await licensesCol.doc(key).update({ revoked: false });
                return res.json({ success: true, key });
            }

            if (path === 'admin/extend' && req.method === 'POST') {
                const { key, days = 30 } = req.body;
                const doc = await licensesCol.doc(key).get();
                if (!doc.exists) return res.status(404).json({ error: 'Key not found' });
                const newExpiry = doc.data().expiresAt + (days * 24 * 60 * 60 * 1000);
                await licensesCol.doc(key).update({ expiresAt: newExpiry });
                return res.json({ success: true, key, newExpiry: new Date(newExpiry).toISOString() });
            }

            if (path === 'admin/unbind' && req.method === 'POST') {
                const { key } = req.body;
                const doc = await licensesCol.doc(key).get();
                if (!doc.exists) return res.status(404).json({ error: 'Key not found' });
                await licensesCol.doc(key).update({ deviceId: null, deviceName: null });
                return res.json({ success: true, key });
            }

            if (path === 'admin/list') {
                const snapshot = await licensesCol.orderBy('createdAt', 'desc').get();
                const list = snapshot.docs.map(doc => {
                    const l = doc.data();
                    return {
                        key: l.key, plan: l.plan, note: l.note,
                        status: l.revoked ? 'REVOKED' : (l.expiresAt < Date.now() ? 'EXPIRED' : 'ACTIVE'),
                        device: l.deviceName || 'Not activated',
                        daysLeft: Math.ceil((l.expiresAt - Date.now()) / (24 * 60 * 60 * 1000)),
                        lastSeen: l.lastSeen ? new Date(l.lastSeen).toISOString() : null,
                        createdAt: new Date(l.createdAt).toISOString()
                    };
                });
                return res.json({ count: list.length, licenses: list });
            }
        }

        res.status(404).json({ error: 'Not found' });
    } catch (e) {
        console.error(e);
        res.status(500).json({ error: 'Server error' });
    }
});
