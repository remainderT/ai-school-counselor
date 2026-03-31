import { useCallback } from "react";
import { toErrorMessage } from "../lib/api";
import { pushToast, type ToastTone } from "../lib/toast";
import { useRequest } from "./useRequest";

type ActionResult<T> =
  | { ok: true; data: T }
  | { ok: false; error: string };

type ActionOptions<T> = {
  errorFallback?: string;
  successToast?: string | ((data: T) => string);
  errorToast?: boolean;
  onSuccess?: (data: T) => void | Promise<void>;
  onError?: (message: string) => void;
};

export function useActionRequest() {
  const req = useRequest();

  const toast = useCallback((msg: string, tone: ToastTone) => {
    if (!msg) return;
    pushToast(msg, tone);
  }, []);

  const runAction = useCallback(
    async <T>(fn: () => Promise<T>, options?: ActionOptions<T>): Promise<ActionResult<T>> => {
      try {
        const data = await req.run(fn);
        const successText = options?.successToast;
        if (typeof successText === "function") {
          toast(successText(data), "success");
        } else if (typeof successText === "string" && successText.trim()) {
          toast(successText, "success");
        }
        if (options?.onSuccess) {
          await options.onSuccess(data);
        }
        return { ok: true, data };
      } catch (e) {
        const message = toErrorMessage(e, options?.errorFallback || "请求失败");
        req.setError(message);
        if (options?.errorToast !== false) {
          toast(message, "error");
        }
        if (options?.onError) {
          options.onError(message);
        }
        return { ok: false, error: message };
      }
    },
    [req, toast]
  );

  return {
    loading: req.loading,
    error: req.error,
    setError: req.setError,
    runAction
  };
}
