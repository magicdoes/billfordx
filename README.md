# Billford

Paper 1.21.4+ rotating trade GUI for MagicSMP.

## Build on GitHub

Upload every file in this project to a GitHub repository. Open **Actions**, select
**Build Billford**, click **Run workflow**, then download the `Billford` artifact.

## Commands

- `/billford` — opens the trade GUI.
- `/billford reload` — reloads configuration and restarts the 20-minute timer.
- `/billford settime <20m|30m|1h>` — changes the reset time in game.
- `/billford trade list` — lists all configured trades.
- `/billford trade add <give-item> <give-amount> <cost-item> <cost-amount>` — adds a trade.
- `/billford trade set <number> <give-item> <give-amount> <cost-item> <cost-amount>` — replaces a trade.
- `/billford trade remove <number>` — removes a trade.

Examples:

```text
/billford settime 30m
/billford trade add GOLDEN_APPLE 8 GOLD_INGOT 32
/billford trade set 1 PISTON 64 NETHERITE_INGOT 1
/billford trade remove 2
```

The clock at slot 48 updates every second while the menu is open.

## FancyHolograms placeholder

Install PlaceholderAPI to enable these synchronized placeholders:

- `%billford_time%` — time remaining, such as `19m 59s`.
- `%billford_interval%` — configured reset interval, such as `20m`.
- `%billford_current_trade%` — current result, such as `64x Piston`.

Use `%billford_time%` in a FancyHolograms text line above the Billford NPC.
