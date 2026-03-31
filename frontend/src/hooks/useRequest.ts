import { useCallback, useState } from "react";

export function useRequest() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>("");

  const run = useCallback(async <T>(fn: () => Promise<T>): Promise<T> => {
    setLoading(true);
    setError("");
    try {
      return await fn();
    } catch (e) {
      const msg = e instanceof Error ? e.message : "请求失败";
      setError(msg);
      throw e;
    } finally {
      setLoading(false);
    }
  }, []);

  return { loading, error, setError, run };
}
