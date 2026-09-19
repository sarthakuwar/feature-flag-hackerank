import { useCallback, useState } from "react";

// Runs one write at a time: `busy` disables the submit button and `error` holds the last failure.
export default function useAction() {
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState(null);

    const run = useCallback(async (action) => {
        setBusy(true);
        setError(null);
        try {
            const result = await action();
            return { ok: true, result };
        } catch (err) {
            setError(err);
            return { ok: false, result: null };
        } finally {
            setBusy(false);
        }
    }, []);

    return { busy, error, setError, run };
}
