# Bounded local fixture results

These are 300-record drains, not a steady-state or acceptance throughput target.

| profile | wall ms median (min/max) | poll ms | find ms | save ms | publish timer ms | lock skips min/max |
|---|---:|---:|---:|---:|---:|---:|
| baseline | 12254.7 (11996.5/12929.1) | 2126.6 | 64.5 | 1677.7 | 380.5 | 0/0 |
| delay-only | 9407.6 (8960.4/9702.4) | 2091.9 | 57.1 | 1632.3 | 377.6 | 12/12 |
| larger-batch | 1825.4 (1817.6/1999.1) | 1810.6 | 9.7 | 1485.7 | 321.9 | 0/0 |
| no-min-lock-control | 3094.4 (2894.8/3201.3) | 2034.0 | 29.9 | 1651.4 | 349.6 | 0/0 |

| producer-only window | wall ms median (min/max) |
|---|---:|
| 1 | 238.1 (198.0/249.7) |
| 32 | 24.8 (20.0/35.0) |
