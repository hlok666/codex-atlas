# Codex Atlas Design System

## 1. Visual Theme & Atmosphere

The chosen direction is **Soft Command Surface / 柔光中枢**: a Windows desktop tool that feels calm, polished, and operational without looking like a terminal or a generic admin template.

- Use one continuous pale canvas behind the entire application. Panels belong to the same surface instead of appearing as isolated floating cards.
- White is the main material; a very light sage-gray background gives the shell depth without a gradient.
- Rounded geometry is consistent and deliberate, but there are no macOS traffic-light controls or platform imitation.
- The interface should feel dense enough for repeated technical work while preserving clear breathing room.
- Session state, balance state, and recovery state provide the color. Decoration does not.

## 2. Color

Core tokens:

- `--canvas: #EEF2EE` - continuous app background.
- `--surface: #FFFFFF` - primary working surface.
- `--surface-soft: #F7F9F7` - selected rows, tool strips, secondary zones.
- `--ink: #171A17` - primary text and black command buttons.
- `--ink-secondary: #5F675F` - descriptions and secondary labels.
- `--ink-muted: #8C948C` - metadata and inactive navigation.
- `--line: #E2E7E2` - restrained borders and separators.
- `--accent: #6DDE55` - primary green highlight, active state, positive action.
- `--accent-soft: #E8F8E4` - selected navigation and healthy status backgrounds.
- `--warning: #F2C94C` - retrying or attention required.
- `--danger: #EF6A63` - stopped or failed state.
- `--info: #66A9F2` - informational state.
- `--purple: #8B73E8` - optional category color, never a dominant background.

Rules:

- Use black for decisive commands and green for one primary positive action per view.
- Limit each panel to one accent plus semantic status colors.
- Avoid blue-purple gradients, tinted shadows, decorative glows, and large saturated fields.
- Text contrast must meet WCAG AA on the intended surface.

## 3. Typography

- Latin UI: `Geist Sans Variable`, weights 400, 500, and 600.
- Chinese UI: `Noto Sans SC Variable`, with `Microsoft YaHei UI` as the system fallback.
- Code, model IDs, session IDs, and CLI commands only: `Geist Mono` or `Cascadia Mono`.
- App page title: 28-32 px, weight 600, line-height 1.2.
- Panel title: 16-18 px, weight 600.
- Body and session title: 13-14 px, weight 400/500.
- Metadata: 11-12 px, weight 400.
- Do not use negative letter spacing, excessive uppercase labels, or monospace for general navigation.
- Chinese and English layouts use the same hierarchy; containers must allow the longer language to wrap without resizing the surrounding layout.

## 4. Spacing & Grid

- Base spacing unit: 4 px; primary rhythm: 8 px.
- Desktop shell inset: 20-24 px; shell radius: 22 px.
- Header height: 60 px; left navigation width: 188-204 px.
- Main content uses a 12-column grid with 12 px gutters.
- Standard panel padding: 16 px; large working region padding: 20-24 px.
- Panel radius: 14 px; input/button radius: 9-10 px; status pill radius: 999 px.
- Repeated rows have a stable 48-56 px height. Dynamic state must not shift neighboring content.
- The first desktop viewport should show the recent-session list and at least one recovery/status region without scrolling.

## 5. Layout & Composition

- Build one full-height application shell with a compact left navigation, a continuous top command bar, and one main canvas.
- The home view is list-first: recent sessions are the visual anchor, not metric cards.
- Session metrics form a single connected summary strip above the list, separated by quiet dividers.
- Recovery state and provider balance occupy a contextual right rail or integrated side region aligned to the session stream.
- Integrations, versions, and Skills are secondary routes; they do not compete with recent sessions on the home view.
- Use shared grid lines and aligned baselines to connect regions. Avoid large gaps that make modules look unrelated.
- Detail views open as an anchored side panel within the shell, not as a floating card over another card.
- The floating desktop status window is a separate compact surface, not visually nested in the main application.

## 6. Components

- **Top command bar:** product mark, global search, language switch, notification state, profile/status control.
- **Navigation:** icon plus label; active item uses a soft green fill and dark text. Counts are quiet metadata.
- **Summary strip:** 3-4 values in one continuous row, no individual card shadows.
- **Session row:** state dot, title/preview, workspace/branch, model, update time, one Resume command, overflow menu.
- **Recovery state:** traffic-light dots are semantic only: green healthy, yellow waiting/retrying, red stopped/balance blocked.
- **Provider balance:** concise balance, provider, last checked, and low-balance rule in one integrated block.
- **Buttons:** black primary, white outlined secondary, icon-only tools with tooltips, green reserved for positive selected/healthy actions.
- **Inputs:** light gray fill or white surface with one subtle outline; 38-40 px high.
- **Cards/panels:** one level only. Do not place decorative cards inside cards.
- **Charts:** thin, low-contrast lines or bars with direct labels; charts are supporting evidence, not decoration.

## 7. Motion & Interaction

- Use 160-220 ms ease-out transitions for selection, side-panel entry, and state changes.
- Prototype/theme switching may cross-fade the main canvas for 180 ms; do not animate every panel independently.
- Row hover changes surface color and reveals secondary actions without moving columns.
- Recovery changes use a short status-color transition and optional Windows notification.
- Respect `prefers-reduced-motion`; remove translations and keep opacity changes only.
- No bouncing, parallax, looping ambient motion, or decorative particle effects.

## 8. Voice & Brand

- Product name: `Codex Atlas` until renamed by the user.
- Voice is calm and operational: short labels, direct outcomes, no marketing copy inside the app.
- Chinese is the default prototype language; English is a complete parallel interface, not a partial translation.
- Keep technical names unchanged where accuracy matters: `Codex2API`, `Paseo`, `CC Switch`, model IDs, branches, paths, and CLI commands.
- Error messages state the cause and next action: “余额不足，已暂停自动继续” / “Insufficient balance. Auto-continue paused.”

## 9. Anti-patterns

- No dark control-room or terminal aesthetic.
- No macOS traffic-light buttons, Dock metaphors, or copied Apple window chrome.
- No giant greeting headline or landing-page composition inside the desktop app.
- No four-up dashboard of unrelated cards with large gaps between them.
- No nested cards, floating white islands, gradient orbs, glass blobs, or colored shadows.
- No tiny uppercase monospace labels across the interface.
- No more than one black primary action and one green positive action in the same local region.
- No exact copying of layouts, logos, charts, copy, avatars, or business data from the supplied reference collage.
- No visual-only status; every red/yellow/green state also has a text label or accessible name.
