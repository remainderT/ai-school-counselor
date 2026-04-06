import { useEffect, useRef, useState } from "react";

export type SelectOption = { value: string; label: string; disabled?: boolean };

export function CustomSelect({
  value,
  options,
  onChange,
  disabled,
  placeholder
}: {
  value: string;
  options: SelectOption[];
  onChange: (v: string) => void;
  disabled?: boolean;
  placeholder?: string;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  const selected = options.find((o) => o.value === value);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [open]);

  return (
    <div
      ref={ref}
      className={`custom-select${disabled ? " custom-select-disabled" : ""}${open ? " custom-select-open" : ""}`}
      onClick={() => !disabled && setOpen((v) => !v)}
    >
      <span className="custom-select-value">
        {selected
          ? selected.label
          : <span className="custom-select-placeholder">{placeholder ?? "请选择"}</span>}
      </span>
      <svg
        className={`custom-select-chevron${open ? " rotated" : ""}`}
        width="14" height="14" viewBox="0 0 24 24"
        fill="none" stroke="currentColor" strokeWidth="2.2"
        strokeLinecap="round" strokeLinejoin="round"
      >
        <polyline points="6 9 12 15 18 9" />
      </svg>
      {open && (
        <div className="custom-select-dropdown">
          {options.map((opt) => (
            <div
              key={opt.value}
              className={`custom-select-option${opt.value === value ? " selected" : ""}${opt.disabled ? " disabled" : ""}`}
              onMouseDown={(e) => {
                e.preventDefault();
                if (!opt.disabled) {
                  onChange(opt.value);
                  setOpen(false);
                }
              }}
            >
              {opt.label}
              {opt.value === value && (
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                  <polyline points="20 6 9 17 4 12" />
                </svg>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
