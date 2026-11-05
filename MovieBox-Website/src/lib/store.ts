/** localStorage-backed favorites / history / resume (no local media data — only refs) */

export type FavItem = {
  subjectId: string;
  title: string;
  cover: string;
  subjectType: number;
  addedAt: number;
};

export type HistoryItem = {
  subjectId: string;
  title: string;
  cover: string;
  subjectType: number;
  se: number;
  ep: number;
  posSec: number;
  durSec: number;
  updatedAt: number;
};

const FAV_KEY = "anyfilm_favorites";
const HIST_KEY = "anyfilm_history";

function read<T>(key: string): T[] {
  if (typeof window === "undefined") return [];
  try {
    return JSON.parse(localStorage.getItem(key) || "[]") as T[];
  } catch {
    return [];
  }
}
function write<T>(key: string, v: T[]) {
  if (typeof window === "undefined") return;
  localStorage.setItem(key, JSON.stringify(v.slice(0, 100)));
}

export function getFavorites(): FavItem[] {
  return read<FavItem>(FAV_KEY).sort((a, b) => b.addedAt - a.addedAt);
}
export function isFavorite(subjectId: string): boolean {
  return read<FavItem>(FAV_KEY).some((f) => f.subjectId === subjectId);
}
export function toggleFavorite(item: Omit<FavItem, "addedAt">): boolean {
  const list = read<FavItem>(FAV_KEY);
  const idx = list.findIndex((f) => f.subjectId === item.subjectId);
  if (idx >= 0) {
    list.splice(idx, 1);
    write(FAV_KEY, list);
    return false;
  }
  list.unshift({ ...item, addedAt: Date.now() });
  write(FAV_KEY, list);
  return true;
}

export function getHistory(): HistoryItem[] {
  return read<HistoryItem>(HIST_KEY).sort((a, b) => b.updatedAt - a.updatedAt);
}
export function saveHistory(item: HistoryItem) {
  const list = read<HistoryItem>(HIST_KEY);
  const idx = list.findIndex((h) => h.subjectId === item.subjectId);
  if (idx >= 0) list.splice(idx, 1);
  list.unshift(item);
  write(HIST_KEY, list);
}
export function getResume(subjectId: string): HistoryItem | undefined {
  return read<HistoryItem>(HIST_KEY).find((h) => h.subjectId === subjectId);
}
export function clearHistory() {
  write(HIST_KEY, []);
}
