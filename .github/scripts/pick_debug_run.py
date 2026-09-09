import json, sys
try:
    runs = json.load(sys.stdin)
except Exception:
    sys.exit(0)
before = sys.argv[1] if len(sys.argv) > 1 else ''
for r in runs:
    if not (r.get('path') or '').endswith('debug-build.yml'):
        continue
    created = r.get('created_at', '')
    if created and created >= before:
        print(json.dumps({'id': r.get('databaseId'), 'status': r.get('status'), 'conclusion': r.get('conclusion')}))
        sys.exit(0)
sys.exit(0)
