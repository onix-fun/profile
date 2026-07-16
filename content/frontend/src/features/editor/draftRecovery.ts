import type { PostEditorState } from "@/features/editor/postEditor";

const DATABASE = "onix-content";
const STORE = "draft-recovery";
const KEY = "active-post";

export interface RecoveredPostDraft {
  id?: string;
  state: PostEditorState;
  savedAt: string;
}

function database(): Promise<IDBDatabase | null> {
  if (typeof indexedDB === "undefined") return Promise.resolve(null);
  return new Promise((resolve) => {
    const request = indexedDB.open(DATABASE, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null);
  });
}

async function transaction<T>(mode: IDBTransactionMode, operation: (store: IDBObjectStore) => IDBRequest<T>): Promise<T | undefined> {
  const db = await database();
  if (!db) return undefined;
  return new Promise((resolve) => {
    const request = operation(db.transaction(STORE, mode).objectStore(STORE));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(undefined);
  });
}

export function saveDraftRecovery(draft: RecoveredPostDraft): Promise<unknown> {
  return transaction("readwrite", (store) => store.put(draft, KEY));
}

export function readDraftRecovery(): Promise<RecoveredPostDraft | undefined> {
  return transaction<RecoveredPostDraft>("readonly", (store) => store.get(KEY));
}

export function clearDraftRecovery(): Promise<unknown> {
  return transaction("readwrite", (store) => store.delete(KEY));
}
