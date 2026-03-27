# ClawLauncher License Server

Firebase Cloud Functions for managing license keys.

## Setup

1. Create Firebase project: `firebase init functions`
2. Deploy: `firebase deploy --only functions`
3. Update `LICENSE_API` in `LicenseManager.kt`

## Admin Commands (via HTTP)

### Generate Key
```bash
curl -X POST https://YOUR_URL/api/admin/generate \
  -H "Authorization: Bearer YOUR_ADMIN_KEY" \
  -H "Content-Type: application/json" \
  -d '{"duration": 30, "plan": "monthly", "note": "Roox"}'
```

### Revoke Key
```bash
curl -X POST https://YOUR_URL/api/admin/revoke \
  -H "Authorization: Bearer YOUR_ADMIN_KEY" \
  -d '{"key": "CLAW-XXXX-XXXX-XXXX"}'
```

### List Keys
```bash
curl https://YOUR_URL/api/admin/list \
  -H "Authorization: Bearer YOUR_ADMIN_KEY"
```

## License Key Format
`CLAW-XXXX-XXXX-XXXX` (alphanumeric)
