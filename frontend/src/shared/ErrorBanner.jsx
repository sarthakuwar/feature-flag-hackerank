function humanizeField(field) {
    return field
        .replace(/\[(\d+)]/g, (_, index) => ` ${Number(index) + 1}`)
        .replace(/\./g, " › ")
        .replace(/([a-z])([A-Z])/g, "$1 $2")
        .toLowerCase();
}

// A CONFLICT error offers a Reload button: `onReload` when the page can refetch, otherwise a full page reload.
export default function ErrorBanner({ error, onReload }) {
    if (!error) return null;
    const message = typeof error === "string" ? error : error.message;
    const formErrors = error.details?.formErrors ?? [];
    const fieldErrors = Object.entries(error.details?.fieldErrors ?? {});

    return (
        <div className="banner banner-error" role="alert">
            <p className="banner-title">{message}</p>
            {error.code === "CONFLICT" && (
                <button type="button" className="button" onClick={onReload || (() => window.location.reload())}>
                    Reload
                </button>
            )}
            {(formErrors.length > 0 || fieldErrors.length > 0) && (
                <ul className="banner-list">
                    {formErrors.map((text) => (
                        <li key={text}>{text}</li>
                    ))}
                    {fieldErrors.flatMap(([field, messages]) =>
                        messages.map((text) => (
                            <li key={`${field}:${text}`}>
                                <strong>{humanizeField(field)}</strong> — {text}
                            </li>
                        )),
                    )}
                </ul>
            )}
        </div>
    );
}
