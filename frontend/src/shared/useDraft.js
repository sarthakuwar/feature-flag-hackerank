import { useCallback, useState } from "react";

// A form value that mirrors `serverValue` until the user edits it. An edit survives reloads of the server value, so a live update
// never wipes typing; `reset()` or a change of `scope` (for example another environment) drops it.
export default function useDraft(scope, serverValue) {
    const [edit, setEdit] = useState(null);
    const setValue = useCallback((next) => setEdit({ scope, value: next }), [scope]);
    const reset = useCallback(() => setEdit(null), []);
    const editing = edit !== null && edit.scope === scope;

    return [editing ? edit.value : serverValue, setValue, reset];
}
