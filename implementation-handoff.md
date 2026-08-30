# Implementation Handoff

Read first: `DESIGN.md`, then `design-contract.md`.

## Build Direction

- Produce three layout prototypes inside the same **Soft Command Surface** visual system. Change composition only, not font, palette, radius, or component styling.
- Prototype A (recommended): left navigation + central recent-session stream + integrated right recovery rail.
- Prototype B: slim icon rail + wider session stream + anchored detail panel opened from a selected row.
- Prototype C: top navigation + full-width session stream + bottom status dock for recovery, CC Switch, and Paseo.
- Show one prototype at a time in an immersive full-window preview. Use a compact selector, not a grid of nested screenshots.

## Binding Constraints

- `Geist Sans Variable` + `Noto Sans SC Variable`; monospace only for commands and IDs.
- Canvas `#EEF2EE`, surface `#FFFFFF`, ink `#171A17`, line `#E2E7E2`, accent `#6DDE55`.
- 8 px primary spacing rhythm; shell 22 px radius; panels 14 px; controls 9-10 px.
- No gradients, glass blobs, macOS dots, nested cards, all-uppercase microcopy, or dark-console framing.
- First viewport must visibly include search, five recent sessions, Resume, recovery state, and provider balance.
- Chinese and English switch without layout shift or clipped controls.

## First Artifact Must Prove

- The whole screen feels continuous rather than assembled from separate cards.
- The session stream is unquestionably the primary workflow.
- Recovery and balance information is visible but visually secondary.
- The three layout options are meaningfully different while clearly belonging to one product.
