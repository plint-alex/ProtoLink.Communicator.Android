# Notes and Cloud sync

See the full design doc:

[notes-and-cloud-sync.md](../../ProtoLink.Communicator.Windows/docs/notes-and-cloud-sync.md)

Summary: Notes writes disk; Cloud syncs mapped folders via hash reconcile. **Map** runs an immediate full reconcile into the existing cloud folder. Local-only push never seeds empty metadata (upgrades to full reconcile). Force Upload / Force Download live under Settings → Mapped folders.
