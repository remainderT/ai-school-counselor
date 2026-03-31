import { useEffect, useState } from "react";
import { subscribeToast, type ToastItem } from "../lib/toast";

export function ToastHost() {
  const [items, setItems] = useState<ToastItem[]>([]);

  useEffect(() => {
    return subscribeToast((item) => {
      setItems((prev) => [...prev, item]);
      window.setTimeout(() => {
        setItems((prev) => prev.filter((it) => it.id !== item.id));
      }, item.durationMs);
    });
  }, []);

  return (
    <div className="toast-host" aria-live="polite" aria-atomic="true">
      {items.map((item) => (
        <div key={item.id} className={`toast ${item.tone}`}>
          {item.message}
        </div>
      ))}
    </div>
  );
}
