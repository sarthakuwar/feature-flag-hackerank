import { useCallback, useEffect, useRef, useState } from "react";
import useResource from "./useResource.js";

// Loads one page of a paginated list: `load({ page, limit })` resolves to `{ items, meta }`. Changing `deps` (the filters)
// returns to page 1. The previous page stays in `items` while the next one loads, so a pager keeps its focus. If the current
// page no longer exists because rows were removed, it steps back to the last page.
export default function usePagedList(load, deps, limit) {
    const scope = JSON.stringify(deps);
    const [pageState, setPageState] = useState({ scope, page: 1 });
    const page = pageState.scope === scope ? pageState.page : 1;
    const setPage = useCallback((next) => setPageState({ scope, page: next }), [scope]);

    const resource = useResource(() => load({ page, limit }), [scope, page, limit]);
    const previousRef = useRef(null);
    if (resource.data) previousRef.current = resource.data;
    const shown = resource.data ?? (resource.error ? null : previousRef.current);

    const { total } = shown?.meta ?? { total: 0 };
    const empty = shown !== null && shown.items.length === 0;
    useEffect(() => {
        if (empty && total > 0 && page > 1) setPage(Math.ceil(total / limit));
    }, [empty, total, page, limit, setPage]);

    return {
        items: shown?.items ?? null,
        meta: shown?.meta ?? null,
        page,
        setPage,
        scope,
        loading: resource.data === null && !resource.error,
        error: resource.error,
        reload: resource.reload,
    };
}
