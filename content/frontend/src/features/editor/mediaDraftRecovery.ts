import type { PostAsset } from "@/api/types";
import type { MediaEditorState } from "@/features/editor/mediaEditor";

const DATABASE = "onix-content-media-editor-v4";
const STORE = "drafts";

export interface MediaDraftRecovery {
  key: string;
  draftId?: string;
  revisionId?: string;
  editVersion?: number;
  state: MediaEditorState;
  savedAt: string;
}

function openDatabase(): Promise<IDBDatabase | null> {
  if (typeof indexedDB === "undefined") return Promise.resolve(null);
  return new Promise((resolve) => {
    const request = indexedDB.open(DATABASE, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE, { keyPath: "key" });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null);
  });
}

async function transact<T>(mode: IDBTransactionMode, action: (store: IDBObjectStore) => IDBRequest<T>): Promise<T | undefined> {
  const db = await openDatabase();
  if (!db) return undefined;
  return new Promise((resolve) => {
    const request = action(db.transaction(STORE, mode).objectStore(STORE));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(undefined);
  });
}

function durableAsset(asset: PostAsset): PostAsset {
  const { previewUrl: _previewUrl, ...durable } = asset;
  return durable;
}

/** IndexedDB structured cloning rejects Vue's nested reactive proxies. JSON
 * normalization is intentional here: the recovery contract contains only
 * plain JSON data and explicitly excludes local object URLs. */
export function mediaDraftSnapshot(state: MediaEditorState): MediaEditorState {
  return JSON.parse(JSON.stringify({
    ...state,
    assets: state.assets.map(durableAsset),
  })) as MediaEditorState;
}

export function saveMediaDraftRecovery(key: string, draftId: string, revisionId: string, editVersion: number, state: MediaEditorState) {
  return transact("readwrite", (store) => store.put({
    key,
    draftId: draftId || undefined,
    revisionId: revisionId || undefined,
    editVersion,
    state: mediaDraftSnapshot(state),
    savedAt: new Date().toISOString(),
  } satisfies MediaDraftRecovery));
}

export function readMediaDraftRecovery(key: string) {
  return transact<MediaDraftRecovery>("readonly", (store) => store.get(key));
}

export function clearMediaDraftRecovery(key: string) {
  return transact("readwrite", (store) => store.delete(key));
}
