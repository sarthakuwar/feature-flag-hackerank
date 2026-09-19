# frontend/CLAUDE.md

React 19 + Vite 8 single-page app, plain JavaScript (JSX). Repo-wide rules are in `../CLAUDE.md`; this file adds frontend rules only.

Add no router package, state library, or CSS framework.

## Layout

| Folder (`src/`) | Owns |
|---|---|
| `features/` | One folder per product area: `features/flags/` (list, detail tabs, rollout detail, dialogs), `features/segments/`, `features/applications/` (list and detail), `features/playground/` |
| `layout/` | `AppShell.jsx`: sidebar, skip link, environment switcher, live-update status, acting-as field |
| `shared/` | Code used by more than one feature: `Dialog.jsx`, `ConfirmDialog.jsx`, `TabList.jsx`, `Pager.jsx`, `ErrorBanner.jsx`, `ErrorBoundary.jsx`, `router.jsx`, and the `use*.js` hooks |
| `shared/api/` | `client.js` (`request()`), one function per endpoint in `flags.js`, and `stream.js` (the only `EventSource`) |
| `shared/state/` | `AppStateContext.jsx`: selected environment and acting-as identity; `LiveContext.jsx`: the one event stream for that environment |

- A new product area is a folder `features/<area>/` with a `<Area>Page.jsx`. Register its route in `App.jsx` and its sidebar link in `AppShell.jsx`.
- Move code to `shared/` only when a second feature needs it.
- Routing is the small custom router in `router.jsx` (`Link`, `navigate`, `usePath`). Use `Link` for internal links so the page does not reload. After a route change the router moves focus to the `<h1>` inside `#main-content` and announces it; give every page one `<h1>`.

## API access

- Call the backend only through `request()` in `client.js`. It returns the `data` field, or `{ items, meta }` with `{ paged: true }`. It times out after 15 s and throws `ApiError` (with the server's `error.message` and `details`) for HTTP errors, timeouts, and network failures. Never call `fetch` in a component.
- Add one exported function per endpoint to `shared/api/flags.js`. Write each call as a literal `request(...)` so the docs check can see the route. Encode every path segment with `encodeURIComponent`. Send `changedBy` from `useAppState().actingAs` on every write.
- A list function for a paged route takes `{ page, limit }` and passes `{ paged: true }`; `listFlags()` also takes `q` and `lifecycle`. Render it with `usePagedList()` and `Pager`.
- Show errors with `ErrorBanner`, which prints `err.message` as-is plus the server's `formErrors` and `fieldErrors`, and offers Reload on a `CONFLICT`. Pass `onReload` when the page can refetch.
- The base URL is `VITE_API_URL` (default `/api/v1`). In development Vite proxies `/api` to the backend on port 8000.

## State and data loading

- Environment (`DEVELOPMENT`, `STAGING`, `PRODUCTION`) and acting-as identity come from `useAppState()`. Do not keep a second copy in a page.
- Load page data with `useResource()` (`data` is `null` while loading) and run every write through `useAction()` (`busy`, `error`). Give an empty list its own empty state. See `FlagsListPage.jsx`.
- Refresh a page after another client's change with `useLiveEvents()`. Reload on `event.type === "resync"` as well as on the events you care about, and wrap the reload in `useDebouncedCallback()` so a burst reloads once. `stream.js` reconnects with capped exponential backoff and emits the resync.
- Debounce a search box with `useDebouncedValue()`. Hold a form's unsaved edits in `useDraft()` so a live reload does not wipe them.
- Key a removable row that keeps its own state (an uncontrolled input or a stateful child) with `useRowKeys()`, never `key={index}`.
- Read and write `localStorage` only through the wrapped helpers in `AppStateContext.jsx`; they survive blocked storage.
- Format enum values with `formatEnumLabel()` and times with `formatRelativeTime()` from `shared/format.js`.

## UI rules

- Every screen shows loading, empty, validation, and error states, and disables the submit button while a request is in flight.
- Controls are keyboard reachable and labeled: real `<button>` and `<input>` elements, `aria-label` on icon-only buttons, `htmlFor` on labels. Build every modal on `Dialog` (role, focus trap, focus return; Escape and Tab reach only the topmost dialog) and every destructive confirmation on `ConfirmDialog`. Build tab lists on `TabList`.
- After a destructive or bulk action, move focus to the page heading (`tabIndex={-1}` plus a ref).
- No horizontal page scroll from 320 px up.
- Every color, border, and surface comes from a CSS variable in `styles.css`. Define each new variable for the dark default and again under `:root[data-theme="light"]`.
- Style with the existing classes in `styles.css` (`.page`, `.button`, `.panel`, `.badge`, `.empty-state`). Add a new class there only when none fits. No inline color values, no CSS-in-JS.
- Put media in `public/`.

## Style

- Indent with 4 spaces as `.editorconfig` says (`.prettierrc` sets `useTabs`, but the source uses spaces). Use double quotes, semicolons, trailing commas, and lines up to 140 columns (`.prettierrc`).
- Function components with hooks; `ErrorBoundary` is the one class component, because React has no hook for it. Default-export one component per file; name the file after it.
- Add no comment that restates the code. Comment only the reason for a non-obvious choice.

## Verification

- There is no frontend test runner. Run `bun run build` in `frontend/`, then `bun start` from the repo root and exercise the change in the browser: happy path, empty list, server error, and narrow width.
