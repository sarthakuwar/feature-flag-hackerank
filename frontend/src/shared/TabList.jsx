import { useRef } from "react";

export function tabPanelProps(id, activeTab) {
    return { id: `panel-${id}`, role: "tabpanel", "aria-labelledby": `tab-${id}`, hidden: activeTab !== id };
}

export default function TabList({ tabs, activeTab, onChange, label }) {
    const tabRefs = useRef({});

    const focusTab = (id) => {
        onChange(id);
        tabRefs.current[id]?.focus();
    };

    const handleKeyDown = (event) => {
        const index = tabs.findIndex((tab) => tab.id === activeTab);
        if (event.key === "ArrowRight") {
            event.preventDefault();
            focusTab(tabs[(index + 1) % tabs.length].id);
        } else if (event.key === "ArrowLeft") {
            event.preventDefault();
            focusTab(tabs[(index - 1 + tabs.length) % tabs.length].id);
        } else if (event.key === "Home") {
            event.preventDefault();
            focusTab(tabs[0].id);
        } else if (event.key === "End") {
            event.preventDefault();
            focusTab(tabs[tabs.length - 1].id);
        }
    };

    return (
        <div className="tablist" role="tablist" aria-label={label} onKeyDown={handleKeyDown}>
            {tabs.map((tab) => (
                <button
                    key={tab.id}
                    ref={(el) => {
                        tabRefs.current[tab.id] = el;
                    }}
                    type="button"
                    role="tab"
                    id={`tab-${tab.id}`}
                    aria-selected={activeTab === tab.id}
                    aria-controls={`panel-${tab.id}`}
                    tabIndex={activeTab === tab.id ? 0 : -1}
                    className="tab"
                    onClick={() => onChange(tab.id)}
                >
                    {tab.label}
                    {tab.count != null && <span className="tab-count">{tab.count}</span>}
                </button>
            ))}
        </div>
    );
}
