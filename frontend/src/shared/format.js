export function formatEnumLabel(value) {
    if (!value) return "";
    return value
        .toLowerCase()
        .split("_")
        .map((word) => (word === "id" ? "ID" : word[0].toUpperCase() + word.slice(1)))
        .join(" ");
}

const VARIATION_COLORS = ["#5ee6a0", "#60a5fa", "#f0b429", "#c084fc", "#f472b6", "#38bdf8"];

export function variationColor(index) {
    return VARIATION_COLORS[index % VARIATION_COLORS.length];
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
