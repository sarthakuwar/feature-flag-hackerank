import { useRef } from "react";

// Stable React keys for a list that is edited by appending and removing rows, so a row's local state never
// moves to its neighbor. Call `removeAt(index)` before reporting the shorter list.
export default function useRowKeys(count) {
    const keysRef = useRef([]);
    const nextRef = useRef(0);

    while (keysRef.current.length < count) {
        keysRef.current.push(nextRef.current);
        nextRef.current += 1;
    }
    keysRef.current.length = count;

    return {
        keys: keysRef.current,
        removeAt: (index) => keysRef.current.splice(index, 1),
    };
}
