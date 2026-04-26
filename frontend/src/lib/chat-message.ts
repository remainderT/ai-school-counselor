import type { RetrievalMatch } from "../types";

const LEGACY_REFERENCE_SECTION_RE = /\n{0,2}参考来源[:：]\s*(?:\r?\n)?(?:\[\d+\][\s\S]*)$/;

export function stripLegacyReferenceSection(content?: string): string {
  const text = content || "";
  return text.replace(LEGACY_REFERENCE_SECTION_RE, "").trimEnd();
}

export function normalizeSources(sources?: RetrievalMatch[]): RetrievalMatch[] {
  if (!Array.isArray(sources) || sources.length === 0) {
    return [];
  }

  const unique = new Map<string, RetrievalMatch>();
  for (const source of sources) {
    if (!source) continue;
    const key = [
      source.fileMd5 || source.sourceFileName || "unknown",
      source.chunkId ?? "none"
    ].join(":");
    if (!unique.has(key)) {
      unique.set(key, source);
    }
  }

  return [...unique.values()].sort(
    (left, right) => (right.relevanceScore ?? 0) - (left.relevanceScore ?? 0)
  );
}

export function formatSourceScore(score?: number): string {
  if (typeof score !== "number" || Number.isNaN(score)) {
    return "未评分";
  }
  return score.toFixed(2);
}
