import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

export type SelectOption = { value: string; label: string; disabled?: boolean };

type DropdownPosition = {
  top: number;
  left: number;
  width: number;
  maxHeight: number;
  openUpward: boolean;
};

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
  const [dropdownPosition, setDropdownPosition] = useState<DropdownPosition | null>(null);
  const triggerRef = useRef<HTMLDivElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const selected = options.find((o) => o.value === value);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      const target = e.target as Node;
      if (
        triggerRef.current &&
        !triggerRef.current.contains(target) &&
        (!dropdownRef.current || !dropdownRef.current.contains(target))
      ) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [open]);

  useLayoutEffect(() => {
    if (!open) return;

    const updateDropdownPosition = () => {
      const trigger = triggerRef.current;
      if (!trigger) return;

      const rect = trigger.getBoundingClientRect();
      const viewportHeight = window.innerHeight;
      const viewportWidth = window.innerWidth;
      const gap = 6;
      const edgePadding = 16;
      const availableBelow = viewportHeight - rect.bottom - edgePadding;
      const availableAbove = rect.top - edgePadding;
      const openUpward = availableBelow < 180 && availableAbove > availableBelow;
      const maxHeight = Math.max(120, Math.min(240, openUpward ? availableAbove - gap : availableBelow - gap));
      const width = Math.min(rect.width, viewportWidth - edgePadding * 2);
      const left = Math.min(Math.max(edgePadding, rect.left), viewportWidth - width - edgePadding);

      setDropdownPosition({
        top: openUpward ? rect.top - gap : rect.bottom + gap,
        left,
        width,
        maxHeight,
        openUpward
      });
    };

    updateDropdownPosition();
    window.addEventListener("resize", updateDropdownPosition);
    window.addEventListener("scroll", updateDropdownPosition, true);

    return () => {
      window.removeEventListener("resize", updateDropdownPosition);
      window.removeEventListener("scroll", updateDropdownPosition, true);
    };
  }, [open]);

  return (
    <>
      <div
        ref={triggerRef}
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
      </div>
      {open && dropdownPosition && createPortal(
        <div
          ref={dropdownRef}
          className={`custom-select-dropdown${dropdownPosition.openUpward ? " custom-select-dropdown-upward" : ""}`}
          style={{
            position: "fixed",
            top: dropdownPosition.top,
            left: dropdownPosition.left,
            width: dropdownPosition.width,
            maxHeight: dropdownPosition.maxHeight,
            transform: dropdownPosition.openUpward ? "translateY(-100%)" : undefined
          }}
        >
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
        </div>,
        document.body
      )}
    </>
  );
}
