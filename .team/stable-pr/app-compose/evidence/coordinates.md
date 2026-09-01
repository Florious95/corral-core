# Composition coordinates

- fixed base: `4605951e427f9ba6627375498dcb3c757c05bf36`
- implementation compose head: `dce90469fbfb767bc2a3d2fe1afedc94fda41587`
- implementation tree: `576a2b0e33fea860b11b01f41686fc3f8b98ef87`
- composed app tree: `9e8301f64b377505dbb16f2a82ff4457f978e38b`
- branch: `pr/app-compose-final-candidate`
- worktree: `.team/nodes/pi-compose-dev/worktree`

## Frozen order

1. status-core local `22bc9e6955d78c072e9715ca9e8ef3c3b7a9325a`; cherry-picked base-relative commits `f1c4fdd4…`, `22483b670…`, `22bc9e695…`; resulting candidate stage head `74811b8bbb3eb6969938b34944209f0881867bbe`.
2. session-ui local `123848a9263db00f0c5b0396c9ecbe2a20004938`; cherry-picked the 25 commits in `4605951e…..123848a9…` in topological/reverse-log order; resulting stage head `5fbb28b52…`.
3. provider-icons local `1b12e92d8efb1c0eec41e14a264f9d80ee833ad9`; cherry-picked the 9 commits in `22bc9e69…..1b12e92d…` in topological/reverse-log order; resulting implementation compose head `dce90469…`.

No other product commit was used. Owning branches/histories were not changed.

## Remote coordinate existence and app-tree correspondence

GitHub PR queries verified all owning PRs OPEN at the frozen heads before delivery:

- corral-core PR #66 `9c6dbd178c94b30dedbf54fdf6860308872d5706`, remote `app` tree `58e6b33f981d660ad5a6f03aafd9716330891c43`, equal to local `22bc9e69:app`.
- corral-core PR #65 `0f9261c43b260b04ffbb5e6c4eeb785a399dea3f`, remote `app` tree `f00573dcf435d1cba784cebedbc350fdf13d3540`, equal to local `123848a9:app`.
- corral-core PR #67 `96a075f766dd972edbee0d661461c82d4bf2fef3`, remote `app` tree `2a6f5efcf8f19e13f186e29ed3f211a9ed38c89b`, equal to local `1b12e92d:app`.
- corral-serve PR #4 `bd34e1760f5d0b25006fbe091d8c11d3fdf1df1d` was OPEN at the frozen head; remote filtered root tree `17022f6bd84c06275ae1c333fa18de3235683e03`.

The receipt commit adds only this candidate evidence; product/app identity remains the app tree above.
