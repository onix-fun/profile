import type { CommentDocumentV1, PostAsset } from "@/api/types";

export interface CommentDraftRecord { document: CommentDocumentV1; attachments: PostAsset[]; savedAt: number; }
const DATABASE = "onix-content-comments-v1";
const STORE = "drafts";

function openDatabase(): Promise<IDBDatabase | null> {
  if (typeof indexedDB === "undefined") return Promise.resolve(null);
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, 1);
    request.onupgradeneeded = () => { if (!request.result.objectStoreNames.contains(STORE)) request.result.createObjectStore(STORE); };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export async function loadCommentDraft(key: string): Promise<CommentDraftRecord | null> {
  const db = await openDatabase(); if (!db) return null;
  return new Promise((resolve) => { const request=db.transaction(STORE,"readonly").objectStore(STORE).get(key);request.onsuccess=()=>resolve((request.result as CommentDraftRecord)||null);request.onerror=()=>resolve(null); });
}
export async function saveCommentDraft(key: string, value: CommentDraftRecord): Promise<void> {
  const db = await openDatabase(); if (!db) return;
  await new Promise<void>((resolve) => { const request=db.transaction(STORE,"readwrite").objectStore(STORE).put(value,key);request.onsuccess=()=>resolve();request.onerror=()=>resolve(); });
}
export async function deleteCommentDraft(key: string): Promise<void> {
  const db = await openDatabase(); if (!db) return;
  await new Promise<void>((resolve) => { const request=db.transaction(STORE,"readwrite").objectStore(STORE).delete(key);request.onsuccess=()=>resolve();request.onerror=()=>resolve(); });
}
