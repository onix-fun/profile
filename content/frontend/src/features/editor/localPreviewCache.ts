const DATABASE = "onix-content-media-previews-v1";
const STORE = "previews";
const MAX_EDGE = 1440;
let activeImages = 0;
const imageQueue: Array<() => void> = [];

function openDatabase(): Promise<IDBDatabase | null> {
  if (typeof indexedDB === "undefined") return Promise.resolve(null);
  return new Promise((resolve) => {
    const request = indexedDB.open(DATABASE, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE, { keyPath: "assetId" });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null);
  });
}

async function storeRequest<T>(mode: IDBTransactionMode, action: (store: IDBObjectStore) => IDBRequest<T>): Promise<T | undefined> {
  const db = await openDatabase();
  if (!db) return undefined;
  return new Promise((resolve) => {
    const request = action(db.transaction(STORE, mode).objectStore(STORE));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(undefined);
  });
}

export function cacheLocalPreview(assetId: string, blob: Blob) {
  return storeRequest("readwrite", (store) => store.put({ assetId, blob, savedAt: Date.now() }));
}

export async function readLocalPreview(assetId: string): Promise<Blob | null> {
  const result = await storeRequest<{ assetId: string; blob: Blob }>("readonly", (store) => store.get(assetId));
  return result?.blob || null;
}

export function deleteLocalPreview(assetId: string) {
  return storeRequest("readwrite", (store) => store.delete(assetId));
}

export function createLocalImagePreview(file: File): Promise<Blob | null> {
  if (!file.type.startsWith("image/") || typeof createImageBitmap === "undefined") return Promise.resolve(null);
  return new Promise((resolve) => {
    imageQueue.push(() => void renderPreview(file).then(resolve).finally(() => { activeImages -= 1; drainQueue(); }));
    drainQueue();
  });
}

function drainQueue() {
  while (activeImages < 2 && imageQueue.length) { activeImages += 1; imageQueue.shift()?.(); }
}

async function renderPreview(file: File): Promise<Blob | null> {
  const bitmap = await createImageBitmap(file);
  try {
    const scale = Math.min(1, MAX_EDGE / Math.max(bitmap.width, bitmap.height));
    if (scale === 1) return file;
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement("canvas");
    canvas.width = width; canvas.height = height;
    canvas.getContext("2d", { alpha: false })?.drawImage(bitmap, 0, 0, width, height);
    return await new Promise((resolve) => canvas.toBlob(resolve, "image/webp", .86));
  } finally { bitmap.close(); }
}
