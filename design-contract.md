# Codex Atlas Reference Design Contract

## Goal

Create a reusable visual direction for a Windows desktop application that manages Codex sessions, recovery automation, CC Switch balances, Paseo imports, Codex versions, Skills, notifications, and a floating status window.

Target audience: developers and power users who repeatedly scan, search, resume, and monitor Codex sessions.

## Evidence

| Evidence | Confidence | What it establishes |
| --- | --- | --- |
| User-provided eight-screen dashboard collage | Provided and observed | Pale continuous canvases, rounded white modules, restrained typography, consistent grids, black commands, bright semantic accents |
| User: “圆润、白色、简洁” | Provided | Light surfaces, rounded geometry, reduced ornament |
| User: “不是仿照 mac” | Provided | No macOS window chrome or platform imitation |
| User: “设计不够连贯和沉浸，板块割裂” | Provided | One coherent shell and shared grid; avoid isolated prototype cards and disconnected dashboard islands |
| User: software must support Chinese and English | Provided | Full bilingual component sizing and copy system |
| Plane, Karakeep, and Refine public GitHub interfaces | Observed, supplemental | Readable sans typography, restrained borders, list-first utility patterns, consistent spacing |
| Default Windows desktop target and React/Tauri direction | Inferred from existing project | Desktop-first responsive shell, native notification and process integration surfaces |

## Reference Boundaries

| Keep | Change | Do not copy |
| --- | --- | --- |
| Soft neutral canvas and white working surfaces | Replace business analytics with Codex sessions and recovery state | Logos, names, avatars, charts, data, and exact layouts |
| Consistent rounded geometry | Make the recent-session stream the main visual anchor | Proprietary brand details or literal screenshots |
| Connected grid and common baselines | Reduce dashboard metrics and emphasize scan/resume/search workflows | Exact color combinations from any single panel |
| Strong sans headings and quiet body copy | Use Chinese-first bilingual typography | Watermarks or reference-image artifacts |
| Black primary commands with sparse bright accents | Use green/yellow/red for actual runtime semantics | Decorative 3D objects that do not explain product state |
| Compact navigation and integrated status regions | Adapt density for repeated Windows desktop use | macOS window controls or Apple-specific metaphors |

## Final Design Stance

Codex Atlas will use a single “Soft Command Surface”: a pale sage-gray desktop shell containing one connected white working canvas. The recent-session stream is central; search, Resume, recovery, balance, and notifications stay visually attached to that stream through aligned summary and side regions. Typography is plain, confident, and readable. Color is sparse and semantic. Multiple layout prototypes may vary the placement of the session stream and status region, but they must all use this one visual system.

## Risks And Unknowns

- Product name and final logo are still provisional.
- Exact Windows window dimensions and minimum supported display resolution are not confirmed; use 1280×800 as the prototype floor and verify 1440×900.
- The collage does not identify its original products or fonts; font choices are inferred from observed visual characteristics.
- Full session-content density will only be known after scanning real Codex logs; row sizing must be tested with long Chinese titles and file paths.
- The floating status window needs a separate small-window prototype after the main shell is accepted.

## Quality Gate

- [x] The app reads as one connected surface at first glance.
- [x] Recent sessions, search, Resume, recovery, and provider status fit in the first desktop viewport.
- [x] Chinese and English share one hierarchy and stable control sizes.
- [x] General UI text is sans-serif; monospace is limited to code and identifiers.
- [x] No macOS chrome, dark terminal styling, nested cards, gradient orbs, or copied reference content.
- [x] Semantic state is communicated by both color and text.
- [x] A second implementation pass can use the token, spacing, layout, and component rules without guessing.
