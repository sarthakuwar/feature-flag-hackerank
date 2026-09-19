import { useEffect, useId, useRef } from "react";

const FOCUSABLE_SELECTOR = "a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled])";

// Open dialogs, oldest first. Keys are handled on `document`, so only the topmost dialog may react to them.
const openDialogs = [];

export default function Dialog({ title, onClose, wide = false, closeOnOverlay = true, children }) {
    const dialogRef = useRef(null);
    const onCloseRef = useRef(onClose);
    onCloseRef.current = onClose;
    const titleId = useId();

    useEffect(() => {
        const token = {};
        openDialogs.push(token);
        const opener = document.activeElement;
        const autofocus = dialogRef.current?.querySelector("[data-autofocus]");
        (autofocus || dialogRef.current)?.focus();

        const handleKeyDown = (event) => {
            if (openDialogs[openDialogs.length - 1] !== token) return;
            if (event.key === "Escape") {
                event.preventDefault();
                onCloseRef.current();
                return;
            }
            if (event.key !== "Tab" || !dialogRef.current) return;
            const focusable = Array.from(dialogRef.current.querySelectorAll(FOCUSABLE_SELECTOR));
            if (focusable.length === 0) return;
            const first = focusable[0];
            const last = focusable[focusable.length - 1];
            if (event.shiftKey && (document.activeElement === first || document.activeElement === dialogRef.current)) {
                event.preventDefault();
                last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault();
                first.focus();
            }
        };
        document.addEventListener("keydown", handleKeyDown);
        return () => {
            document.removeEventListener("keydown", handleKeyDown);
            openDialogs.splice(openDialogs.indexOf(token), 1);
            if (opener instanceof HTMLElement && document.contains(opener)) opener.focus();
        };
    }, []);

    return (
        <div className="dialog-overlay" role="presentation" onMouseDown={(event) => closeOnOverlay && event.target === event.currentTarget && onClose()}>
            <div
                ref={dialogRef}
                className="dialog"
                data-wide={wide}
                role="dialog"
                aria-modal="true"
                aria-labelledby={titleId}
                tabIndex={-1}
            >
                <h2 id={titleId}>{title}</h2>
                {children}
            </div>
        </div>
    );
}
