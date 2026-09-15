import type { AnalysisResult, LineageResult } from "./types";

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: "same-origin", ...init });
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: response.statusText }));
    throw new Error((body as { message?: string }).message ?? response.statusText);
  }
  return (await response.json()) as T;
}

export function analyzeFiles(files: FileList): Promise<AnalysisResult> {
  const form = new FormData();
  Array.from(files).forEach((file) => form.append("files", file));
  return request<AnalysisResult>("/api/analysis/files", { method: "POST", body: form });
}

export function analyzeText(name: string, content: string): Promise<AnalysisResult> {
  return request<AnalysisResult>("/api/analysis/text", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, content }),
  });
}

export function currentAnalysis(): Promise<AnalysisResult> {
  return request<AnalysisResult>("/api/analysis/current");
}

export function traceLineage(
  object: string,
  direction: string,
  maxDepth: number,
): Promise<LineageResult> {
  const params = new URLSearchParams({
    object,
    direction,
    maxDepth: String(maxDepth),
  });
  return request<LineageResult>(`/api/lineage?${params.toString()}`);
}
