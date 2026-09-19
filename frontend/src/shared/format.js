export function formatEnumLabel(value) {
    if (!value) return "";
    return value
        .toLowerCase()
        .split("_")
        .map((word) => (word === "id" ? "ID" : word[0].toUpperCase() + word.slice(1)))
        .join(" ");
}

const VARIATION_COLOR_COUNT = 6;

export function variationColor(index) {
    const slot = index < 0 ? 0 : index % VARIATION_COLOR_COUNT;
    return `var(--variation-${slot + 1})`;
}

const RELATIVE_TIME_FORMATTER = new Intl.RelativeTimeFormat("en", { numeric: "auto" });

export function formatRelativeTime(isoString) {
    if (!isoString) return "";
    const diffMs = new Date(isoString).getTime() - Date.now();
    const diffMinutes = Math.round(diffMs / 60000);
    if (Math.abs(diffMinutes) < 60) return RELATIVE_TIME_FORMATTER.format(diffMinutes, "minute");
    const diffHours = Math.round(diffMinutes / 60);
    if (Math.abs(diffHours) < 24) return RELATIVE_TIME_FORMATTER.format(diffHours, "hour");
    const diffDays = Math.round(diffHours / 24);
    return RELATIVE_TIME_FORMATTER.format(diffDays, "day");
}

const DATE_TIME_FORMATTER = new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" });

export function formatDateTime(isoString) {
    if (!isoString) return "";
    return DATE_TIME_FORMATTER.format(new Date(isoString));
}

export function formatDuration(totalMinutes) {
    if (totalMinutes < 60) return `${totalMinutes} min`;
    const days = Math.floor(totalMinutes / 1440);
    const hours = Math.floor((totalMinutes % 1440) / 60);
    const minutes = totalMinutes % 60;
    return [days && `${days} d`, hours && `${hours} h`, minutes && `${minutes} min`].filter(Boolean).join(" ");
}

export function parseKeyList(text) {
    return Array.from(
        new Set(
            text
                .split(/[\n,]/)
                .map((entry) => entry.trim())
                .filter(Boolean),
        ),
    );
}

export function pluralize(count, singular, plural = `${singular}s`) {
    return `${count.toLocaleString("en")} ${count === 1 ? singular : plural}`;
}
