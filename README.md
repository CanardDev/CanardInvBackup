# CanardInvBackup

Inventory backups for Paper servers. When a player loses their stuff to a bug, a lag spike or a crash, staff can look at what they had and give it back in one command.

Every backup contains the **whole inventory** (armor and off-hand included) **and the ender chest**.

## Features

- Automatic backups on **quit**, **death** and **server stop**
- Optional daily backup at a fixed time (handy just before an automatic restart)
- Manual backups of one player or everyone
- **Read-only preview** of the inventory and the ender chest in a menu
- Restore the inventory, the ender chest or both
- Before restoring, the player's current items are saved too, so a mistake can always be undone
- Number of backups kept per player is configurable
- Works with offline players (list and preview)
- Files are written in the background: no lag, even with many players
- English and French messages, fully editable

## Commands

| Command | Description |
|---|---|
| `/invbackup list <player>` | List a player's backups |
| `/invbackup view <player> [n]` | Look at the inventory of backup `n` (1 = newest) |
| `/invbackup ender <player> [n]` | Look at the ender chest of backup `n` |
| `/invbackup restore <player> <n> [all\|inv\|ender]` | Give a backup back (player must be online) |
| `/invbackup save [player\|all]` | Save now |
| `/invbackup reload` | Reload the config and messages |

Aliases: `/ib`, `/invb`

## Permissions

| Permission | Default | Description |
|---|---|---|
| `invbackup.use` | op | Use every `/invbackup` command |

## Configuration

```yaml
language: en            # en or fr

max-backups: 5

save-on:
  quit: true
  death: true
  shutdown: true

scheduled:
  enabled: false
  time: "03:55"

timezone: "Europe/Paris"
date-format: "dd/MM/yyyy HH:mm:ss"
```

Backups are stored in `plugins/CanardInvBackup/data/<uuid>.yml`.

## Requirements

- Paper 1.21 or newer (or a Paper fork like Purpur)
- Java 21

## Installation

1. Download the `.jar` from the [Releases](../../releases) page
2. Put it in your `plugins/` folder
3. Restart the server and edit `plugins/CanardInvBackup/config.yml`

## Building

```
mvn package
```

---

Made by **CanardDev**. Questions or custom plugins: [Discord](https://discord.com/users/982732557365964881)
