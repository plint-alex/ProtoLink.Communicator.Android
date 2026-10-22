# Notes and Cloud sync

See the full design doc:

[notes-and-cloud-sync.md](../../ProtoLink.Communicator.Windows/docs/notes-and-cloud-sync.md)

Summary: Notes writes disk; Cloud syncs mapped folders via hash reconcile. **Map** runs an immediate full reconcile into the existing cloud folder. Local-only push never seeds empty metadata (upgrades to full reconcile). Force Upload / Force Download live under Settings → Mapped folders.

**Sibling files (decision E5 / E5a, Android):** Notes may show a local **Folder files** list under the open page — **direct-child files only** via SAF (`NotesSiblingFiles` + `SafTreeLister`; exclude `index.html`/`index.htm`; do **not** list directories already in the left Notes tree). VIEW-intent open; soft-empty if the folder URI is gone. Same mental model as Windows `NotesSiblingFiles` (no shared binary across repos). Still no sync chrome in that panel.

**E6 discoverability (Android):** Cloud hub **Add local folder to Cloud…** opens a SAF → create/pick Cloud folder → same `mapFolder` / MappingStore path (create happens *before* map). Open-folder **Map this Cloud folder…** remains (AT-C8). Notes shows a one-line nudge when the Notes root URI is not under any mapping. Settings may deep-link the wizard but is not the only entry. Spec: Agents `docs/features/entity-navigation/map-local-folder-ux`.
