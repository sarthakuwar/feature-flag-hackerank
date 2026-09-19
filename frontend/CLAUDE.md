# frontend/CLAUDE.md

React 19 + Vite 8 single-page app, plain JavaScript (JSX). Repo-wide rules are in `../CLAUDE.md`; this file adds frontend rules only.

Dependencies are pinned in `package.json` (React, ReactDOM, Vite, the React plugin). Add no UI library, router package, state library, or CSS framework. `vite.config.js` is read-only.

## Layout

| Folder (`src/`) | Owns |
|---|---|
| `features/` | One folder per product area: `features/flags/` (list, detail tabs, dialogs), `features/segments/`, `features/applications/`, `features/playground/` |
| `layout/` | `AppShell.jsx`: sidebar, skip link, environment switcher, live-update status, acting-as field |
| `shared/` | Code used by more than one feature |
| `shared/api/` | `client.js` (`request()`) and one function per endpoint in `flags.js` |
| `shared/state/` | `AppStateContext.jsx`: selected environment and acting-as identity; `LiveContext.jsx`: the one event stream for that environment |

- A new product area is a folder `features/<area>/` with a `<Area>Page.jsx`. Register its route in `App.jsx` and its sidebar link in `AppShell.jsx`.
- Move code to `shared/` only when a second feature needs it.
- Routing is the small custom router in `router.jsx` (`Link`, `navigate`, `usePath`). Use `Link` for internal links so the page does not reload.

## API access

- Call the backend only through `request()` in `client.js`. It returns the `data` field and throws `ApiError` with the server's `error.message` and `details`. Never call `fetch` in a component.
- Add one exported function per endpoint to `shared/api/flags.js`. Write each call as a literal `request(...)` so the docs check can see the route. Encode every path segment with `encodeURIComponent`. Send `changedBy` from `useAppState().actingAs` on every write.
- Show errors with `ErrorBanner`, which prints `err.message` as-is plus the server's `formErrors` and `fieldErrors`.
- The base URL is `VITE_API_URL` (default `/api/v1`). In development Vite proxies `/api` to the backend on port 8000.

## State and data loading

- Environment (`DEVELOPMENT`, `STAGING`, `PRODUCTION`) and acting-as identity come from `useAppState()`. Do not keep a second copy in a page.
- Load page data with `useResource()` (`data` is `null` while loading) and run every write through `useAction()` (`busy`, `error`). Give an empty list its own empty state. See `FlagsListPage.jsx`.
- Refresh a page after another client's change with `useLiveEvents()`. Never open an `EventSource` in a component.
- Read and write `localStorage` only through the wrapped helpers in `AppStateContext.jsx`; they survive blocked storage.
- Format enum values with `formatEnumLabel()` and times with `formatRelativeTime()` from `shared/format.js`.

## UI rules

- Every screen shows loading, empty, validation, and error states, and disables the submit button while a request is in flight.
- Controls are keyboard reachable and labeled: real `<button>` and `<input>` elements, `aria-label` on icon-only buttons, `htmlFor` on labels. Build every modal on `Dialog` (role, Escape, focus trap, focus return) and every destructive confirmation on `ConfirmDialog`. Build tab lists on `TabList`.
- After a destructive or bulk action, move focus to the page heading (`tabIndex={-1}` plus a ref).
- No horizontal page scroll from 320 px up.
- Every color, border, and surface comes from a CSS variable in `styles.css`. Define each new variable for the dark default and again under `:root[data-theme="light"]`.
- Style with the existing classes in `styles.css` (`.page`, `.button`, `.panel`, `.badge`, `.empty-state`). Add a new class there only when none fits. No inline color values, no CSS-in-JS.
- Use only local media from `public/`. Never load an image, font, or script from another host.

## Style

- 4-space indent, double quotes, semicolons, trailing commas, lines up to 140 columns (`.editorconfig`, `.prettierrc`).
- Function components with hooks. Default-export one component per file; name the file after it.
- Add no comment that restates the code. Comment only the reason for a non-obvious choice.

## Verification

- There is no frontend test runner. Run `bun run build` in `frontend/`, then `bun start` from the repo root and exercise the change in the browser: happy path, empty list, server error, and narrow width.
