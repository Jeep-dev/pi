from pathlib import Path

p = Path('app/src/main/java/com/piandroid/PiSessionRuntime.kt')
text = p.read_text()
old = '''    @Synchronized
    fun register(records: List<PiSessionRecord>) {
        records.forEach { record ->
            val file = conversationFileKey(record.sessionFile)
            if (file.isNotBlank()) {
                check(conversationOwners[file].let { it == null || it == record.androidSessionId }) {
                    "Pi conversation already has another Android owner: $file"
                }
                conversationOwners[file] = record.androidSessionId
            }
        }
    }
'''
new = '''    @Synchronized
    fun register(records: List<PiSessionRecord>) {
        records.forEach { record ->
            val file = conversationFileKey(record.sessionFile)
            if (file.isNotBlank()) {
                check(conversationOwners[file].let { it == null || it == record.androidSessionId }) {
                    "Pi conversation already has another Android owner: $file"
                }
                conversationOwners[file] = record.androidSessionId
            }
        }
        // The durable Session registry is the set of open terminal-like windows.
        // After an Activity/process restart, every persisted window must attach to
        // its surviving runtime or restart it. Explicitly closed windows were removed
        // from the registry already, so they are intentionally not restarted.
        records.forEach { record ->
            runtime(record).ensureConnected(record, autoStart = true)
        }
    }
'''
if text.count(old) != 1:
    raise SystemExit('register anchor mismatch')
p.write_text(text.replace(old, new, 1))

g = Path('app/build.gradle.kts')
s = g.read_text()
s = s.replace('// v5.19.25 build 125: closing an Android Session preserves all Pi history and on-disk data.',
              '// v5.19.26 build 126: restore and restart every persisted open Session window after App restart.', 1)
s = s.replace('        versionCode = 125', '        versionCode = 126', 1)
s = s.replace('        versionName = "5.19.25"', '        versionName = "5.19.26"', 1)
if 'versionCode = 126' not in s or 'versionName = "5.19.26"' not in s:
    raise SystemExit('version bump failed')
g.write_text(s)
